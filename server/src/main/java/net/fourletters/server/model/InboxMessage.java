package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import net.fourletters.dto.EncryptedMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Cold-tier (durable) inbox row. A message is written here exactly once per recipient — only if
 * it is not confirmed by a delivery receipt within the in-memory hold window. Rows are deleted
 * strictly upon a verified receipt, or read back via {@code GET /inbox}.
 *
 * <p>The key is composite {@code (messageId, recipientId)} so a group message — fanned out to one
 * row per member, all sharing the client-generated {@code messageId} — has an independent durable
 * row per member. {@code groupId} and {@code epoch} are {@code null} for 1:1 messages.
 */
@Entity
@Table(name = "inbox")
@IdClass(InboxMessageId.class)
public class InboxMessage {

    /** Client-generated message id; part of the composite key. */
    @Id
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    /** Target user; part of the composite key (one row per member for group fan-out). */
    @Id
    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "sender_id")
    private UUID senderId;


    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "signature", nullable = false, columnDefinition = "TEXT")
    private String signature;

    /** Group id for a group message; {@code null} for 1:1. */
    @Column(name = "group_id")
    private UUID groupId;

    /** Group-key epoch the payload was encrypted under; {@code null} for 1:1. */
    @Column(name = "epoch")
    private Long epoch;

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
        row.groupId = message.getGroupId();
        row.epoch = message.getEpoch();
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
        message.setGroupId(groupId);
        message.setEpoch(epoch);
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

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public Long getEpoch() { return epoch; }
    public void setEpoch(Long epoch) { this.epoch = epoch; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

