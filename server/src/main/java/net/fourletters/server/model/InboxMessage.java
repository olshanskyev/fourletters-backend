package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.fourletters.dto.EncryptedMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Cold-tier (durable) inbox row. A message is written here exactly once — only if it is
 * not confirmed by a signed delivery receipt within the in-memory hold window. Rows are
 * deleted strictly upon a verified signed receipt, or read back via {@code GET /inbox}.
 */
@Entity
@Table(name = "inbox")
public class InboxMessage {

    /** Client-generated message id; primary key for random access and idempotent deletes. */
    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "sender_id")
    private UUID senderId;


    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "signature", nullable = false, columnDefinition = "TEXT")
    private String signature;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InboxMessage() {
    }

    /** Build a durable row from the in-memory DTO at flush time. */
    public static InboxMessage from(EncryptedMessage message) {
        InboxMessage row = new InboxMessage();
        row.messageId = message.getMessageId();
        row.recipientId = message.getRecipientId();
        row.senderId = message.getSenderId();
        row.payload = message.getPayload();
        row.signature = message.getSignature();
        row.createdAt = Instant.now();
        return row;
    }

    /** Reconstruct the wire DTO for an {@code /inbox} read. */
    public EncryptedMessage toDto() {
        EncryptedMessage message = new EncryptedMessage();
        message.setMessageId(messageId);
        message.setRecipientId(recipientId);
        message.setSenderId(senderId);
        message.setPayload(payload);
        message.setSignature(signature);
        return message;
    }

    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public UUID getRecipientId() { return recipientId; }
    public void setRecipientId(UUID recipientId) { this.recipientId = recipientId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }


    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

