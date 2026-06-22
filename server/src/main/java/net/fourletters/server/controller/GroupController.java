package net.fourletters.server.controller;

import net.fourletters.dto.CreateGroupRequest;
import net.fourletters.dto.Group;
import net.fourletters.dto.GroupKeyDistribution;
import net.fourletters.dto.GroupKeySet;
import net.fourletters.dto.GroupSummary;
import net.fourletters.dto.UpdateMembersRequest;
import net.fourletters.server.service.GroupService;
import net.fourletters.server.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Group lifecycle, roster administration, and sender-key distribution. Authorization (owner-only
 * actions, membership checks) and epoch compare-and-set are enforced in {@link GroupService};
 * validation/authz failures surface as {@code ResponseStatusException} with the documented status.
 */
@RestController
@RequestMapping("/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping(produces = "application/json")
    public ResponseEntity<Group> createGroup(@RequestBody CreateGroupRequest request) {
        UUID ownerId = SecurityUtils.currentUserId();
        if (ownerId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.createGroup(ownerId, request));
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<List<GroupSummary>> listGroups() {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(groupService.listGroups(userId));
    }

    @GetMapping(value = "/{groupId}", produces = "application/json")
    public ResponseEntity<Group> getGroup(@PathVariable("groupId") UUID groupId) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(groupService.getGroup(userId, groupId));
    }

    @PatchMapping(value = "/{groupId}/members", produces = "application/json")
    public ResponseEntity<Group> updateGroupMembers(@PathVariable("groupId") UUID groupId,
                                                    @RequestBody UpdateMembersRequest request) {
        UUID ownerId = SecurityUtils.currentUserId();
        if (ownerId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(groupService.updateGroupMembers(ownerId, groupId, request));
    }

    @DeleteMapping(value = "/{groupId}/members/me")
    public ResponseEntity<Void> leaveGroup(@PathVariable("groupId") UUID groupId) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        groupService.leaveGroup(userId, groupId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{groupId}/keys", produces = "application/json")
    public ResponseEntity<Group> rotateGroupKey(@PathVariable("groupId") UUID groupId,
                                                @RequestBody GroupKeyDistribution distribution) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(groupService.rotateGroupKey(userId, groupId, distribution));
    }

    @GetMapping(value = "/{groupId}/keys", produces = "application/json")
    public ResponseEntity<GroupKeySet> getGroupKey(@PathVariable("groupId") UUID groupId,
                                                   @RequestParam(value = "epoch", required = false) Long epoch) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(groupService.getGroupKey(userId, groupId, epoch));
    }
}
