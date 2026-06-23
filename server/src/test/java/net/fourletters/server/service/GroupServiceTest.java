package net.fourletters.server.service;

import net.fourletters.dto.CreateGroupRequest;
import net.fourletters.dto.GroupSummary;
import net.fourletters.dto.UpdateMembersRequest;
import net.fourletters.server.model.Group;
import net.fourletters.server.model.GroupMember;
import net.fourletters.server.repository.GroupMemberRepository;
import net.fourletters.server.repository.GroupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for group lifecycle and owner-only roster administration. A group message is sent by
 * the client as one independent 1:1 copy per member, so there is no group key to test here.
 */
@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private GroupMemberRepository memberRepository;

    private GroupService service;

    private final UUID owner = UUID.randomUUID();
    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    private GroupService service() {
        if (service == null) {
            service = new GroupService(groupRepository, memberRepository);
        }
        return service;
    }

    private Group existingGroup() {
        Instant now = Instant.now();
        Group group = new Group();
        group.setId(groupId);
        group.setName("crew");
        group.setOwnerId(owner);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        return group;
    }

    private static HttpStatus statusOf(Throwable ex) {
        return (HttpStatus) ((ResponseStatusException) ex).getStatusCode();
    }

    // ---- createGroup ----------------------------------------------------------------------

    @Test
    void createGroupAddsOwnerToRoster() {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("crew");
        request.setMembers(new ArrayList<>(List.of(memberA)));
        when(memberRepository.findByGroupId(any())).thenReturn(List.of());

        net.fourletters.dto.Group dto = service().createGroup(owner, request);

        assertThat(dto.getOwnerId()).isEqualTo(owner);

        ArgumentCaptor<GroupMember> members = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepository, times(2)).save(members.capture());
        assertThat(members.getAllValues())
                .extracting(GroupMember::getUserId)
                .containsExactlyInAnyOrder(owner, memberA);
    }

    @Test
    void createGroupRejectsBlankName() {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("  ");
        request.setMembers(new ArrayList<>());

        assertThatThrownBy(() -> service().createGroup(owner, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- listGroups -----------------------------------------------------------------------

    @Test
    void listGroupsMapsMembershipsToSummaries() {
        when(memberRepository.findByUserId(memberA))
                .thenReturn(List.of(new GroupMember(groupId, memberA, Instant.now())));
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));

        List<GroupSummary> summaries = service().listGroups(memberA);

        assertThat(summaries).singleElement()
                .satisfies(s -> {
                    assertThat(s.getId()).isEqualTo(groupId);
                    assertThat(s.getOwnerId()).isEqualTo(owner);
                });
    }

    // ---- getGroup -------------------------------------------------------------------------

    @Test
    void getGroupReturnsDetailForAMember() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));
        when(memberRepository.existsByGroupIdAndUserId(groupId, memberA)).thenReturn(true);
        when(memberRepository.findByGroupId(groupId))
                .thenReturn(List.of(new GroupMember(groupId, owner, Instant.now()),
                        new GroupMember(groupId, memberA, Instant.now())));

        net.fourletters.dto.Group dto = service().getGroup(memberA, groupId);

        assertThat(dto.getMembers()).extracting(net.fourletters.dto.GroupMember::getUserId)
                .containsExactlyInAnyOrder(owner, memberA);
    }

    @Test
    void getGroupRejectsNonMember() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));
        when(memberRepository.existsByGroupIdAndUserId(groupId, memberB)).thenReturn(false);

        assertThatThrownBy(() -> service().getGroup(memberB, groupId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getGroupReportsMissingGroup() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getGroup(memberA, groupId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- updateGroupMembers ---------------------------------------------------------------

    @Test
    void updateGroupMembersAppliesRosterDiff() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));
        when(memberRepository.findUserIdsByGroupId(groupId)).thenReturn(List.of(owner, memberA));
        when(memberRepository.findByGroupId(groupId))
                .thenReturn(List.of(new GroupMember(groupId, owner, Instant.now()),
                        new GroupMember(groupId, memberB, Instant.now())));

        UpdateMembersRequest request = new UpdateMembersRequest();
        request.setRemove(List.of(memberA));
        request.setAdd(List.of(memberB));

        net.fourletters.dto.Group dto = service().updateGroupMembers(owner, groupId, request);

        assertThat(dto.getMembers()).extracting(net.fourletters.dto.GroupMember::getUserId)
                .containsExactlyInAnyOrder(owner, memberB);
        verify(memberRepository).deleteByGroupIdAndUserId(groupId, memberA);
        ArgumentCaptor<GroupMember> added = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepository).save(added.capture());
        assertThat(added.getValue().getUserId()).isEqualTo(memberB);
    }

    @Test
    void updateGroupMembersRejectsNonOwner() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));

        UpdateMembersRequest request = new UpdateMembersRequest();
        request.setAdd(List.of(memberB));

        assertThatThrownBy(() -> service().updateGroupMembers(memberA, groupId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.FORBIDDEN));
        verify(memberRepository, never()).deleteByGroupIdAndUserId(any(), any());
    }

    // ---- leaveGroup -----------------------------------------------------------------------

    @Test
    void leaveGroupRemovesAMember() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));

        service().leaveGroup(memberA, groupId);

        verify(memberRepository).deleteByGroupIdAndUserId(groupId, memberA);
    }

    @Test
    void leaveGroupForbidsTheOwner() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup()));

        assertThatThrownBy(() -> service().leaveGroup(owner, groupId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.FORBIDDEN));
        verify(memberRepository, never()).deleteByGroupIdAndUserId(any(), any());
    }
}
