package net.fourletters.server.service;

import net.fourletters.dto.CreateGroupRequest;
import net.fourletters.dto.UpdateMembersRequest;
import net.fourletters.server.model.Group;
import net.fourletters.server.model.GroupMember;
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
 * Group lifecycle and roster administration. The Server owns only the roster; a group message is
 * sent by the client as one independent 1:1 copy per member
 */
@Service
public class GroupService {

    private static final Logger logger = LoggerFactory.getLogger(GroupService.class);

    /**
     * Upper bound for a stored avatar data URL. The client downscales to 256px and caps the encoded
     * payload at ~200KB; this leaves headroom for base64 overhead while rejecting abuse.
     */
    private static final int MAX_AVATAR_URL_LENGTH = 512 * 1024;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;

    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository memberRepository) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
    }

    /** Create a group with the caller as owner. */
    @Transactional
    public net.fourletters.dto.Group createGroup(UUID ownerId, CreateGroupRequest request) {
        if (request == null || request.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        Set<UUID> roster = new LinkedHashSet<>(request.getMembers());
        roster.add(ownerId); // the owner is always a member

        Instant now = Instant.now();
        Group group = new Group();
        group.setId(UUID.randomUUID());
        group.setName(request.getName());
        group.setOwnerId(ownerId);
        group.setAvatarUrl(sanitizeAvatarUrl(request.getAvatarUrl()));
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groupRepository.save(group);

        for (UUID memberId : roster) {
            memberRepository.save(new GroupMember(group.getId(), memberId, now));
        }

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
        // The roster is loaded once and reused for both the membership check and the DTO.
        List<GroupMember> members = memberRepository.findByGroupId(groupId);
        if (members.stream().noneMatch(member -> member.getUserId().equals(userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a member");
        }
        return group.toDto(members, null);
    }

    /** Owner-only roster change: add and/or remove members. */
    @Transactional
    public net.fourletters.dto.Group updateGroupMembers(UUID ownerId, UUID groupId, UpdateMembersRequest request) {
        Group group = requireOwnedGroup(groupId, ownerId);

        Set<UUID> previous = new LinkedHashSet<>(memberRepository.findUserIdsByGroupId(groupId));
        Set<UUID> roster = new LinkedHashSet<>(previous);
        if (request != null && request.getRemove() != null) {
            request.getRemove().forEach(roster::remove);
        }
        if (request != null && request.getAdd() != null) {
            roster.addAll(request.getAdd());
        }
        roster.add(ownerId); // the owner can never be removed

        Instant now = Instant.now();
        boolean removedAny = false;
        for (UUID removed : previous) {
            if (!roster.contains(removed)) {
                memberRepository.deleteByGroupIdAndUserId(groupId, removed);
                removedAny = true;
            }
        }
        for (UUID member : roster) {
            if (!previous.contains(member)) {
                memberRepository.save(new GroupMember(groupId, member, now));
            }
        }

        // A removal invalidates every distributed Sender Key
        if (removedAny) {
            group.setEpoch(group.getEpoch() + 1);
        }
        group.setUpdatedAt(now);
        groupRepository.save(group);
        logger.debug("Group {} roster updated ({} members)", groupId, roster.size());
        return toGroupDto(group, now);
    }

    /** Owner-only: partially update a group's metadata (name and/or avatar). */
    @Transactional
    public net.fourletters.dto.Group updateGroup(UUID ownerId, UUID groupId,
                                                 net.fourletters.dto.UpdateGroupRequest request) {
        Group group = requireOwnedGroup(groupId, ownerId);
        if (request != null) {
            // A null field means "leave unchanged"; the avatar additionally treats "" as "clear".
            if (request.getName() != null) {
                String name = request.getName().trim();
                if (name.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
                }
                group.setName(name);
            }
            if (request.getAvatarUrl() != null) {
                group.setAvatarUrl(sanitizeAvatarUrl(request.getAvatarUrl()));
            }
        }
        Instant now = Instant.now();
        group.setUpdatedAt(now);
        groupRepository.save(group);
        logger.debug("Group {} metadata updated", groupId);
        return toGroupDto(group, now);
    }

    /**
     * Caller removes themselves from a group. Owners cannot leave (they must transfer or delete the
     * group). Idempotent: leaving a group you are not in is a no-op.
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

    // ---- Helpers --------------------------------------------------------------------------

    /**
     * The current roster of a group as plain user ids, for server-side group fan-out. Returns an
     * empty list for an unknown group.
     */
    @Transactional(readOnly = true)
    public List<UUID> groupRoster(UUID groupId) {
        return memberRepository.findUserIdsByGroupId(groupId);
    }

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

    /** Normalize an incoming avatar data URL: blank becomes null (clear), oversize is rejected. */
    private String sanitizeAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return null;
        }
        if (avatarUrl.length() > MAX_AVATAR_URL_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatar too large");
        }
        return avatarUrl;
    }

    /** Load the current roster and let the entity assemble its full wire view. */
    private net.fourletters.dto.Group toGroupDto(Group group, Instant updatedAtOverride) {
        return group.toDto(memberRepository.findByGroupId(group.getId()), updatedAtOverride);
    }
}
