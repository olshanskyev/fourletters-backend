package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
/** Roster row: one user's membership in one group. Key is composite {@code (groupId, userId)}. */
@Entity
@Table(name = "group_members")
@IdClass(GroupMemberId.class)
public class GroupMember {

    @Id
    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public GroupMember() {
    }

    public GroupMember(UUID groupId, UUID userId, Instant joinedAt) {
        this.groupId = groupId;
        this.userId = userId;
        this.joinedAt = joinedAt;
    }

    /** The wire view of this roster row. */
    public net.fourletters.dto.GroupMember toDto() {
        net.fourletters.dto.GroupMember member = new net.fourletters.dto.GroupMember();
        member.setUserId(userId);
        member.setJoinedAt(joinedAt.toEpochMilli());
        return member;
    }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
}
