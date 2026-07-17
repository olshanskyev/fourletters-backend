package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A messaging group. Metadata is intentionally minimal: a plaintext name and the owner (sole
 * roster admin). Membership lives in {@link GroupMember}. A group message is sent by the client as
 * one independent 1:1 copy per member, so the Server holds no group key.
 */
@Entity
@Table(name = "groups")
public class Group {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Group avatar image as a base64 data URL, stored plaintext. Null when unset. */
    @Column(name = "avatar_url")
    private String avatarUrl;

    /**
     * Server-authoritative Sender-Key epoch. Incremented only when a member is removed, which
     * invalidates every distributed Sender Key.
     */
    @Column(name = "epoch", nullable = false)
    private int epoch = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Group() {
    }

    /** Lightweight wire view (no roster), for group listings. */
    public net.fourletters.dto.GroupSummary toSummary() {
        net.fourletters.dto.GroupSummary summary = new net.fourletters.dto.GroupSummary();
        summary.setId(id);
        summary.setName(name);
        summary.setOwnerId(ownerId);
        summary.setEpoch(epoch);
        return summary;
    }

    /**
     * Full wire view including the roster.
     */
    public net.fourletters.dto.Group toDto(List<GroupMember> memberRows, Instant updatedAtOverride) {
        List<net.fourletters.dto.GroupMember> members = new ArrayList<>(memberRows.size());
        for (GroupMember row : memberRows) {
            members.add(row.toDto());
        }
        Instant effectiveUpdatedAt = updatedAtOverride != null ? updatedAtOverride : updatedAt;

        net.fourletters.dto.Group dto = new net.fourletters.dto.Group();
        dto.setId(id);
        dto.setName(name);
        dto.setOwnerId(ownerId);
        dto.setMembers(members);
        dto.setAvatarUrl(avatarUrl);
        dto.setEpoch(epoch);
        dto.setCreatedAt(createdAt.toEpochMilli());
        dto.setUpdatedAt(effectiveUpdatedAt.toEpochMilli());
        return dto;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public int getEpoch() { return epoch; }
    public void setEpoch(int epoch) { this.epoch = epoch; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
