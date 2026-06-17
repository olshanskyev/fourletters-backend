package net.fourletters.server.service;

import net.fourletters.dto.AcceptedResponse;
import net.fourletters.dto.DeliveryReceipt;
import net.fourletters.dto.EncryptedMessage;
import net.fourletters.dto.InboxResponse;
import net.fourletters.dto.MessageBatchResponse;
import net.fourletters.dto.ReceiptData;
import net.fourletters.dto.ReceiptEvent;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.InboxMessage;
import net.fourletters.server.repository.InboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
 * that window. A signed receipt drops the copy from whichever tier holds it; {@code /inbox}
 * reads return the union of both tiers.
 */
@Service
public class InboxService {

    private static final Logger logger = LoggerFactory.getLogger(InboxService.class);

    private final ServerRabbitMqService rabbitMqService;
    private final InboxMessageRepository inboxRepository;

    /** Hold window length */
    private final Duration holdWindow;

    /**
     * Epoch milliseconds at which this Server process started, surfaced on responses so a
     * client can detect a restart (a changed value means the in-memory hot tier was lost).
     */
    private final long serverStartedAt = System.currentTimeMillis();

    /** In-memory hot tier: all heap state and its atomic operations. */
    private final HotTierStorage hotTier = new HotTierStorage();

    public InboxService(ServerRabbitMqService rabbitMqService,
                        InboxMessageRepository inboxRepository,
                        @Value("${inbox.hold-window-seconds:30}") long holdWindowSeconds) {
        this.rabbitMqService = rabbitMqService;
        this.inboxRepository = inboxRepository;
        this.holdWindow = Duration.ofSeconds(holdWindowSeconds);
    }

    /**
     * Accept an outgoing message: stamp the authenticated sender, retain it in the hot tier,
     * and publish it for live delivery.
     */
    public AcceptedResponse accept(EncryptedMessage message, UUID senderId) {
        // senderId is authoritative from the session; any client-provided value is ignored.
        message.setSenderId(senderId);

        hotTier.store(message);

        rabbitMqService.publishMessage(message);
        logger.debug("Accepted message {} for recipient {}", message.getMessageId(), message.getRecipientId());

        AcceptedResponse response = new AcceptedResponse();
        response.setMessageId(message.getMessageId());
        response.setStatus(AcceptedResponse.StatusEnum.ACCEPTED);
        response.setServerStartedAt(serverStartedAt);
        return response;
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
     * tiers, in arrival order. A message in transit between tiers appears in both (a harmless
     * duplicate the client de-duplicates by message id) and never in neither (no gap).
     */
    public InboxResponse getInbox(UUID recipientId) {
        List<EncryptedMessage> messages = new ArrayList<>();
        // Cold rows are older than anything still held, so they come first, in arrival order.
        for (InboxMessage row : inboxRepository.findByRecipientIdOrderByCreatedAtAsc(recipientId)) {
            messages.add(row.toDto());
        }
        messages.addAll(hotTier.messagesFor(recipientId));

        InboxResponse response = new InboxResponse();
        response.setMessages(messages);
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
        relayReceipt(recipientId, messageId, target, receipt.getType());
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
        InboxMessage row = inboxRepository.findById(messageId).orElse(null);
        if (row != null && recipientId.equals(row.getRecipientId())) {
            inboxRepository.deleteByMessageId(messageId);
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
        for (UUID messageId : hotTier.expiredMessageIds(cutoff)) {
            // Atomically claim the message and get the copy to persist;
            EncryptedMessage message = hotTier.claimForFlush(messageId);
            if (message == null) {
                continue;
            }
            try {
                // Persist to the durable tier first, then drop the hot copy.
                inboxRepository.save(InboxMessage.from(message));
                hotTier.evict(message);
                logger.debug("Flushed message {} for recipient {} to durable inbox",
                        messageId, message.getRecipientId());
            } catch (RuntimeException e) {
                // Persist failed: release the claim and leave the hot copy in place so the
                // next sweep (or an incoming receipt) retries. Sender's outbox is the backup.
                hotTier.releaseClaim(message);
                logger.warn("Failed to flush message {} to durable inbox; will retry", messageId, e);
            }
        }
    }


    private void relayReceipt(UUID recipientId, UUID messageId, UUID senderId, DeliveryReceipt.TypeEnum type) {
        ReceiptData data = new ReceiptData();
        data.setMessageId(messageId);
        data.setRecipientId(recipientId);

        ReceiptEvent event = new ReceiptEvent();
        event.setEvent(type == DeliveryReceipt.TypeEnum.READ
                ? ReceiptEvent.EventEnum.MESSAGE_READ
                : ReceiptEvent.EventEnum.MESSAGE_DELIVERED);
        event.setData(data);

        rabbitMqService.publishReceipt(senderId, event);
        logger.debug("Dropped message {} on {} receipt; relayed to sender {}", messageId, type, senderId);
    }
}
