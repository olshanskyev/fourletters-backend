package net.fourletters.server.service;

import net.fourletters.dto.CreateGroupRequest;
import net.fourletters.dto.GroupKeyDistribution;
import net.fourletters.dto.GroupKeyEvent;
import net.fourletters.dto.GroupKeySet;
import net.fourletters.dto.GroupSummary;
import net.fourletters.dto.UpdateMembersRequest;
import net.fourletters.dto.WrappedGroupKey;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.Group;
import net.fourletters.server.model.GroupKey;
import net.fourletters.server.model.GroupMember;
import net.fourletters.server.repository.GroupKeyRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for group lifecycle, owner-only roster administration, any-member key rotation,
 * the epoch compare-and-set guard, and wrapped-key distribution.
 */
@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private GroupRepository groupRepository;
    @Mock
    private GroupMemberRepository memberRepository;
    @Mock
    private GroupKeyRepository keyRepository;
    @Mock
    private ServerRabbitMqService rabbitMqService;

    private GroupService service;

    private final UUID owner = UUID.randomUUID();
    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    private GroupService service() {
        if (service == null) {
            service = new GroupService(groupRepository, memberRepository, keyRepository, rabbitMqService);
        }
        return service;
    }

    private static WrappedGroupKey wrapped(UUID recipient) {
        WrappedGroupKey key = new WrappedGroupKey();
        key.setRecipientId(recipient);
        key.setWrappedKey("blob-" + recipient);
        return key;
    }

    private Group existingGroup(long epoch) {
        Instant now = Instant.now();
        Group group = new Group();
        group.setId(groupId);
        group.setName("crew");
        group.setOwnerId(owner);
        group.setEpoch(epoch);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        return group;
    }

    private static HttpStatus statusOf(Throwable ex) {
        return (HttpStatus) ((ResponseStatusException) ex).getStatusCode();
    }

    // ---- createGroup ----------------------------------------------------------------------

    @Test
    void createGroupAddsOwnerToRosterStoresKeysAndNudgesEveryoneButOwner() {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("crew");
        request.setMembers(new ArrayList<>(List.of(memberA)));
        request.setKeys(List.of(wrapped(owner), wrapped(memberA)));
        when(memberRepository.findByGroupId(any())).thenReturn(List.of());

        net.fourletters.dto.Group dto = service().createGroup(owner, request);

        assertThat(dto.getOwnerId()).isEqualTo(owner);
        assertThat(dto.getEpoch()).isZero();

        ArgumentCaptor<GroupMember> members = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepository, times(2)).save(members.capture());
        assertThat(members.getAllValues())
                .extracting(GroupMember::getUserId)
                .containsExactlyInAnyOrder(owner, memberA);

        verify(keyRepository, times(2)).save(any(GroupKey.class));
        verify(rabbitMqService, times(1)).publishGroupKeyEvent(eq(memberA), any(GroupKeyEvent.class));
        verify(rabbitMqService, never()).publishGroupKeyEvent(eq(owner), any(GroupKeyEvent.class));
    }

    @Test
    void createGroupRejectsBlankName() {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("  ");
        request.setMembers(new ArrayList<>());
        request.setKeys(List.of());

        assertThatThrownBy(() -> service().createGroup(owner, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createGroupRejectsKeysetThatMissesARosterMember() {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("crew");
        request.setMembers(new ArrayList<>(List.of(memberA)));
        request.setKeys(List.of(wrapped(owner))); // missing memberA

        assertThatThrownBy(() -> service().createGroup(owner, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(groupRepository, never()).save(any());
    }

    // ---- listGroups -----------------------------------------------------------------------

    @Test
    void listGroupsMapsMembershipsToSummaries() {
        when(memberRepository.findByUserId(memberA))
                .thenReturn(List.of(new GroupMember(groupId, memberA, Instant.now())));
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(2)));

        List<GroupSummary> summaries = service().listGroups(memberA);

        assertThat(summaries).singleElement()
                .satisfies(s -> {
                    assertThat(s.getId()).isEqualTo(groupId);
                    assertThat(s.getOwnerId()).isEqualTo(owner);
                    assertThat(s.getEpoch()).isEqualTo(2L);
                });
    }

    // ---- getGroup -------------------------------------------------------------------------

    @Test
    void getGroupReturnsDetailForAMember() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));
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
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));
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
    void updateGroupMembersAppliesRosterDiffWhenOwnerWinsTheCas() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));
        when(memberRepository.findUserIdsByGroupId(groupId)).thenReturn(List.of(owner, memberA));
        when(groupRepository.compareAndSetEpoch(eq(groupId), eq(0L), eq(1L), any())).thenReturn(1);
        when(memberRepository.findByGroupId(groupId))
                .thenReturn(List.of(new GroupMember(groupId, owner, Instant.now()),
                        new GroupMember(groupId, memberB, Instant.now())));

        UpdateMembersRequest request = new UpdateMembersRequest();
        request.setEpoch(1L);
        request.setRemove(List.of(memberA));
        request.setAdd(List.of(memberB));
        request.setKeys(List.of(wrapped(owner), wrapped(memberB)));

        net.fourletters.dto.Group dto = service().updateGroupMembers(owner, groupId, request);

        assertThat(dto.getEpoch()).isEqualTo(1L);
        verify(memberRepository).deleteByGroupIdAndUserId(groupId, memberA);
        ArgumentCaptor<GroupMember> added = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepository).save(added.capture());
        assertThat(added.getValue().getUserId()).isEqualTo(memberB);
        verify(keyRepository, times(2)).save(any(GroupKey.class));
        verify(rabbitMqService).publishGroupKeyEvent(eq(memberB), any(GroupKeyEvent.class));
        verify(rabbitMqService, never()).publishGroupKeyEvent(eq(owner), any(GroupKeyEvent.class));
    }

    @Test
    void updateGroupMembersRejectsNonOwner() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));

        UpdateMembersRequest request = new UpdateMembersRequest();
        request.setEpoch(1L);
        request.setKeys(List.of(wrapped(owner)));

        assertThatThrownBy(() -> service().updateGroupMembers(memberA, groupId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void updateGroupMembersRejectsStaleEpoch() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(5)));

        UpdateMembersRequest request = new UpdateMembersRequest();
        request.setEpoch(5L); // must be 6
        request.setKeys(List.of(wrapped(owner)));

        assertThatThrownBy(() -> service().updateGroupMembers(owner, groupId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void updateGroupMembersRejectsWhenAnotherWriterWonTheCas() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));
        when(memberRepository.findUserIdsByGroupId(groupId)).thenReturn(List.of(owner, memberA));
        when(groupRepository.compareAndSetEpoch(eq(groupId), eq(0L), eq(1L), any())).thenReturn(0);

        UpdateMembersRequest request = new UpdateMembersRequest();
        request.setEpoch(1L);
        request.setRemove(List.of(memberA));
        request.setKeys(List.of(wrapped(owner)));

        assertThatThrownBy(() -> service().updateGroupMembers(owner, groupId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.CONFLICT));
        verify(memberRepository, never()).deleteByGroupIdAndUserId(any(), any());
        verify(keyRepository, never()).save(any());
    }

    // ---- rotateGroupKey -------------------------------------------------------------------

    @Test
    void rotateGroupKeyLetsAnyMemberRotateAndNudgesEveryoneButTheCaller() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));
        when(memberRepository.existsByGroupIdAndUserId(groupId, memberA)).thenReturn(true);
        when(memberRepository.findUserIdsByGroupId(groupId)).thenReturn(List.of(owner, memberA));
        when(groupRepository.compareAndSetEpoch(eq(groupId), eq(0L), eq(1L), any())).thenReturn(1);
        when(memberRepository.findByGroupId(groupId))
                .thenReturn(List.of(new GroupMember(groupId, owner, Instant.now()),
                        new GroupMember(groupId, memberA, Instant.now())));

        GroupKeyDistribution distribution = new GroupKeyDistribution();
        distribution.setEpoch(1L);
        distribution.setKeys(List.of(wrapped(owner), wrapped(memberA)));

        net.fourletters.dto.Group dto = service().rotateGroupKey(memberA, groupId, distribution);

        assertThat(dto.getEpoch()).isEqualTo(1L);
        verify(keyRepository, times(2)).save(any(GroupKey.class));
        verify(rabbitMqService).publishGroupKeyEvent(eq(owner), any(GroupKeyEvent.class));
        verify(rabbitMqService, never()).publishGroupKeyEvent(eq(memberA), any(GroupKeyEvent.class));
    }

    @Test
    void rotateGroupKeyRejectsNonMember() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));
        when(memberRepository.existsByGroupIdAndUserId(groupId, memberB)).thenReturn(false);

        GroupKeyDistribution distribution = new GroupKeyDistribution();
        distribution.setEpoch(1L);
        distribution.setKeys(List.of(wrapped(owner)));

        assertThatThrownBy(() -> service().rotateGroupKey(memberB, groupId, distribution))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- leaveGroup -----------------------------------------------------------------------

    @Test
    void leaveGroupRemovesAMember() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));

        service().leaveGroup(memberA, groupId);

        verify(memberRepository).deleteByGroupIdAndUserId(groupId, memberA);
    }

    @Test
    void leaveGroupForbidsTheOwner() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(0)));

        assertThatThrownBy(() -> service().leaveGroup(owner, groupId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.FORBIDDEN));
        verify(memberRepository, never()).deleteByGroupIdAndUserId(any(), any());
    }

    // ---- getGroupKey ----------------------------------------------------------------------

    @Test
    void getGroupKeyDefaultsToCurrentEpochAndMarksDelivered() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(5)));
        GroupKey key = new GroupKey(groupId, 5L, memberA, "blob", Instant.now());
        when(keyRepository.findByGroupIdAndEpochAndRecipientId(groupId, 5L, memberA))
                .thenReturn(Optional.of(key));

        GroupKeySet set = service().getGroupKey(memberA, groupId, null);

        assertThat(set.getEpoch()).isEqualTo(5L);
        assertThat(key.isDelivered()).isTrue();
        verify(keyRepository).save(key);
    }

    @Test
    void getGroupKeyReportsMissingEpoch() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(existingGroup(5)));
        when(keyRepository.findByGroupIdAndEpochAndRecipientId(groupId, 2L, memberA))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getGroupKey(memberA, groupId, 2L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- drainGroupKeysFor ----------------------------------------------------------------

    @Test
    void drainGroupKeysMarksEveryPendingKeyDeliveredAndPersists() {
        GroupKey one = new GroupKey(groupId, 1L, memberA, "b1", Instant.now());
        GroupKey two = new GroupKey(groupId, 2L, memberA, "b2", Instant.now());
        when(keyRepository.findByRecipientIdAndDeliveredFalseOrderByEpochAsc(memberA))
                .thenReturn(List.of(one, two));

        List<GroupKeySet> drained = service().drainGroupKeysFor(memberA);

        assertThat(drained).extracting(GroupKeySet::getEpoch).containsExactly(1L, 2L);
        assertThat(one.isDelivered()).isTrue();
        assertThat(two.isDelivered()).isTrue();
        verify(keyRepository).saveAll(List.of(one, two));
    }

    @Test
    void drainGroupKeysIsANoOpWhenNothingPending() {
        when(keyRepository.findByRecipientIdAndDeliveredFalseOrderByEpochAsc(memberA))
                .thenReturn(List.of());

        assertThat(service().drainGroupKeysFor(memberA)).isEmpty();
        verify(keyRepository, never()).saveAll(any());
    }

    // ---- membersOf ------------------------------------------------------------------------

    @Test
    void membersOfReportsMissingGroup() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().membersOf(groupId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(statusOf(ex)).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
