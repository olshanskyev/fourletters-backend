package net.fourletters.server.repository;

import java.util.List;
import java.util.UUID;
import net.fourletters.server.model.InboxMessage;
import net.fourletters.server.model.InboxMessageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cold-tier durable inbox access. The key is composite {@code (messageId, recipientId)} because a
 * group message is fanned out to one durable row per member, all sharing the client-generated
 * {@code messageId}.
 */
@Repository
public interface InboxMessageRepository extends JpaRepository<InboxMessage, InboxMessageId> {

    /** A recipient's durable messages in arrival order. */
    List<InboxMessage> findByRecipientIdOrderByCreatedAtAsc(UUID recipientId);

    /**
     * Bulk delete a single recipient's copy of a message — a single {@code DELETE ... WHERE} with
     * no entity load. Scoped by {@code (messageId, recipientId)} so a receipt only removes the
     * acknowledging member's copy of a group message, never another member's. A safe no-op
     * (returns 0) when the row is absent — the common case for a receipt on a message never
     * flushed out of the hot tier.
     *
     * @return number of rows deleted (0 or 1)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM InboxMessage m WHERE m.messageId = :messageId AND m.recipientId = :recipientId")
    int deleteByMessageIdAndRecipientId(@Param("messageId") UUID messageId,
                                        @Param("recipientId") UUID recipientId);
}


