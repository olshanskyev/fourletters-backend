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
 * A messaging group. Metadata is intentionally minimal: a plaintext name, the owner (sole roster
 * admin), and the current key {@code epoch}. Membership lives in {@link GroupMember} and wrapped
 * per-epoch keys in {@link GroupKey}.
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

    /** Current sender-key epoch; bumped on every membership change and explicit rotation. */
    @Column(name = "epoch", nullable = false)
    private long epoch;

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
        dto.setEpoch(epoch);
        dto.setMembers(members);
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

    public long getEpoch() { return epoch; }
    public void setEpoch(long epoch) { this.epoch = epoch; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
