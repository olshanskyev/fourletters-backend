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
 *   <li>the per-recipient ownership token, keyed by {@link MessageKey}, used as the single
 *       hand-off claim between an incoming receipt and the flush sweeper, and</li>
 *   <li>the per-recipient {@code accept instant} marker, used both to detect hold-window
 *       expiry and to order messages by arrival.</li>
 * </ul>
 *
 * <p>The claim and accept-time maps are keyed by {@link MessageKey} rather than message id alone:
 * a group message is fanned out to one held copy per member, all sharing the client-generated
 * {@code messageId}, so the recipient must be part of the key for the copies not to collide.
 *
 * <p>Durability is intentionally <em>not</em> a concern here — the sender's outbox backs the
 * window — so all state is plain, non-persistent JVM heap.
 */
class HotTierStorage {

    /** Per-recipient identity of a held copy; group fan-out shares {@code messageId} across members. */
    record MessageKey(UUID messageId, UUID recipientId) {
    }

    /** recipientId -> (messageId -> message). */
    private final Map<UUID, Map<UUID, EncryptedMessage>> messagesByRecipient = new ConcurrentHashMap<>();
    /** (messageId, recipientId) -> held; the atomic claim token shared by receipts and the sweeper. */
    private final Map<MessageKey, Boolean> claims = new ConcurrentHashMap<>();
    /** (messageId, recipientId) -> accept instant; drives hold-window expiry and arrival ordering. */
    private final Map<MessageKey, Instant> enqueuedAt = new ConcurrentHashMap<>();

    /** Retain a message in the hot tier and register its ownership and accept time. */
    void store(EncryptedMessage message) {
        UUID recipientId = message.getRecipientId();
        MessageKey key = new MessageKey(message.getMessageId(), recipientId);
        messagesByRecipient.computeIfAbsent(recipientId, r -> new ConcurrentHashMap<>())
                .put(message.getMessageId(), message);
        claims.put(key, Boolean.TRUE);
        enqueuedAt.put(key, Instant.now());
    }

    /** A recipient's held messages, in arrival order. */
    Collection<EncryptedMessage> messagesFor(UUID recipientId) {
        Map<UUID, EncryptedMessage> pending = messagesByRecipient.get(recipientId);
        if (pending == null) {
            return List.of();
        }
        List<EncryptedMessage> ordered = new ArrayList<>(pending.values());
        ordered.sort(Comparator.comparing(
                m -> enqueuedAt.getOrDefault(new MessageKey(m.getMessageId(), recipientId), Instant.EPOCH)));
        return ordered;
    }

    /**
     * Atomically claim and remove a held message on behalf of its rightful recipient. Succeeds
     * only if {@code recipientId} currently holds a copy <em>and</em> the sweeper has not
     * already claimed it for flushing — guaranteeing a confirmed-in-window message never hits
     * the database.
     *
     * @return the removed message, or {@code null} if this caller does not own a held copy
     */
    EncryptedMessage claimByRecipient(UUID messageId, UUID recipientId) {
        MessageKey key = new MessageKey(messageId, recipientId);
        if (claims.remove(key) == null) {
            return null;
        }
        enqueuedAt.remove(key);
        return removeFromMessageMap(recipientId, messageId);
    }

    /** Snapshot of held copies whose hold window elapsed at or before {@code cutoff}. */
    List<MessageKey> expiredKeys(Instant cutoff) {
        List<MessageKey> expired = new ArrayList<>();
        for (Map.Entry<MessageKey, Instant> entry : enqueuedAt.entrySet()) {
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
    EncryptedMessage claimForFlush(MessageKey key) {
        if (claims.remove(key) == null) {
            enqueuedAt.remove(key);
            return null;
        }
        EncryptedMessage message = peek(key.recipientId(), key.messageId());
        if (message == null) {
            enqueuedAt.remove(key);
        }
        return message;
    }

    /** Drop a hot copy after it has been durably persisted. */
    void evict(EncryptedMessage message) {
        MessageKey key = new MessageKey(message.getMessageId(), message.getRecipientId());
        removeFromMessageMap(message.getRecipientId(), message.getMessageId());
        enqueuedAt.remove(key);
    }

    /** Release a flush claim back to the recipient after a persist failure, leaving the hot copy intact. */
    void releaseClaim(EncryptedMessage message) {
        MessageKey key = new MessageKey(message.getMessageId(), message.getRecipientId());
        claims.putIfAbsent(key, Boolean.TRUE);
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
