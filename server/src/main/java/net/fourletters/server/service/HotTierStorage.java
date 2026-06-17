package net.fourletters.server.service;

import net.fourletters.dto.EncryptedMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The discardable in-memory <b>hot tier</b> of the server-owned inbox.
 *
 * <p>Encapsulates the heap state a message needs while it is held during the post-accept
 * hold window, plus the atomic operations the {@link InboxService} and its sweeper perform
 * on it:
 * <ul>
 *   <li>the per-recipient set of held messages, keyed by message id,</li>
 *   <li>the {@code messageId -> recipientId} ownership token used as the single hand-off
 *       claim between an incoming receipt and the flush sweeper, and</li>
 *   <li>the {@code messageId -> accept instant} marker, used both to detect hold-window
 *       expiry and to order messages by arrival.</li>
 * </ul>
 *
 * <p>Durability is intentionally <em>not</em> a concern here — the sender's outbox backs the
 * window — so all state is plain, non-persistent JVM heap.
 */
class HotTierStorage {

    /** recipientId -> (messageId -> message). */
    private final Map<UUID, Map<UUID, EncryptedMessage>> messagesByRecipient = new ConcurrentHashMap<>();
    /** messageId -> recipientId; the atomic claim token shared by receipts and the sweeper. */
    private final Map<UUID, UUID> messageRecipients = new ConcurrentHashMap<>();
    /** messageId -> accept instant; drives hold-window expiry and arrival ordering. */
    private final Map<UUID, Instant> enqueuedAt = new ConcurrentHashMap<>();

    /** Retain a message in the hot tier and register its ownership and accept time. */
    void store(EncryptedMessage message) {
        UUID recipientId = message.getRecipientId();
        messagesByRecipient.computeIfAbsent(recipientId, r -> new ConcurrentHashMap<>())
                .put(message.getMessageId(), message);
        messageRecipients.put(message.getMessageId(), recipientId);
        enqueuedAt.put(message.getMessageId(), Instant.now());
    }

    /** A recipient's held messages, in arrival order. */
    Collection<EncryptedMessage> messagesFor(UUID recipientId) {
        Map<UUID, EncryptedMessage> pending = messagesByRecipient.get(recipientId);
        if (pending == null) {
            return List.of();
        }
        List<EncryptedMessage> ordered = new ArrayList<>(pending.values());
        ordered.sort(Comparator.comparing(m -> enqueuedAt.getOrDefault(m.getMessageId(), Instant.EPOCH)));
        return ordered;
    }

    /**
     * Atomically claim and remove a held message on behalf of its rightful recipient. Succeeds
     * only if {@code recipientId} currently owns the message <em>and</em> the sweeper has not
     * already claimed it for flushing — guaranteeing a confirmed-in-window message never hits
     * the database.
     *
     * @return the removed message, or {@code null} if this caller does not own a held copy
     */
    EncryptedMessage claimByRecipient(UUID messageId, UUID recipientId) {
        if (!messageRecipients.remove(messageId, recipientId)) {
            return null;
        }
        enqueuedAt.remove(messageId);
        return removeFromMessageMap(recipientId, messageId);
    }

    /** Snapshot of message ids whose hold window elapsed at or before {@code cutoff}. */
    List<UUID> expiredMessageIds(Instant cutoff) {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Instant> entry : enqueuedAt.entrySet()) {
            if (!entry.getValue().isAfter(cutoff)) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    /**
     * Unconditionally claim a held message for the flush sweeper and return it, ready to be
     * persisted. A receipt that already claimed it removed this token, so a {@code null}
     * result means there is nothing left to flush (a concurrent receipt won the race, or the
     * copy was already evicted); in that case the stale accept marker is dropped here so the
     * sweeper does not revisit it. The hot copy is intentionally <em>left in place</em> until
     * {@link #evict(EncryptedMessage)} confirms it is durable — preserving persist-then-evict.
     *
     * @return the message to persist, or {@code null} if it is no longer claimable
     */
    EncryptedMessage claimForFlush(UUID messageId) {
        UUID recipientId = messageRecipients.remove(messageId);
        if (recipientId == null) {
            enqueuedAt.remove(messageId);
            return null;
        }
        EncryptedMessage message = peek(recipientId, messageId);
        if (message == null) {
            enqueuedAt.remove(messageId);
        }
        return message;
    }

    /** Drop a hot copy after it has been durably persisted. */
    void evict(EncryptedMessage message) {
        removeFromMessageMap(message.getRecipientId(), message.getMessageId());
        enqueuedAt.remove(message.getMessageId());
    }

    /** Release a flush claim back to the recipient after a persist failure, leaving the hot copy intact. */
    void releaseClaim(EncryptedMessage message) {
        messageRecipients.putIfAbsent(message.getMessageId(), message.getRecipientId());
    }

    private EncryptedMessage peek(UUID recipientId, UUID messageId) {
        Map<UUID, EncryptedMessage> pending = messagesByRecipient.get(recipientId);
        return pending == null ? null : pending.get(messageId);
    }

    private EncryptedMessage removeFromMessageMap(UUID recipientId, UUID messageId) {
        Map<UUID, EncryptedMessage> pending = messagesByRecipient.get(recipientId);
        return pending == null ? null : pending.remove(messageId);
    }
}
