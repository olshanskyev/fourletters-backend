package net.fourletters.server.controller;

import net.fourletters.dto.PublicUser;
import net.fourletters.dto.UserBatchResponse;
import net.fourletters.server.service.UserService;
import net.fourletters.server.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * User directory: serves public profiles (display name and avatar) used to render conversation and
 * roster metadata. Exposes no roles or private account data; see {@code /auth/user} for the
 * caller's own full account.
 */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<UserBatchResponse> getUsersBatch(@RequestParam("ids") String ids) {
        if (SecurityUtils.currentUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(userService.getUsersBatch(parseIds(ids)));
    }

    @GetMapping(value = "/{userId}", produces = "application/json")
    public ResponseEntity<PublicUser> getUser(@PathVariable("userId") UUID userId) {
        if (SecurityUtils.currentUserId() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(userService.getPublicUser(userId));
    }

    /** Parse the comma-separated {@code ids} query parameter, skipping blank or malformed entries. */
    private List<UUID> parseIds(String ids) {
        if (ids == null || ids.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids is required");
        }
        List<UUID> result = new ArrayList<>();
        for (String token : ids.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed user id: " + trimmed);
            }
        }
        return result;
    }
}
