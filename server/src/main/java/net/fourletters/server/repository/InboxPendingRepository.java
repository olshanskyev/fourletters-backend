package net.fourletters.server.repository;

import java.util.UUID;
import net.fourletters.server.model.InboxPending;
import net.fourletters.server.model.InboxPendingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-recipient pending set for stored messages. A row is removed on that recipient's first receipt;
 * when a message has no pending rows left, a database trigger drops its parent {@code inbox} payload.
 */
@Repository
public interface InboxPendingRepository extends JpaRepository<InboxPending, InboxPendingId> {

    /** Drop a single recipient's pending row for a message. */
    @Modifying
    @Transactional
    @Query("DELETE FROM InboxPending p WHERE p.messageId = :messageId AND p.recipientId = :recipientId")
    int deleteByMessageIdAndRecipientId(@Param("messageId") UUID messageId,
                                        @Param("recipientId") UUID recipientId);
}
