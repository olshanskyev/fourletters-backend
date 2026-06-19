package net.fourletters.server.service;

import net.fourletters.dto.KeysResponse;
import net.fourletters.dto.KeysUploadRequest;
import net.fourletters.dto.PublicKeySet;
import net.fourletters.server.model.UserPublicKey;
import net.fourletters.server.repository.UserPublicKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserPublicKeyService {

    private final UserPublicKeyRepository repository;

    public UserPublicKeyService(UserPublicKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public KeysResponse uploadKeys(UUID userId, KeysUploadRequest request) {
        UserPublicKey userPublicKey = repository.findById(userId)
                .orElseGet(() -> {
                    UserPublicKey newKey = new UserPublicKey();
                    newKey.setUserId(userId);
                    return newKey;
                });

        userPublicKey.setSigningPublicKey(request.getSigningPublicKey());
        userPublicKey.setEncryptionPublicKey(request.getEncryptionPublicKey());
        userPublicKey.setUploadedAt(Instant.now());

        UserPublicKey saved = repository.save(userPublicKey);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public Optional<KeysResponse> getKeysByUserId(UUID userId) {
        return repository.findById(userId).map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public List<KeysResponse> getKeysBatch(List<UUID> userIds) {
        return repository.findAllByUserIdIn(userIds).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private KeysResponse mapToResponse(UserPublicKey userPublicKey) {
        PublicKeySet keys = new PublicKeySet();
        keys.setSigningPublicKey(userPublicKey.getSigningPublicKey());
        keys.setEncryptionPublicKey(userPublicKey.getEncryptionPublicKey());
        keys.setUploadedAt(userPublicKey.getUploadedAt().toEpochMilli());

        KeysResponse response = new KeysResponse();
        response.setUserId(userPublicKey.getUserId());
        response.setKeys(keys);

        return response;
    }
}




