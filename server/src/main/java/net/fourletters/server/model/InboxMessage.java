package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.fourletters.dto.EncryptedMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Cold-tier (durable) inbox row: one payload per message, written only if a message is not confirmed
 * within the in-memory hold window. Recipients still owed a receipt live in {@code inbox_pending};
 * {@code groupId} is {@code null} for 1:1 messages.
 */
@Entity
@Table(name = "inbox")
public class InboxMessage {

    /** Client-generated message id; the primary key (one row per message, not per recipient). */
    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "sender_id")
    private UUID senderId;


    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Group id for a group message; {@code null} for 1:1. */
    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public InboxMessage() {
    }

    /** Build a durable row from the in-memory DTO at flush time. */
    public static InboxMessage from(EncryptedMessage message) {
        InboxMessage row = new InboxMessage();
        row.messageId = message.getMessageId();
        row.senderId = message.getSenderId();
        row.payload = message.getPayload();
        row.groupId = message.getGroupId();
        row.createdAt = Instant.now();
        return row;
    }

    /** Reconstruct the wire DTO for an {@code /inbox} read (recipientId is set by the caller). */
    public EncryptedMessage toDto() {
        EncryptedMessage message = new EncryptedMessage();
        message.setMessageId(messageId);
        message.setSenderId(senderId);
        message.setPayload(payload);
        message.setGroupId(groupId);
        return message;
    }

    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }


    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

