package net.fourletters.server.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for {@link GroupKey}: {@code (groupId, epoch, recipientId)}. */
public class GroupKeyId implements Serializable {

    private UUID groupId;
    private long epoch;
    private UUID recipientId;

    public GroupKeyId() {
    }

    public GroupKeyId(UUID groupId, long epoch, UUID recipientId) {
        this.groupId = groupId;
        this.epoch = epoch;
        this.recipientId = recipientId;
    }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }

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
        GroupKeyId that = (GroupKeyId) o;
        return epoch == that.epoch
                && Objects.equals(groupId, that.groupId)
                && Objects.equals(recipientId, that.recipientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, epoch, recipientId);
    }
}
