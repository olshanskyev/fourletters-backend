package net.fourletters.server.service;

import net.fourletters.dto.CreateGroupRequest;
import net.fourletters.dto.GroupKeyDistribution;
import net.fourletters.dto.GroupKeyEvent;
import net.fourletters.dto.GroupKeyNotification;
import net.fourletters.dto.GroupKeySet;
import net.fourletters.dto.UpdateMembersRequest;
import net.fourletters.dto.WrappedGroupKey;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.Group;
import net.fourletters.server.model.GroupKey;
import net.fourletters.server.model.GroupMember;
import net.fourletters.server.repository.GroupKeyRepository;
import net.fourletters.server.repository.GroupMemberRepository;
import net.fourletters.server.repository.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Group lifecycle, roster administration, and sender-key distribution.
 */
@Service
public class GroupService {

    private static final Logger logger = LoggerFactory.getLogger(GroupService.class);

    private final GroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupKeyRepository keyRepository;
    private final ServerRabbitMqService rabbitMqService;

    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository memberRepository,
                        GroupKeyRepository keyRepository,
                        ServerRabbitMqService rabbitMqService) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.keyRepository = keyRepository;
        this.rabbitMqService = rabbitMqService;
    }

    /** Create a group at epoch 0 with the caller as owner. The key set must cover the full roster. */
    @Transactional
    public net.fourletters.dto.Group createGroup(UUID ownerId, CreateGroupRequest request) {
        if (request == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        Set<UUID> roster = new LinkedHashSet<>(request.getMembers());
        roster.add(ownerId); // the owner is always a member
        validateKeysCoverRoster(request.getKeys(), roster);

        Instant now = Instant.now();
        Group group = new Group();
        group.setId(UUID.randomUUID());
        group.setName(request.getName());
        group.setOwnerId(ownerId);
        group.setEpoch(0L);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groupRepository.save(group);

        for (UUID memberId : roster) {
            memberRepository.save(new GroupMember(group.getId(), memberId, now));
        }
        storeKeys(group.getId(), 0L, request.getKeys(), now);

        publishNudge(roster, ownerId, group.getId(), 0L);
        logger.debug("Created group {} (owner {}, {} members)", group.getId(), ownerId, roster.size());
        return toGroupDto(group, now);
    }

    /** Groups the caller belongs to, as lightweight summaries. */
    @Transactional(readOnly = true)
    public List<net.fourletters.dto.GroupSummary> listGroups(UUID userId) {
        List<net.fourletters.dto.GroupSummary> summaries = new ArrayList<>();
        for (GroupMember membership : memberRepository.findByUserId(userId)) {
            groupRepository.findById(membership.getGroupId())
                    .ifPresent(group -> summaries.add(group.toSummary()));
        }
        return summaries;
    }

    /** Full group detail, including roster. Caller must be a member. */
    @Transactional(readOnly = true)
    public net.fourletters.dto.Group getGroup(UUID userId, UUID groupId) {
        Group group = requireGroup(groupId);
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a member");
        }
        return toGroupDto(group, null);
    }

    /** Owner-only roster change with a fresh key set, gated by an epoch compare-and-set. */
    @Transactional
    public net.fourletters.dto.Group updateGroupMembers(UUID ownerId, UUID groupId, UpdateMembersRequest request) {
        Group group = requireOwnedGroup(groupId, ownerId);
        long expected = requireNextEpoch(group, request == null ? null : request.getEpoch());

        Set<UUID> previous = new LinkedHashSet<>(memberRepository.findUserIdsByGroupId(groupId));
        Set<UUID> roster = new LinkedHashSet<>(previous);
        if (request.getRemove() != null) {
            request.getRemove().forEach(roster::remove);
        }
        if (request.getAdd() != null) {
            roster.addAll(request.getAdd());
        }
        roster.add(ownerId); // the owner can never be removed
        validateKeysCoverRoster(request.getKeys(), roster);

        Instant now = Instant.now();
        if (groupRepository.compareAndSetEpoch(groupId, group.getEpoch(), expected, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "epoch changed concurrently");
        }

        for (UUID removed : previous) {
            if (!roster.contains(removed)) {
                memberRepository.deleteByGroupIdAndUserId(groupId, removed);
            }
        }
        for (UUID member : roster) {
            if (!previous.contains(member)) {
                memberRepository.save(new GroupMember(groupId, member, now));
            }
        }
        storeKeys(groupId, expected, request.getKeys(), now);

        group.setEpoch(expected);
        group.setUpdatedAt(now);
        publishNudge(roster, ownerId, groupId, expected);
        logger.debug("Group {} roster updated to epoch {} ({} members)", groupId, expected, roster.size());
        return toGroupDto(group, now);
    }

    /**
     * Rotate the group key with no roster change, gated by an epoch compare-and-set. Any current
     * member may rotate — not just the owner — so forward secrecy can be restored after a departure
     * even while the owner is offline. Concurrent rotations serialize on the epoch CAS: the first
     * commit wins and the loser gets a {@code 409} to refetch and retry.
     */
    @Transactional
    public net.fourletters.dto.Group rotateGroupKey(UUID callerId, UUID groupId, GroupKeyDistribution distribution) {
        Group group = requireMemberGroup(groupId, callerId);
        long expected = requireNextEpoch(group, distribution == null ? null : distribution.getEpoch());

        Set<UUID> roster = new LinkedHashSet<>(memberRepository.findUserIdsByGroupId(groupId));
        roster.add(group.getOwnerId());
        validateKeysCoverRoster(distribution.getKeys(), roster);

        Instant now = Instant.now();
        if (groupRepository.compareAndSetEpoch(groupId, group.getEpoch(), expected, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "epoch changed concurrently");
        }
        storeKeys(groupId, expected, distribution.getKeys(), now);

        group.setEpoch(expected);
        group.setUpdatedAt(now);
        publishNudge(roster, callerId, groupId, expected);
        logger.debug("Group {} key rotated to epoch {} by member {}", groupId, expected, callerId);
        return toGroupDto(group, now);
    }

    /**
     * Caller removes themselves from a group. Owners cannot leave (they must transfer or delete the
     * group). Idempotent: leaving a group you are not in is a no-op. Leaving
     * does not rotate the key here.
     */
    @Transactional
    public void leaveGroup(UUID userId, UUID groupId) {
        Group group = requireGroup(groupId);
        if (group.getOwnerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner cannot leave the group");
        }
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);
        logger.debug("User {} left group {}", userId, groupId);
    }

    /** The caller's wrapped key for a specific epoch (or the current epoch when {@code epoch} is null). */
    @Transactional
    public GroupKeySet getGroupKey(UUID userId, UUID groupId, Long epoch) {
        Group group = requireGroup(groupId);
        long target = epoch != null ? epoch : group.getEpoch();
        GroupKey key = keyRepository.findByGroupIdAndEpochAndRecipientId(groupId, target, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no key for this epoch"));
        key.setDelivered(true);
        keyRepository.save(key);
        return key.toKeySet();
    }

    // ---- Used by InboxService -------------------------------------------------------------

    /** The current roster of a group, for message fan-out. Throws 404 if the group does not exist. */
    @Transactional(readOnly = true)
    public List<UUID> membersOf(UUID groupId) {
        requireGroup(groupId);
        return memberRepository.findUserIdsByGroupId(groupId);
    }

    /**
     * Drain the undelivered wrapped keys owed to a user, marking them delivered. Carried on the
     * {@code /inbox} response so an offline member catches up on every epoch it missed.
     */
    @Transactional
    public List<GroupKeySet> drainGroupKeysFor(UUID userId) {
        List<GroupKey> pending = keyRepository.findByRecipientIdAndDeliveredFalseOrderByEpochAsc(userId);
        List<GroupKeySet> result = new ArrayList<>(pending.size());
        for (GroupKey key : pending) {
            key.setDelivered(true);
            result.add(key.toKeySet());
        }
        if (!pending.isEmpty()) {
            keyRepository.saveAll(pending);
        }
        return result;
    }

    // ---- Helpers --------------------------------------------------------------------------

    private Group requireGroup(UUID groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "group not found"));
    }

    private Group requireOwnedGroup(UUID groupId, UUID ownerId) {
        Group group = requireGroup(groupId);
        if (!group.getOwnerId().equals(ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the owner may administer the group");
        }
        return group;
    }

    private Group requireMemberGroup(UUID groupId, UUID userId) {
        Group group = requireGroup(groupId);
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a member of the group");
        }
        return group;
    }

    /** The supplied epoch must be exactly {@code currentEpoch + 1}, else a 409 (stale view). */
    private long requireNextEpoch(Group group, Long requestedEpoch) {
        if (requestedEpoch == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "epoch is required");
        }
        if (requestedEpoch != group.getEpoch() + 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale epoch; expected "
                    + (group.getEpoch() + 1));
        }
        return requestedEpoch;
    }

    private void validateKeysCoverRoster(List<WrappedGroupKey> keys, Set<UUID> roster) {
        if (keys == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keys are required");
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        for (WrappedGroupKey key : keys) {
            if (key.getWrappedKey().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "each key needs a recipient and blob");
            }
            recipients.add(key.getRecipientId());
        }
        if (!recipients.equals(roster)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key set must cover exactly the resulting roster");
        }
    }

    private void storeKeys(UUID groupId, long epoch, List<WrappedGroupKey> keys, Instant now) {
        for (WrappedGroupKey key : keys) {
            keyRepository.save(new GroupKey(groupId, epoch, key.getRecipientId(), key.getWrappedKey(), now));
        }
    }

    /** Nudge every member except the actor who minted this key (they already hold it). */
    private void publishNudge(Set<UUID> roster, UUID actorId, UUID groupId, long epoch) {
        GroupKeyNotification notification = new GroupKeyNotification();
        notification.setGroupId(groupId);
        notification.setEpoch(epoch);
        GroupKeyEvent event = new GroupKeyEvent();
        event.setEvent(GroupKeyEvent.EventEnum.GROUP_KEY_ROTATED);
        event.setData(notification);
        for (UUID memberId : roster) {
            if (!memberId.equals(actorId)) {
                rabbitMqService.publishGroupKeyEvent(memberId, event);
            }
        }
    }

    /** Load the current roster and let the entity assemble its full wire view. */
    private net.fourletters.dto.Group toGroupDto(Group group, Instant updatedAtOverride) {
        return group.toDto(memberRepository.findByGroupId(group.getId()), updatedAtOverride);
    }
}
