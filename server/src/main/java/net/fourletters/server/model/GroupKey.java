package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import net.fourletters.dto.GroupKeySet;
import java.time.Instant;
import java.util.UUID;

/**
 * One member's copy of a group sender-key for a given epoch, sealed to that member's encryption
 * public key by the owner. The server stores only the opaque {@code wrappedKey} blob and never
 * sees plaintext key material. Rows are the offline-delivery backstop: they are kept until the
 * member pulls them (then marked {@code delivered}) and survive across rotations so a member that
 * was offline can still catch up on any missed epoch.
 *
 * <p>Key is composite {@code (groupId, epoch, recipientId)}.
 */
@Entity
@Table(name = "group_keys")
@IdClass(GroupKeyId.class)
public class GroupKey {

    @Id
    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Id
    @Column(name = "epoch", nullable = false)
    private long epoch;

    @Id
    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "wrapped_key", nullable = false, columnDefinition = "TEXT")
    private String wrappedKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Set once the member has pulled this key via {@code /inbox} or {@code GET /groups/{id}/keys}. */
    @Column(name = "delivered", nullable = false)
    private boolean delivered;

    public GroupKey() {
    }

    public GroupKey(UUID groupId, long epoch, UUID recipientId, String wrappedKey, Instant createdAt) {
        this.groupId = groupId;
        this.epoch = epoch;
        this.recipientId = recipientId;
        this.wrappedKey = wrappedKey;
        this.createdAt = createdAt;
        this.delivered = false;
    }

    /** The wire view of this wrapped key (group, epoch, blob) for the recipient. */
    public GroupKeySet toKeySet() {
        GroupKeySet set = new GroupKeySet();
        set.setGroupId(groupId);
        set.setEpoch(epoch);
        set.setWrappedKey(wrappedKey);
        return set;
    }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }

    public UUID getRecipientId() { return recipientId; }
    public void setRecipientId(UUID recipientId) { this.recipientId = recipientId; }

    public String getWrappedKey() { return wrappedKey; }
    public void setWrappedKey(String wrappedKey) { this.wrappedKey = wrappedKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
}
