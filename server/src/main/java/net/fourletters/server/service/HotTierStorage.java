package net.fourletters.server.service;

import net.fourletters.dto.EncryptedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory hot tier of the inbox: holds accepted messages during the hold window so quickly
 * confirmed ones never reach the cold tier. Every message is stored once with the set of recipients
 * still owing a receipt (1:1 = a set of one, group = roster minus sender); each receipt removes one
 * member and the payload is dropped once the last is gone. The pending set is the idempotency marker
 * for group messages; a 1:1 message additionally leaves a short-lived {@code settled} tombstone so a
 * duplicate receipt (after its sole recipient acknowledged) resolves without a cold-tier read.
 */
class HotTierStorage {

    private static final Logger logger = LoggerFactory.getLogger(HotTierStorage.class);

    /** Cold-tier projection driven at flush time: one payload plus one row per pending recipient. */
    @FunctionalInterface
    interface ColdSink {
        void persist(EncryptedMessage message, Set<UUID> pending);
    }

    /**
     * Outcome of {@link #acknowledge}: {@code handled} means the hot tier owned the receipt (a live
     * copy dropped) so the cold tier is skipped; otherwise {@code ABSENT}.
     */
    record Ack(boolean handled, UUID senderId) {

        /** Not held here — consult the cold tier. */
        static final Ack ABSENT = new Ack(false, null);

        /** Already acknowledged here — handled, no relay target. */
        static final Ack SETTLED = new Ack(true, null);

        /** A live copy was dropped; relay to {@code senderId}. */
        static Ack held(UUID senderId) {
            return new Ack(true, senderId);
        }
    }

    /** One stored message plus its still-pending recipients. */
    private static final class Entry {
        final EncryptedMessage message;
        final Set<UUID> pending;
        final Instant enqueuedAt;
        final long sequenceId;

        Entry(EncryptedMessage message, Set<UUID> pending, long sequenceId) {
            this.message = message;
            this.pending = pending;
            this.enqueuedAt = Instant.now();
            this.sequenceId = sequenceId;
        }
    }

    /** messageId -> the single held message. */
    private final Map<UUID, Entry> messages = new ConcurrentHashMap<>();
    /** messageId -> claim token, the atomic hand-off between a receipt and the sweeper. */
    private final Map<UUID, Boolean> claims = new ConcurrentHashMap<>();
    /** Tombstone of drained 1:1 messages, so a later duplicate receipt need not touch cold. */
    private final Map<UUID, Instant> settled = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    /** Retain {@code message}, owed to {@code recipients}. */
    void store(EncryptedMessage message, Set<UUID> recipients) {
        UUID messageId = message.getMessageId();
        messages.put(messageId, new Entry(message, new CopyOnWriteArraySet<>(recipients), sequence.getAndIncrement()));
        claims.put(messageId, Boolean.TRUE);
    }

    /** Whether this tier currently holds the message. */
    boolean holds(UUID messageId) {
        return messages.containsKey(messageId);
    }

    /** Messages still owed to {@code recipientId}, in arrival order, stamped for that recipient. */
    Collection<EncryptedMessage> pendingFor(UUID recipientId) {
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : messages.values()) {
            if (entry.pending.contains(recipientId)) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparingLong(e -> e.sequenceId));
        List<EncryptedMessage> ordered = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            // Stamp this recipient so the client threads it correctly.
            ordered.add(copyFor(entry.message, recipientId));
        }
        return ordered;
    }

    /** Acknowledge delivery to one recipient; the {@link Ack} says whether this tier handled it. */
    Ack acknowledge(UUID messageId, UUID recipientId) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            // Flushed or drained: a 1:1 tombstone short-circuits a duplicate; else consult cold.
            return settled.containsKey(messageId) ? Ack.SETTLED : Ack.ABSENT;
        }
        // Remove this member (a no-op for an absent one, so duplicates are idempotent); drop the
        // entry once none remain.
        entry.pending.remove(recipientId);
        if (entry.pending.isEmpty() && claims.remove(messageId) != null) {
            messages.remove(messageId);
            // A 1:1 message (no group) leaves a tombstone so a duplicate receipt need not hit cold.
            if (entry.message.getGroupId() == null) {
                markSettled(messageId);
            }
        }
        return Ack.held(entry.message.getSenderId());
    }

    /** Tombstone a 1:1 message the caller cleared from cold, so a duplicate receipt short-circuits. */
    void markSettled(UUID messageId) {
        settled.put(messageId, Instant.now());
    }

    /** Expire settle tombstones older than {@code cutoff}. */
    void sweep(Instant cutoff) {
        settled.entrySet().removeIf(e -> !e.getValue().isAfter(cutoff));
    }

    /** Flush messages past {@code cutoff} via {@code sink}, then evict them. */
    void flushExpired(Instant cutoff, ColdSink sink) {
        for (UUID messageId : expiredKeys(cutoff)) {
            Entry entry = claimForFlush(messageId);
            if (entry == null) {
                continue;
            }
            if (entry.pending.isEmpty()) {
                // Every recipient acknowledged in-window: nothing to persist.
                evict(messageId);
                continue;
            }
            try {
                // Persist-then-evict.
                sink.persist(entry.message, entry.pending);
                evict(messageId);
                logger.debug("Flushed message {} ({} pending) to durable inbox",
                        messageId, entry.pending.size());
            } catch (RuntimeException e) {
                releaseClaim(messageId);
                logger.warn("Failed to flush message {} to durable inbox; will retry", messageId, e);
            }
        }
    }

    /** Messages whose hold window elapsed at or before {@code cutoff}. */
    private List<UUID> expiredKeys(Instant cutoff) {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : messages.entrySet()) {
            if (!e.getValue().enqueuedAt.isAfter(cutoff)) {
                expired.add(e.getKey());
            }
        }
        return expired;
    }

    /** Claim a held message for the sweeper, or {@code null} if a receipt already took it. */
    private Entry claimForFlush(UUID messageId) {
        if (claims.remove(messageId) == null) {
            return null;
        }
        return messages.get(messageId);
    }

    /** Drop a hot copy after it has been persisted. */
    private void evict(UUID messageId) {
        messages.remove(messageId);
    }

    /** Release a flush claim after a persist failure, leaving the hot copy intact. */
    private void releaseClaim(UUID messageId) {
        claims.putIfAbsent(messageId, Boolean.TRUE);
    }

    private static EncryptedMessage copyFor(EncryptedMessage source, UUID recipientId) {
        EncryptedMessage copy = new EncryptedMessage();
        copy.setMessageId(source.getMessageId());
        copy.setSenderId(source.getSenderId());
        copy.setGroupId(source.getGroupId());
        copy.setPayload(source.getPayload());
        copy.setRecipientId(recipientId);
        return copy;
    }
}
