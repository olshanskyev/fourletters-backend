package net.fourletters.server.repository;

import java.util.List;
import java.util.UUID;
import net.fourletters.server.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Cold-tier durable inbox: one row per message (single-copy). Recipients still owed a receipt live
 * in {@link InboxPendingRepository}.
 */
@Repository
public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {

    /** The messages a recipient has not yet acknowledged, in arrival order. */
    @Query("SELECT m FROM InboxMessage m WHERE m.messageId IN "
            + "(SELECT p.messageId FROM InboxPending p WHERE p.recipientId = :recipientId) "
            + "ORDER BY m.createdAt ASC")
    List<InboxMessage> findPendingForRecipient(@Param("recipientId") UUID recipientId);
}


