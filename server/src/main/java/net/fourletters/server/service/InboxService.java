package net.fourletters.server.service;

import net.fourletters.dto.*;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.InboxMessage;
import net.fourletters.server.model.InboxMessageId;
import net.fourletters.server.repository.InboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final GroupService groupService;

    /** Hold window length */
    private final Duration holdWindow;

    /**
     * Epoch milliseconds at which this Server process started, surfaced on responses so a
     * client can detect a restart (a changed value means the in-memory hot tier was lost).
     */
    private final long serverStartedAt = System.currentTimeMillis();

    /** In-memory hot tier: all heap state and its atomic operations. */
    private final HotTierStorage hotTier = new HotTierStorage();

    /** In-memory backstop of acks owed to offline senders, pulled via /inbox. */
    private final PendingReceipts pendingReceipts;

    public InboxService(ServerRabbitMqService rabbitMqService,
                        InboxMessageRepository inboxRepository,
                        GroupService groupService,
                        PendingReceipts pendingReceipts,
                        @Value("${inbox.hold-window-seconds:30}") long holdWindowSeconds) {
        this.rabbitMqService = rabbitMqService;
        this.inboxRepository = inboxRepository;
        this.groupService = groupService;
        this.pendingReceipts = pendingReceipts;
        this.holdWindow = Duration.ofSeconds(holdWindowSeconds);
    }

    /**
     * Accept an outgoing message: stamp the authenticated sender, retain it in the hot tier,
     * and publish it for live delivery. A 1:1 message produces a single recipient copy; a group
     * message (carrying {@code groupId}) is fanned out to one independent copy per current member
     * — excluding the sender — each sharing the client-generated {@code messageId} and carrying the
     * group's {@code groupId}/{@code epoch}. Each copy is held, published, and later receipted on
     * its own, so a member's ack only clears that member's copy.
     */
    public AcceptedResponse accept(EncryptedMessage message, UUID senderId) {
        // senderId is authoritative from the session; any client-provided value is ignored.
        message.setSenderId(senderId);

        if (message.getGroupId() != null) {
            acceptGroup(message, senderId);
        } else {
            acceptOneToOne(message);
        }

        AcceptedResponse response = new AcceptedResponse();
        response.setMessageId(message.getMessageId());
        response.setStatus(AcceptedResponse.StatusEnum.ACCEPTED);
        response.setServerStartedAt(serverStartedAt);
        return response;
    }

    /** Hold and publish a single recipient copy. */
    private void acceptOneToOne(EncryptedMessage message) {
        hotTier.store(message);
        rabbitMqService.publishMessage(message);
        logger.debug("Accepted message {} for recipient {}", message.getMessageId(), message.getRecipientId());
    }

    /**
     * Fan a group message out to every current member except the sender. The sender must be a
     * member; the resulting per-member copies are independent inbox rows keyed by
     * {@code (messageId, recipientId)}.
     */
    private void acceptGroup(EncryptedMessage message, UUID senderId) {
        UUID groupId = message.getGroupId();
        List<UUID> members = groupService.membersOf(groupId);
        if (!members.contains(senderId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "sender is not a member of the group");
        }
        for (UUID memberId : members) {
            if (memberId.equals(senderId)) {
                continue; // the sender already holds its own copy
            }
            EncryptedMessage copy = fanOutCopy(message, memberId);
            hotTier.store(copy);
            rabbitMqService.publishMessage(copy);
        }
        logger.debug("Fanned group message {} (group {}, epoch {}) out to {} members",
                message.getMessageId(), groupId, message.getEpoch(), members.size());
    }

    /** A per-member copy of a group message: same id/payload/signature, member-specific recipient. */
    private EncryptedMessage fanOutCopy(EncryptedMessage source, UUID recipientId) {
        EncryptedMessage copy = new EncryptedMessage();
        copy.setMessageId(source.getMessageId());
        copy.setSenderId(source.getSenderId());
        copy.setRecipientId(recipientId);
        copy.setPayload(source.getPayload());
        copy.setSignature(source.getSignature());
        copy.setGroupId(source.getGroupId());
        copy.setEpoch(source.getEpoch());
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
        for (InboxMessage row : inboxRepository.findByRecipientIdOrderByCreatedAtAsc(userId)) {
            messages.add(row.toDto());
        }
        messages.addAll(hotTier.messagesFor(userId));

        InboxResponse response = new InboxResponse();
        response.setMessages(messages);
        response.setReceipts(pendingReceipts.drain(userId));
        response.setGroupKeys(groupService.drainGroupKeysFor(userId));
        response.setServerStartedAt(serverStartedAt);
        return response;
    }

    /**
     * Record a delivery/read receipt from the authenticated recipient: drop the retained copy
     * (on the first receipt — delivery is satisfied once acknowledged) and relay the receipt
     * to the original sender. Relaying uses the sender named in the receipt, so a second
     * receipt (e.g. {@code read} arriving after {@code delivered}, in either order) is still
     * relayed after the copy has been dropped. Idempotent for repeats.
     */
    public void recordReceipt(UUID recipientId, DeliveryReceipt receipt) {
        UUID messageId = receipt.getMessageId();

        // Any receipt means the recipient has the message; drop the retained copy once.
        UUID storedSenderId = dropRetainedCopy(messageId, recipientId);

        // Relay to the original sender. Prefer the sender carried in the receipt (so a later
        // receipt relays even though the copy is gone); fall back to the stored sender for the
        // first receipt or clients that omit it.
        UUID target = receipt.getOriginalSenderId() != null ? receipt.getOriginalSenderId() : storedSenderId;
        if (target == null) {
            logger.debug("Ignoring receipt for message {} from {} (no relay target)", messageId, recipientId);
            return;
        }
        // Relay live. If the sender is offline the publish comes back unroutable and the broker
        // service retains it in PendingReceipts for the sender to pull via /inbox; an online
        // sender gets it live and nothing is stored.
        relayReceipt(recipientId, messageId, target, receipt.getType(), receipt.getSignature());
    }

    /**
     * Drop the retained copy of a message from whichever tier holds it, if it belongs to this
     * recipient. Returns the original sender recorded on the copy, or {@code null} if no copy
     * is held (already dropped, never accepted, or not this recipient's message).
     */
    private UUID dropRetainedCopy(UUID messageId, UUID recipientId) {
        EncryptedMessage stored = hotTier.claimByRecipient(messageId, recipientId);
        if (stored != null) {
            return stored.getSenderId();
        }
        InboxMessage row = inboxRepository
                .findById(new InboxMessageId(messageId, recipientId))
                .orElse(null);
        if (row != null) {
            inboxRepository.deleteByMessageIdAndRecipientId(messageId, recipientId);
            return row.getSenderId();
        }
        return null;
    }

    /**
     * Hold-window sweeper: flush every message older than the hold window to the durable
     * inbox, then evict it from memory. Each message is <em>claimed</em> in the hot tier
     * before persisting, so a concurrent receipt can never also process it; the claim
     * guarantees a confirmed-in-window message is never written. Order is
     * <b>persist-then-evict</b> so a message is never absent from both tiers.
     */
    @Scheduled(fixedDelayString = "${inbox.flush-interval-ms:5000}")
    void flushExpired() {
        Instant cutoff = Instant.now().minus(holdWindow);
        for (HotTierStorage.MessageKey key : hotTier.expiredKeys(cutoff)) {
            // Atomically claim the message and get the copy to persist;
            EncryptedMessage message = hotTier.claimForFlush(key);
            if (message == null) {
                continue;
            }
            try {
                // Persist to the durable tier first, then drop the hot copy.
                inboxRepository.save(InboxMessage.from(message));
                hotTier.evict(message);
                logger.debug("Flushed message {} for recipient {} to durable inbox",
                        key.messageId(), message.getRecipientId());
            } catch (RuntimeException e) {
                // Persist failed: release the claim and leave the hot copy in place so the
                // next sweep (or an incoming receipt) retries. Sender's outbox is the backup.
                hotTier.releaseClaim(message);
                logger.warn("Failed to flush message {} to durable inbox; will retry", key.messageId(), e);
            }
        }
    }


    private void relayReceipt(UUID recipientId, UUID messageId, UUID senderId, ReceiptType type, String signature) {
        ReceiptData data = new ReceiptData();
        data.setMessageId(messageId);
        data.setRecipientId(recipientId);
        data.setSignature(signature);
        data.setType(type);
        ReceiptEvent event = new ReceiptEvent();
        event.setEvent(type == ReceiptType.READ
                ? ReceiptEvent.EventEnum.MESSAGE_READ
                : ReceiptEvent.EventEnum.MESSAGE_DELIVERED);

        event.setData(data);

        rabbitMqService.publishReceipt(senderId, event);
        logger.debug("Dropped message {} on {} receipt; relayed to sender {}", messageId, type, senderId);
    }
}
