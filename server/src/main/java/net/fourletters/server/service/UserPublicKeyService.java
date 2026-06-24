package net.fourletters.server.service;

import net.fourletters.dto.KeysResponse;
import net.fourletters.dto.KeysUploadRequest;
import net.fourletters.dto.OneTimePreKeyDto;
import net.fourletters.dto.PreKeysUploadRequest;
import net.fourletters.server.model.OneTimePreKey;
import net.fourletters.server.model.UserPublicKey;
import net.fourletters.server.repository.OneTimePreKeyRepository;
import net.fourletters.server.repository.UserPublicKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signal pre-key directory. Stores each user's bundle (identity + signed pre-key) and a consumable
 * pool of one-time pre-keys, one popped per session handout.
 */
@Service
public class UserPublicKeyService {

    private final UserPublicKeyRepository repository;
    private final OneTimePreKeyRepository oneTimePreKeyRepository;

    public UserPublicKeyService(UserPublicKeyRepository repository,
                                OneTimePreKeyRepository oneTimePreKeyRepository) {
        this.repository = repository;
        this.oneTimePreKeyRepository = oneTimePreKeyRepository;
    }

    /** Replace the caller's full pre-key bundle (fresh-device upload). */
    @Transactional
    public KeysResponse uploadKeys(UUID userId, KeysUploadRequest request) {
        UserPublicKey userPublicKey = repository.findById(userId)
                .map(existing -> {
                    existing.applyUpload(request);
                    return existing;
                })
                .orElseGet(() -> UserPublicKey.from(userId, request));
        UserPublicKey saved = repository.save(userPublicKey);

        oneTimePreKeyRepository.deleteByUserId(userId);
        storeOneTimePreKeys(userId, request.getOneTimePreKeys());

        return saved.toDto(null);
    }

    /** Append more one-time pre-keys to the caller's pool. */
    @Transactional
    public void replenishOneTimePreKeys(UUID userId, PreKeysUploadRequest request) {
        storeOneTimePreKeys(userId, request.getOneTimePreKeys());
    }

    /** Remaining one-time pre-keys in the caller's pool. */
    @Transactional(readOnly = true)
    public long countOneTimePreKeys(UUID userId) {
        return oneTimePreKeyRepository.countByUserId(userId);
    }

    /** Fetch a user's bundle, popping (consuming) one one-time pre-key if any remain. */
    @Transactional
    public Optional<KeysResponse> getKeysByUserId(UUID userId) {
        return repository.findById(userId).map(key -> key.toDto(popOneTimePreKey(userId)));
    }

    /** Batch fetch directory entries without consuming one-time pre-keys (used for listing). */
    @Transactional(readOnly = true)
    public List<KeysResponse> getKeysBatch(List<UUID> userIds) {
        return repository.findAllByUserIdIn(userIds).stream()
                .map(key -> key.toDto(null))
                .collect(Collectors.toList());
    }

    private void storeOneTimePreKeys(UUID userId, List<OneTimePreKeyDto> preKeys) {
        if (preKeys == null || preKeys.isEmpty()) {
            return;
        }
        List<OneTimePreKey> entities = new ArrayList<>(preKeys.size());
        for (OneTimePreKeyDto dto : preKeys) {
            entities.add(OneTimePreKey.from(userId, dto));
        }
        oneTimePreKeyRepository.insertAll(entities);
    }

    private OneTimePreKey popOneTimePreKey(UUID userId) {
        List<OneTimePreKey> available = oneTimePreKeyRepository.findByUserIdOrderByKeyIdAsc(userId);
        if (available.isEmpty()) {
            return null;
        }
        OneTimePreKey claimed = available.get(0);
        oneTimePreKeyRepository.delete(claimed);
        return claimed;
    }
}




