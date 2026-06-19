package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "public_keys")
public class UserPublicKey {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "signing_public_key", nullable = false, columnDefinition = "TEXT")
    private String signingPublicKey;

    @Column(name = "encryption_public_key", nullable = false, columnDefinition = "TEXT")
    private String encryptionPublicKey;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public UserPublicKey() {
    }

    public UserPublicKey(UUID userId, String signingPublicKey, String encryptionPublicKey, Instant uploadedAt) {
        this.userId = userId;
        this.signingPublicKey = signingPublicKey;
        this.encryptionPublicKey = encryptionPublicKey;
        this.uploadedAt = uploadedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getSigningPublicKey() {
        return signingPublicKey;
    }

    public void setSigningPublicKey(String signingPublicKey) {
        this.signingPublicKey = signingPublicKey;
    }

    public String getEncryptionPublicKey() {
        return encryptionPublicKey;
    }

    public void setEncryptionPublicKey(String encryptionPublicKey) {
        this.encryptionPublicKey = encryptionPublicKey;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}



