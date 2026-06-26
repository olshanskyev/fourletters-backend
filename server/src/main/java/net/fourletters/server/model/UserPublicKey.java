package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.fourletters.dto.KeysResponse;
import net.fourletters.dto.KeysUploadRequest;
import net.fourletters.dto.PublicKeySet;
import java.time.Instant;
import java.util.UUID;

/**
 * Directory entry for a user's Signal pre-key bundle: long-lived identity key, registration id and
 * the current signed pre-key. One-time pre-keys live in {@link OneTimePreKey}.
 */
@Entity
@Table(name = "public_keys")
public class UserPublicKey {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "registration_id", nullable = false)
    private int registrationId;

    @Column(name = "identity_key", nullable = false, columnDefinition = "TEXT")
    private String identityKey;

    @Column(name = "signed_prekey_id", nullable = false)
    private int signedPrekeyId;

    @Column(name = "signed_prekey_public", nullable = false, columnDefinition = "TEXT")
    private String signedPrekeyPublic;

    @Column(name = "signed_prekey_signature", nullable = false, columnDefinition = "TEXT")
    private String signedPrekeySignature;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public UserPublicKey() {
    }

    /** Build a fresh directory entry for a user from an upload request. */
    public static UserPublicKey from(UUID userId, KeysUploadRequest request) {
        UserPublicKey entry = new UserPublicKey();
        entry.userId = userId;
        entry.applyUpload(request);
        return entry;
    }

    /** Overwrite the mutable bundle fields from a (re-)upload request. */
    public void applyUpload(KeysUploadRequest request) {
        net.fourletters.dto.SignedPreKey signedPreKey = request.getSignedPreKey();
        this.registrationId = request.getRegistrationId();
        this.identityKey = request.getIdentityKey();
        this.signedPrekeyId = signedPreKey.getKeyId();
        this.signedPrekeyPublic = signedPreKey.getPublicKey();
        this.signedPrekeySignature = signedPreKey.getSignature();
        this.uploadedAt = Instant.now();
    }

    /** Reconstruct the directory response, optionally embedding a popped one-time pre-key. */
    public KeysResponse toDto(OneTimePreKey oneTimePreKey) {
        net.fourletters.dto.SignedPreKey signedPreKey = new net.fourletters.dto.SignedPreKey();
        signedPreKey.setKeyId(signedPrekeyId);
        signedPreKey.setPublicKey(signedPrekeyPublic);
        signedPreKey.setSignature(signedPrekeySignature);

        PublicKeySet keys = new PublicKeySet();
        keys.setRegistrationId(registrationId);
        keys.setIdentityKey(identityKey);
        keys.setSignedPreKey(signedPreKey);
        keys.setUploadedAt(uploadedAt.toEpochMilli());
        if (oneTimePreKey != null) {
            keys.setOneTimePreKey(oneTimePreKey.toDto());
        }

        KeysResponse response = new KeysResponse();
        response.setUserId(userId);
        response.setKeys(keys);
        return response;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public int getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(int registrationId) {
        this.registrationId = registrationId;
    }

    public String getIdentityKey() {
        return identityKey;
    }

    public void setIdentityKey(String identityKey) {
        this.identityKey = identityKey;
    }

    public int getSignedPrekeyId() {
        return signedPrekeyId;
    }

    public void setSignedPrekeyId(int signedPrekeyId) {
        this.signedPrekeyId = signedPrekeyId;
    }

    public String getSignedPrekeyPublic() {
        return signedPrekeyPublic;
    }

    public void setSignedPrekeyPublic(String signedPrekeyPublic) {
        this.signedPrekeyPublic = signedPrekeyPublic;
    }

    public String getSignedPrekeySignature() {
        return signedPrekeySignature;
    }

    public void setSignedPrekeySignature(String signedPrekeySignature) {
        this.signedPrekeySignature = signedPrekeySignature;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}



