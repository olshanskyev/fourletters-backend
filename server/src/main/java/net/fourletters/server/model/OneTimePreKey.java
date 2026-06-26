package net.fourletters.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One entry in a user's consumable pool of Signal one-time pre-keys. The server hands one out per
 * opened session and deletes it. Composite key {@code (userId, keyId)}.
 */
@Entity
@Table(name = "one_time_prekeys")
@IdClass(OneTimePreKey.OneTimePreKeyId.class)
public class OneTimePreKey {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "key_id")
    private int keyId;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    public OneTimePreKey() {
    }

    public OneTimePreKey(UUID userId, int keyId, String publicKey) {
        this.userId = userId;
        this.keyId = keyId;
        this.publicKey = publicKey;
    }

    /** Build a pool entry for a user from its wire DTO. */
    public static OneTimePreKey from(UUID userId, net.fourletters.dto.OneTimePreKey dto) {
        return new OneTimePreKey(userId, dto.getKeyId(), dto.getPublicKey());
    }

    /** Reconstruct the wire DTO (used when a key is handed out in a bundle). */
    public net.fourletters.dto.OneTimePreKey toDto() {
        net.fourletters.dto.OneTimePreKey dto = new net.fourletters.dto.OneTimePreKey();
        dto.setKeyId(keyId);
        dto.setPublicKey(publicKey);
        return dto;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public int getKeyId() {
        return keyId;
    }

    public void setKeyId(int keyId) {
        this.keyId = keyId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    /** Composite primary key for {@link OneTimePreKey}. */
    public static class OneTimePreKeyId implements java.io.Serializable {
        private UUID userId;
        private int keyId;

        public OneTimePreKeyId() {
        }

        public OneTimePreKeyId(UUID userId, int keyId) {
            this.userId = userId;
            this.keyId = keyId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof OneTimePreKeyId that)) return false;
            return keyId == that.keyId && java.util.Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, keyId);
        }
    }
}
