package net.fourletters.server.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key for {@link InboxMessage}: {@code (messageId, recipientId)}.
 *
 * <p>A 1:1 message has a single recipient row; a group message is fanned out to one row per
 * member, all sharing the client-generated {@code messageId}. The recipient therefore must be
 * part of the key so each member's copy is an independent durable row.
 */
public class InboxMessageId implements Serializable {

    private UUID messageId;
    private UUID recipientId;

    public InboxMessageId() {
    }

    public InboxMessageId(UUID messageId, UUID recipientId) {
        this.messageId = messageId;
        this.recipientId = recipientId;
    }

    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }

    public UUID getRecipientId() { return recipientId; }
    public void setRecipientId(UUID recipientId) { this.recipientId = recipientId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InboxMessageId that = (InboxMessageId) o;
        return Objects.equals(messageId, that.messageId) && Objects.equals(recipientId, that.recipientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, recipientId);
    }
}
