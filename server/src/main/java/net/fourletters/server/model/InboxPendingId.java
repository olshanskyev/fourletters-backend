package net.fourletters.server.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class InboxPendingId implements Serializable {

    private UUID messageId;
    private UUID recipientId;

    public InboxPendingId() {
    }

    public InboxPendingId(UUID messageId, UUID recipientId) {
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
        InboxPendingId that = (InboxPendingId) o;
        return Objects.equals(messageId, that.messageId) && Objects.equals(recipientId, that.recipientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, recipientId);
    }
}
