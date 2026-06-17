package net.fourletters.server.repository;

import net.fourletters.server.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * Idempotent bulk delete by id. Unlike {@link JpaRepository#deleteById}, this issues a
     * single {@code DELETE ... WHERE} and is a safe no-op (returns 0) when the row is absent —
     * the common case for a receipt on a message that was never flushed out of the hot tier.
     *
     * @return number of rows deleted (0 or 1)
     */
    @Transactional
    long deleteByMessageId(UUID messageId);
}


