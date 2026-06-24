package net.fourletters.server.controller;

import net.fourletters.dto.KeysResponse;
import net.fourletters.dto.KeysUploadRequest;
import net.fourletters.dto.PreKeyCountResponse;
import net.fourletters.dto.PreKeysUploadRequest;
import net.fourletters.dto.PublicKeysBatchResponse;
import net.fourletters.server.service.UserPublicKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fourletters.server.util.SecurityUtils;

@RestController
@RequestMapping("/keys")
public class KeysController {

    private static final Logger logger = LoggerFactory.getLogger(KeysController.class);

    private final UserPublicKeyService userPublicKeyService;

    public KeysController(UserPublicKeyService userPublicKeyService) {
        this.userPublicKeyService = userPublicKeyService;
    }

    @PutMapping(produces = "application/json")
    public ResponseEntity<KeysResponse> uploadKeys(@RequestBody KeysUploadRequest request) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }

        KeysResponse response = userPublicKeyService.uploadKeys(userId, request);
        return ResponseEntity.ok(response);
    }

    /** Append more one-time pre-keys to the caller's pool when it runs low. */
    @PostMapping(value = "/prekeys", produces = "application/json")
    public ResponseEntity<Void> replenishPreKeys(@RequestBody PreKeysUploadRequest request) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request == null || request.getOneTimePreKeys() == null) {
            return ResponseEntity.badRequest().build();
        }
        userPublicKeyService.replenishOneTimePreKeys(userId, request);
        return ResponseEntity.ok().build();
    }

    /** Remaining one-time pre-keys in the caller's pool, so the client can top up below a threshold. */
    @GetMapping(value = "/prekeys/count", produces = "application/json")
    public ResponseEntity<PreKeyCountResponse> countPreKeys() {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        PreKeyCountResponse response = new PreKeyCountResponse();
        response.setCount((int) userPublicKeyService.countOneTimePreKeys(userId));
        return ResponseEntity.ok(response);
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<PublicKeysBatchResponse> getKeysBatch(@RequestParam("ids") String idsStr) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (idsStr == null || idsStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        List<UUID> ids = new ArrayList<>();
        for (String idPart : idsStr.split(",")) {
            try {
                ids.add(UUID.fromString(idPart.trim()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        List<KeysResponse> results = userPublicKeyService.getKeysBatch(ids);
        PublicKeysBatchResponse response = new PublicKeysBatchResponse();
        response.setResults(results);

        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{userId}", produces = "application/json")
    public ResponseEntity<KeysResponse> getKeysByUserId(@PathVariable("userId") String requestedUserIdStr) {
        UUID currentUserId = SecurityUtils.currentUserId();
        if (currentUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID requestedUserId;
        try {
            requestedUserId = UUID.fromString(requestedUserIdStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Optional<KeysResponse> responseOpt = userPublicKeyService.getKeysByUserId(requestedUserId);
        return responseOpt.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
