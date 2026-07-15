package net.fourletters.server.service;

import net.fourletters.dto.EncryptedMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the unified hot tier. Pure in-memory — no Spring, no database. A 1:1 message is a
 * pending set of one; a group message is a pending set of the roster minus the sender.
 */
class HotTierStorageTest {

    private final HotTierStorage tier = new HotTierStorage();
    private final UUID sender = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    /** A cold sink that records each flushed payload and pending set. */
    private static final class RecordingSink implements HotTierStorage.ColdSink {
        final List<EncryptedMessage> persisted = new ArrayList<>();
        final List<Set<UUID>> pending = new ArrayList<>();

        @Override
        public void persist(EncryptedMessage message, Set<UUID> members) {
            persisted.add(message);
            pending.add(Set.copyOf(members));
        }
    }

    private EncryptedMessage directMessage(UUID messageId) {
        EncryptedMessage m = new EncryptedMessage();
        m.setMessageId(messageId);
        m.setSenderId(sender);
        m.setRecipientId(alice);
        m.setPayload("cipher");
        return m;
    }

    private EncryptedMessage groupMessage(UUID messageId) {
        EncryptedMessage m = new EncryptedMessage();
        m.setMessageId(messageId);
        m.setSenderId(sender);
        m.setGroupId(groupId);
        m.setPayload("cipher");
        return m;
    }

    private static Instant future() {
        return Instant.now().plusSeconds(60);
    }

    @Test
    void pendingForStampsRecipientAndKeepsArrivalOrder() {
        EncryptedMessage first = groupMessage(UUID.randomUUID());
        EncryptedMessage second = groupMessage(UUID.randomUUID());
        tier.store(first, Set.of(alice, bob));
        tier.store(second, Set.of(alice));

        List<EncryptedMessage> forAlice = new ArrayList<>(tier.pendingFor(alice));

        assertThat(forAlice).extracting(EncryptedMessage::getMessageId)
                .containsExactly(first.getMessageId(), second.getMessageId());
        assertThat(forAlice).allSatisfy(m -> assertThat(m.getRecipientId()).isEqualTo(alice));
    }

    @Test
    void pendingForExcludesMembersWhoAlreadyAcknowledged() {
        EncryptedMessage m = groupMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice, bob));

        tier.acknowledge(m.getMessageId(), alice);

        assertThat(tier.pendingFor(alice)).isEmpty();
        assertThat(tier.pendingFor(bob)).extracting(EncryptedMessage::getMessageId)
                .containsExactly(m.getMessageId());
    }

    @Test
    void acknowledgeDirectCopyReturnsSenderAndDropsIt() {
        EncryptedMessage m = directMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice));

        HotTierStorage.Ack ack = tier.acknowledge(m.getMessageId(), alice);

        assertThat(ack.handled()).isTrue();
        assertThat(ack.senderId()).isEqualTo(sender);
        // A pending set of one is now empty: the payload is dropped.
        assertThat(tier.holds(m.getMessageId())).isFalse();
        assertThat(tier.pendingFor(alice)).isEmpty();
    }

    @Test
    void acknowledgeReturnsSenderAndDropsPayloadOnceEmpty() {
        EncryptedMessage m = groupMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice, bob));

        HotTierStorage.Ack first = tier.acknowledge(m.getMessageId(), alice);
        assertThat(first.handled()).isTrue();
        assertThat(first.senderId()).isEqualTo(sender);
        // Still held while bob is pending.
        assertThat(tier.holds(m.getMessageId())).isTrue();

        HotTierStorage.Ack last = tier.acknowledge(m.getMessageId(), bob);
        assertThat(last.handled()).isTrue();
        assertThat(last.senderId()).isEqualTo(sender);
        // Last member gone: the shared payload is dropped.
        assertThat(tier.holds(m.getMessageId())).isFalse();
    }

    @Test
    void duplicateAcknowledgeForSameMemberIsIdempotent() {
        EncryptedMessage m = groupMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice, bob));
        tier.acknowledge(m.getMessageId(), alice);

        HotTierStorage.Ack again = tier.acknowledge(m.getMessageId(), alice);

        assertThat(again.handled()).isTrue();
        assertThat(again.senderId()).isEqualTo(sender);
        // Bob still owes a receipt, so the payload survives.
        assertThat(tier.holds(m.getMessageId())).isTrue();
    }

    @Test
    void acknowledgeUnknownMessageIsAbsent() {
        HotTierStorage.Ack ack = tier.acknowledge(UUID.randomUUID(), alice);

        assertThat(ack).isEqualTo(HotTierStorage.Ack.ABSENT);
        assertThat(ack.handled()).isFalse();
    }

    @Test
    void duplicateDirectAcknowledgeIsSettledWithNoSender() {
        EncryptedMessage m = directMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice));
        tier.acknowledge(m.getMessageId(), alice);

        // The 1:1 entry drained, leaving a tombstone; a duplicate resolves without cold access.
        HotTierStorage.Ack ack = tier.acknowledge(m.getMessageId(), alice);

        assertThat(ack).isEqualTo(HotTierStorage.Ack.SETTLED);
        assertThat(ack.handled()).isTrue();
        assertThat(ack.senderId()).isNull();
    }

    @Test
    void drainedGroupLeavesNoTombstoneSoDuplicateIsAbsent() {
        EncryptedMessage m = groupMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice));
        tier.acknowledge(m.getMessageId(), alice);

        // A group message never tombstones; a duplicate after full drain resolves against cold.
        assertThat(tier.acknowledge(m.getMessageId(), alice)).isEqualTo(HotTierStorage.Ack.ABSENT);
    }

    @Test
    void markSettledMakesLaterAcknowledgeShortCircuit() {
        UUID messageId = UUID.randomUUID();
        // Simulates the cold path having cleared a flushed 1:1 message's durable row.
        tier.markSettled(messageId);

        assertThat(tier.acknowledge(messageId, alice)).isEqualTo(HotTierStorage.Ack.SETTLED);
    }

    @Test
    void sweepExpiresSettledTombstones() {
        EncryptedMessage m = directMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice));
        tier.acknowledge(m.getMessageId(), alice);

        tier.sweep(future());

        // Tombstone gone: a later receipt is now merely absent (resolved against the cold tier).
        assertThat(tier.acknowledge(m.getMessageId(), alice)).isEqualTo(HotTierStorage.Ack.ABSENT);
    }

    @Test
    void flushExpiredPersistsPayloadWithPendingMembersThenEvicts() {
        EncryptedMessage m = groupMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice, bob));
        RecordingSink sink = new RecordingSink();

        tier.flushExpired(future(), sink);

        assertThat(sink.persisted).extracting(EncryptedMessage::getMessageId).containsExactly(m.getMessageId());
        assertThat(sink.pending).containsExactly(Set.of(alice, bob));
        assertThat(tier.holds(m.getMessageId())).isFalse();
    }

    @Test
    void flushExpiredSkipsPersistWhenEveryMemberAcknowledged() {
        EncryptedMessage m = directMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice));
        // The sole member acknowledged within the window, so the payload was already dropped and
        // there is nothing left for the sweeper to persist.
        tier.acknowledge(m.getMessageId(), alice);
        RecordingSink sink = new RecordingSink();

        tier.flushExpired(future(), sink);

        assertThat(sink.persisted).isEmpty();
        assertThat(tier.holds(m.getMessageId())).isFalse();
    }

    @Test
    void flushFailureReleasesClaimAndKeepsHotCopy() {
        EncryptedMessage m = groupMessage(UUID.randomUUID());
        tier.store(m, Set.of(alice, bob));
        HotTierStorage.ColdSink failing = (message, members) -> {
            throw new IllegalStateException("db down");
        };

        tier.flushExpired(future(), failing);

        // The entry survives and can still be acknowledged.
        assertThat(tier.holds(m.getMessageId())).isTrue();
        HotTierStorage.Ack ack = tier.acknowledge(m.getMessageId(), alice);
        assertThat(ack.handled()).isTrue();
        assertThat(ack.senderId()).isEqualTo(sender);
    }
}
