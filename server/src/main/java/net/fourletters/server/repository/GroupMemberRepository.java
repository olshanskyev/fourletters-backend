package net.fourletters.server.repository;

import java.util.List;
import java.util.UUID;
import net.fourletters.server.model.GroupMember;
import net.fourletters.server.model.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    List<GroupMember> findByGroupId(UUID groupId);

    List<GroupMember> findByUserId(UUID userId);

    /** The current roster as plain user ids. */
    @Query("SELECT m.userId FROM GroupMember m WHERE m.groupId = :groupId")
    List<UUID> findUserIdsByGroupId(@Param("groupId") UUID groupId);

    @Modifying
    @Query("DELETE FROM GroupMember m WHERE m.groupId = :groupId AND m.userId = :userId")
    int deleteByGroupIdAndUserId(@Param("groupId") UUID groupId, @Param("userId") UUID userId);
}
