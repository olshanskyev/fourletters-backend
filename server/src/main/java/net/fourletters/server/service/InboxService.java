package net.fourletters.server.service;

import net.fourletters.dto.*;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.InboxMessage;
import net.fourletters.server.model.InboxPending;
import net.fourletters.server.repository.InboxMessageRepository;
import net.fourletters.server.repository.InboxPendingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-owned message inbox — the trusted owner of delivery.
 *
 * <p>Two-tier store: a discardable in-memory <b>hot tier</b> (encapsulated in
 * {@link HotTierStorage}) holds messages during the post-accept hold window, and the durable
 * PostgreSQL <b>cold tier</b> is written exactly once if a message is not confirmed within
 * that window. A receipt drops the copy from whichever tier holds it; {@code /inbox}
 * reads return the union of both tiers.
 */
@Service
public class InboxService {

    private static final Logger logger = LoggerFactory.getLogger(InboxService.class);

    private final ServerRabbitMqService rabbitMqService;
    private final InboxMessageRepository inboxRepository;
    private final InboxPendingRepository inboxPendingRepository;
    private final GroupService groupService;
    private final PushNotificationService pushNotificationService;

    /** Hold window length */
    private final Duration holdWindow;

    /**
     * Epoch milliseconds at which this Server process started, surfaced on responses so a
     * client can detect a restart (a changed value means the in-memory hot tier was lost).
     */
    private final long serverStartedAt = System.currentTimeMillis();

    /** In-memory hot tier holding accepted messages during the hold window. */
    private final HotTierStorage tier = new HotTierStorage();

    /** Cold projection of a flushed message: one payload plus one row per pending recipient. */
    private final HotTierStorage.ColdSink coldSink;

    /** In-memory backstop of acks owed to offline senders, pulled via /inbox. */
    private final PendingReceipts pendingReceipts;

    public InboxService(ServerRabbitMqService rabbitMqService,
                        InboxMessageRepository inboxRepository,
                        InboxPendingRepository inboxPendingRepository,
                        GroupService groupService,
                        PendingReceipts pendingReceipts,
                        PushNotificationService pushNotificationService,
                        @Value("${inbox.hold-window-seconds:30}") long holdWindowSeconds) {
        this.rabbitMqService = rabbitMqService;
        this.inboxRepository = inboxRepository;
        this.inboxPendingRepository = inboxPendingRepository;
        this.groupService = groupService;
        this.pendingReceipts = pendingReceipts;
        this.pushNotificationService = pushNotificationService;
        this.holdWindow = Duration.ofSeconds(holdWindowSeconds);
        this.coldSink = (message, pending) -> {
            inboxRepository.save(InboxMessage.from(message));
            for (UUID recipientId : pending) {
                inboxPendingRepository.save(new InboxPending(message.getMessageId(), recipientId));
            }
            // Backstop wake-up: a message reaching the cold tier went unacknowledged for the whole
            // hold window, so each still-pending recipient is offline or was on a Hub binding that
            // silently died
            notifyPending(message, pending);
        };
    }

    /**
     * Fire a best-effort push wake-up to every recipient still owed a flushed message. Guarded so a
     * push failure can never disrupt the surrounding persist-then-evict flush.
     */
    private void notifyPending(EncryptedMessage message, Set<UUID> pending) {
        try {
            for (UUID recipientId : pending) {
                pushNotificationService.notifyRecipient(
                        recipientId, message.getSenderId(), message.getGroupId(), message.getMessageId(), true);
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to fire cold-tier push backstop for message {}", message.getMessageId(), e);
        }
    }

    /**
     * Accept an outgoing message: stamp the authenticated sender, retain it, and publish it for
     * live delivery.
     *
     * <p>A <b>1:1</b> message (a {@code recipientId}, no {@code groupId}) is held as a single
     * recipient copy. A <b>group</b> message (a {@code groupId}, no {@code recipientId}) is
     * encrypted once with the sender's Sender Key; the Server reads the roster and stores that
     * single payload <em>once</em>, tracking the set of members still owed delivery, then publishes
     * the same copy to each member.
     */
    public AcceptedResponse accept(EncryptedMessage message, UUID senderId) {
        // senderId is authoritative from the session; any client-provided value is ignored.
        message.setSenderId(senderId);

        if (message.getGroupId() != null && message.getRecipientId() == null) {
            acceptGroup(message, senderId);
        } else {
            acceptDirect(message);
        }

        AcceptedResponse response = new AcceptedResponse();
        response.setMessageId(message.getMessageId());
        response.setStatus(AcceptedResponse.StatusEnum.ACCEPTED);
        response.setServerStartedAt(serverStartedAt);
        return response;
    }

    /**
     * Store a single-copy direct message.
     */
    private void acceptDirect(EncryptedMessage message) {
        UUID recipientId = message.getRecipientId();
        if (recipientId == null) {
            throw new IllegalArgumentException("A 1:1 message requires a recipientId");
        }
        tier.store(message, Set.of(recipientId));
        rabbitMqService.publishMessage(message);
        logger.debug("Accepted message {} for recipient {}",
                message.getMessageId(), recipientId);
    }

    /**
     * Store a single-copy group message and fan it out. The roster (minus the sender) is the set of
     * members owed delivery.
     */
    private void acceptGroup(EncryptedMessage message, UUID senderId) {
        Set<UUID> recipients = new LinkedHashSet<>(groupService.groupRoster(message.getGroupId()));
        recipients.remove(senderId);
        if (recipients.isEmpty()) {
            logger.debug("Group message {} has no other recipients; dropping", message.getMessageId());
            return;
        }

        tier.store(message, recipients);
        for (UUID recipientId : recipients) {
            rabbitMqService.publishMessage(publishCopy(message, recipientId));
        }
        logger.debug("Accepted group message {} for {} recipients (single copy)",
                message.getMessageId(), recipients.size());
    }

    /** A per-recipient view of a stored group copy, for live publishing (threading needs a recipient). */
    private static EncryptedMessage publishCopy(EncryptedMessage source, UUID recipientId) {
        EncryptedMessage copy = new EncryptedMessage();
        copy.setMessageId(source.getMessageId());
        copy.setSenderId(source.getSenderId());
        copy.setGroupId(source.getGroupId());
        copy.setPayload(source.getPayload());
        copy.setRecipientId(recipientId);
        return copy;
    }

    /**
     * Accept a batch of messages in one call (used by the client to re-submit unconfirmed
     * outbox messages after a detected restart). Each is accepted exactly as a single send —
     * stamped, held in the hot tier, and published — and is idempotent by {@code messageId}.
     * {@code serverStartedAt} is carried once on the batch envelope, not per result.
     */
    public MessageBatchResponse acceptAll(List<EncryptedMessage> messages, UUID senderId) {
        List<AcceptedResponse> results = new ArrayList<>(messages.size());
        for (EncryptedMessage message : messages) {
            AcceptedResponse accepted = accept(message, senderId);
            accepted.setServerStartedAt(null); // carried once on the envelope below
            results.add(accepted);
        }

        MessageBatchResponse response = new MessageBatchResponse();
        response.setResults(results);
        response.setServerStartedAt(serverStartedAt);
        return response;
    }

    /**
     * Return the recipient's retained messages from the <b>union</b> of the hot and cold
     * tiers, in arrival order, plus any acknowledgements owed to this user as a sender that
     * accumulated while they were offline. A message in transit between tiers appears in both
     * (a harmless duplicate the client de-duplicates by message id) and never in neither.
     */
    public InboxResponse getInbox(UUID userId) {
        List<EncryptedMessage> messages = new ArrayList<>();
        // Cold rows are older than anything still held, so they come first, in arrival order.
        for (InboxMessage row : inboxRepository.findPendingForRecipient(userId)) {
            EncryptedMessage dto = row.toDto();
            dto.setRecipientId(userId);
            messages.add(dto);
        }
        messages.addAll(tier.pendingFor(userId));

        InboxResponse response = new InboxResponse();
        response.setMessages(messages);
        response.setReceipts(pendingReceipts.drain(userId));
        response.setServerStartedAt(serverStartedAt);
        return response;
    }

    /**
     * Record a delivery/read/undecryptable receipt from the authenticated recipient: drop the
     * retained copy (on the first receipt — once acknowledged the Server need not keep it) and
     * relay the receipt to the original sender.
     * Idempotent for repeats.
     */
    public void recordReceipt(UUID recipientId, DeliveryReceipt receipt) {
        UUID messageId = receipt.getMessageId();

        // Any receipt means the recipient has the message; drop the retained copy once.
        UUID storedSenderId = dropRetainedCopy(messageId, recipientId);

        // The message left the hot tier via a receipt, forget any push marker for it.
        pushNotificationService.clearPushed(messageId, recipientId);

        // Relay to the original sender. Prefer the sender carried in the receipt (so a later
        // receipt relays even though the copy is gone); fall back to the stored sender for the
        // first receipt or clients that omit it.
        UUID target = receipt.getOriginalSenderId() != null ? receipt.getOriginalSenderId() : storedSenderId;
        if (target == null) {
            logger.debug("Ignoring receipt for message {} from {} (no relay target)", messageId, recipientId);
            return;
        }
        // Retain the ack (for pull via /inbox) and publish it live as a best-effort fast path.
        relayReceipt(recipientId, messageId, target, receipt.getType(), receipt.getSignature());
    }

    /**
     * Record a batch of receipts from the authenticated recipient.
     */
    public void recordReceipts(UUID recipientId, java.util.List<DeliveryReceipt> receipts) {
        for (DeliveryReceipt receipt : receipts) {
            recordReceipt(recipientId, receipt);
        }
    }

    /**
     * Drop the retained copy from whichever tier holds it, for this recipient (1:1 or group).
     *
     * @return the original sender id (to relay the receipt), or {@code null} if nothing was held
     */
    private UUID dropRetainedCopy(UUID messageId, UUID recipientId) {
        // Hot tier first (in-memory, no DB). If it handles the receipt the cold tier is never touched.
        HotTierStorage.Ack ack = tier.acknowledge(messageId, recipientId);
        if (ack.handled()) {
            return ack.senderId();
        }
        // Flushed: clear the durable copy.
        return dropColdCopy(messageId, recipientId);
    }

    /**
     * Clear a flushed message's cold copy for this recipient: drop the recipient's pending row (a DB
     * trigger drops the shared payload once the last recipient is gone).
     *
     * @return the original sender id, or {@code null} if no durable copy was found
     */
    private UUID dropColdCopy(UUID messageId, UUID recipientId) {
        InboxMessage row = inboxRepository.findById(messageId).orElse(null);
        if (row == null) {
            return null;
        }
        // A 1:1 message has a single recipient; tombstone it so a duplicate receipt short-circuits.
        if (row.getGroupId() == null) {
            tier.markSettled(messageId);
        }
        inboxPendingRepository.deleteByMessageIdAndRecipientId(messageId, recipientId);
        return row.getSenderId();
    }

    /**
     * Hold-window sweeper: flush messages older than the hold window to the durable inbox, then
     * evict them. The tier claims before persisting (so a concurrent receipt can't also process it)
     * and persists-then-evicts, so a message is never absent from both tiers.
     */
    @Scheduled(fixedDelayString = "${inbox.flush-interval-ms:5000}")
    void flushExpired() {
        Instant cutoff = Instant.now().minus(holdWindow);
        tier.flushExpired(cutoff, coldSink);
        // Expire 1:1 settle tombstones older than the hold window.
        tier.sweep(cutoff);
    }


    private void relayReceipt(UUID recipientId, UUID messageId, UUID senderId, ReceiptType type, String signature) {
        // Retain the ack for pull via /inbox; this is what lets it survive a "zombie" sender
        // binding (a live publish routed to a silently-dropped connection). The sender applies it
        // idempotently, live and/or on its next /inbox.
        pendingReceipts.record(senderId, messageId, recipientId, type, signature);

        ReceiptData data = new ReceiptData();
        data.setMessageId(messageId);
        data.setRecipientId(recipientId);
        data.setSignature(signature);
        data.setType(type);
        ReceiptEvent event = new ReceiptEvent();
        event.setEvent(eventTypeFor(type));

        event.setData(data);

        rabbitMqService.publishReceipt(senderId, event);
        logger.debug("Relayed {} receipt for message {} to sender {} (retained for /inbox)",
                type, messageId, senderId);
    }

    /**
     * Map a receipt type to its live WS relay event.
     */
    private static ReceiptEvent.EventEnum eventTypeFor(ReceiptType type) {
        return switch (type) {
            case READ -> ReceiptEvent.EventEnum.MESSAGE_READ;
            case UNDECRYPTABLE -> ReceiptEvent.EventEnum.MESSAGE_UNDECRYPTABLE;
            case DELIVERED -> ReceiptEvent.EventEnum.MESSAGE_DELIVERED;
        };
    }
}
