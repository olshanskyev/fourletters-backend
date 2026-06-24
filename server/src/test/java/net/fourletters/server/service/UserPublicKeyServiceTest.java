package net.fourletters.server.service;

import net.fourletters.dto.KeysResponse;
import net.fourletters.dto.KeysUploadRequest;
import net.fourletters.dto.OneTimePreKeyDto;
import net.fourletters.dto.PreKeysUploadRequest;
import net.fourletters.dto.SignedPreKeyDto;
import net.fourletters.server.model.OneTimePreKey;
import net.fourletters.server.model.UserPublicKey;
import net.fourletters.server.repository.OneTimePreKeyRepository;
import net.fourletters.server.repository.UserPublicKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Signal pre-key directory: bundle upload (insert vs. in-place update), one-time
 * pre-key replenish/count, and bundle fetch that pops exactly one one-time pre-key from the pool.
 */
@ExtendWith(MockitoExtension.class)
class UserPublicKeyServiceTest {

    @Mock
    private UserPublicKeyRepository repository;
    @Mock
    private OneTimePreKeyRepository oneTimePreKeyRepository;

    private UserPublicKeyService service;

    private final UUID userId = UUID.randomUUID();

    private UserPublicKeyService service() {
        if (service == null) {
            service = new UserPublicKeyService(repository, oneTimePreKeyRepository);
        }
        return service;
    }

    private SignedPreKeyDto signedPreKey(int keyId, String pub, String sig) {
        SignedPreKeyDto spk = new SignedPreKeyDto();
        spk.setKeyId(keyId);
        spk.setPublicKey(pub);
        spk.setSignature(sig);
        return spk;
    }

    private OneTimePreKeyDto preKey(int keyId, String pub) {
        OneTimePreKeyDto dto = new OneTimePreKeyDto();
        dto.setKeyId(keyId);
        dto.setPublicKey(pub);
        return dto;
    }

    private KeysUploadRequest uploadRequest(List<OneTimePreKeyDto> oneTimePreKeys) {
        KeysUploadRequest request = new KeysUploadRequest();
        request.setRegistrationId(4242);
        request.setIdentityKey("idKey");
        request.setSignedPreKey(signedPreKey(1, "spkPub", "spkSig"));
        request.setOneTimePreKeys(oneTimePreKeys);
        return request;
    }

    // ---- uploadKeys -----------------------------------------------------------------------

    @Test
    void uploadKeysCreatesNewDirectoryEntryWhenNoneExists() {
        KeysUploadRequest request = uploadRequest(List.of(preKey(10, "p10"), preKey(11, "p11")));
        when(repository.findById(userId)).thenReturn(Optional.empty());
        when(repository.save(any(UserPublicKey.class))).thenAnswer(inv -> inv.getArgument(0));

        KeysResponse response = service().uploadKeys(userId, request);

        ArgumentCaptor<UserPublicKey> saved = ArgumentCaptor.forClass(UserPublicKey.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getRegistrationId()).isEqualTo(4242);
        assertThat(saved.getValue().getIdentityKey()).isEqualTo("idKey");
        assertThat(saved.getValue().getSignedPrekeyId()).isEqualTo(1);

        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getKeys().getIdentityKey()).isEqualTo("idKey");
        // A fresh-bundle upload never hands out a one-time pre-key in its own response.
        assertThat(response.getKeys().getOneTimePreKey()).isNull();
    }

    @Test
    void uploadKeysUpdatesExistingEntryInPlace() {
        UserPublicKey existing = UserPublicKey.from(userId, uploadRequest(List.of()));
        when(repository.findById(userId)).thenReturn(Optional.of(existing));
        when(repository.save(any(UserPublicKey.class))).thenAnswer(inv -> inv.getArgument(0));

        KeysUploadRequest reupload = uploadRequest(List.of(preKey(20, "p20")));
        reupload.setIdentityKey("rotatedKey");
        reupload.setRegistrationId(9001);

        service().uploadKeys(userId, reupload);

        ArgumentCaptor<UserPublicKey> saved = ArgumentCaptor.forClass(UserPublicKey.class);
        verify(repository).save(saved.capture());
        // Same instance is mutated, not a new row.
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().getIdentityKey()).isEqualTo("rotatedKey");
        assertThat(saved.getValue().getRegistrationId()).isEqualTo(9001);
    }

    @Test
    void uploadKeysReplacesOneTimePreKeyPool() {
        KeysUploadRequest request = uploadRequest(List.of(preKey(10, "p10"), preKey(11, "p11")));
        when(repository.findById(userId)).thenReturn(Optional.empty());
        when(repository.save(any(UserPublicKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service().uploadKeys(userId, request);

        // The old pool is dropped before the new one is inserted.
        verify(oneTimePreKeyRepository).deleteByUserId(userId);

        ArgumentCaptor<List<OneTimePreKey>> inserted = ArgumentCaptor.forClass(List.class);
        verify(oneTimePreKeyRepository).insertAll(inserted.capture());
        assertThat(inserted.getValue())
                .extracting(OneTimePreKey::getKeyId)
                .containsExactly(10, 11);
        assertThat(inserted.getValue())
                .allSatisfy(k -> assertThat(k.getUserId()).isEqualTo(userId));
    }

    @Test
    void uploadKeysWithEmptyPoolStillClearsButDoesNotInsert() {
        KeysUploadRequest request = uploadRequest(List.of());
        when(repository.findById(userId)).thenReturn(Optional.empty());
        when(repository.save(any(UserPublicKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service().uploadKeys(userId, request);

        verify(oneTimePreKeyRepository).deleteByUserId(userId);
        verify(oneTimePreKeyRepository, never()).insertAll(anyList());
    }

    // ---- replenishOneTimePreKeys ----------------------------------------------------------

    @Test
    void replenishInsertsNewPreKeysWithoutClearingPool() {
        PreKeysUploadRequest request = new PreKeysUploadRequest();
        request.setOneTimePreKeys(List.of(preKey(50, "p50"), preKey(51, "p51")));

        service().replenishOneTimePreKeys(userId, request);

        verify(oneTimePreKeyRepository, never()).deleteByUserId(any());

        ArgumentCaptor<List<OneTimePreKey>> inserted = ArgumentCaptor.forClass(List.class);
        verify(oneTimePreKeyRepository).insertAll(inserted.capture());
        assertThat(inserted.getValue())
                .extracting(OneTimePreKey::getKeyId)
                .containsExactly(50, 51);
    }

    @Test
    void replenishWithEmptyListInsertsNothing() {
        PreKeysUploadRequest request = new PreKeysUploadRequest();
        request.setOneTimePreKeys(List.of());

        service().replenishOneTimePreKeys(userId, request);

        verify(oneTimePreKeyRepository, never()).insertAll(anyList());
    }

    // ---- countOneTimePreKeys --------------------------------------------------------------

    @Test
    void countDelegatesToRepository() {
        when(oneTimePreKeyRepository.countByUserId(userId)).thenReturn(7L);

        assertThat(service().countOneTimePreKeys(userId)).isEqualTo(7L);
    }

    // ---- getKeysByUserId ------------------------------------------------------------------

    @Test
    void getKeysPopsLowestOneTimePreKeyAndDeletesIt() {
        UserPublicKey entry = UserPublicKey.from(userId, uploadRequest(List.of()));
        when(repository.findById(userId)).thenReturn(Optional.of(entry));
        OneTimePreKey lowest = new OneTimePreKey(userId, 5, "p5");
        OneTimePreKey higher = new OneTimePreKey(userId, 6, "p6");
        when(oneTimePreKeyRepository.findByUserIdOrderByKeyIdAsc(userId))
                .thenReturn(List.of(lowest, higher));

        Optional<KeysResponse> response = service().getKeysByUserId(userId);

        assertThat(response).isPresent();
        assertThat(response.get().getKeys().getOneTimePreKey().getKeyId()).isEqualTo(5);
        // The handed-out key is consumed (deleted) so it is never reused.
        verify(oneTimePreKeyRepository).delete(lowest);
    }

    @Test
    void getKeysReturnsBundleWithoutOneTimePreKeyWhenPoolEmpty() {
        UserPublicKey entry = UserPublicKey.from(userId, uploadRequest(List.of()));
        when(repository.findById(userId)).thenReturn(Optional.of(entry));
        when(oneTimePreKeyRepository.findByUserIdOrderByKeyIdAsc(userId)).thenReturn(List.of());

        Optional<KeysResponse> response = service().getKeysByUserId(userId);

        assertThat(response).isPresent();
        assertThat(response.get().getKeys().getOneTimePreKey()).isNull();
        verify(oneTimePreKeyRepository, never()).delete(any());
    }

    @Test
    void getKeysReturnsEmptyWhenUserNotInDirectory() {
        when(repository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service().getKeysByUserId(userId)).isEmpty();
        verify(oneTimePreKeyRepository, never()).findByUserIdOrderByKeyIdAsc(any());
    }

    // ---- getKeysBatch ---------------------------------------------------------------------

    @Test
    void getKeysBatchMapsEntriesWithoutConsumingPreKeys() {
        UUID other = UUID.randomUUID();
        UserPublicKey a = UserPublicKey.from(userId, uploadRequest(List.of()));
        UserPublicKey b = UserPublicKey.from(other, uploadRequest(List.of()));
        when(repository.findAllByUserIdIn(List.of(userId, other))).thenReturn(List.of(a, b));

        List<KeysResponse> responses = service().getKeysBatch(List.of(userId, other));

        assertThat(responses)
                .extracting(KeysResponse::getUserId)
                .containsExactly(userId, other);
        assertThat(responses).allSatisfy(r -> assertThat(r.getKeys().getOneTimePreKey()).isNull());
        verify(oneTimePreKeyRepository, never()).findByUserIdOrderByKeyIdAsc(any());
    }
}
