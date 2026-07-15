package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

/** One recipient still owing a receipt for a stored message. */
@Entity
@Table(name = "inbox_pending")
@IdClass(InboxPendingId.class)
public class InboxPending {

    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Id
    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    public InboxPending() {
    }

    public InboxPending(UUID messageId, UUID recipientId) {
        this.messageId = messageId;
        this.recipientId = recipientId;
    }

    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public UUID getRecipientId() { return recipientId; }
    public void setRecipientId(UUID recipientId) { this.recipientId = recipientId; }
}
