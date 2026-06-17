package net.fourletters.server.repository;

import net.fourletters.server.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Cold-tier durable inbox access.
 */
@Repository
public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {

    /** A recipient's durable messages in arrival order. */
    List<InboxMessage> findByRecipientIdOrderByCreatedAtAsc(UUID recipientId);

    /**
     * Bulk delete by id — a single {@code DELETE ... WHERE} with no entity load. Unlike a
     * derived {@code deleteByMessageId} (which selects the rows first to run lifecycle
     * callbacks), this issues only the delete and is a safe no-op (returns 0) when the row
     * is absent — the common case for a receipt on a message never flushed out of the hot tier.
     *
     * @return number of rows deleted (0 or 1)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM InboxMessage m WHERE m.messageId = :messageId")
    int deleteByMessageId(@Param("messageId") UUID messageId);
}


