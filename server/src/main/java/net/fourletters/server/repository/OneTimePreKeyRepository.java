package net.fourletters.server.repository;

import java.util.List;
import java.util.UUID;
import net.fourletters.server.model.OneTimePreKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Pool of one-time pre-keys. Supports popping the next available key for a user (consumed on
 * session handout), counting the remaining pool, and replacing the pool on bundle upload.
 */
@Repository
public interface OneTimePreKeyRepository extends JpaRepository<OneTimePreKey, OneTimePreKey.OneTimePreKeyId>, OneTimePreKeyRepositoryCustom {

    /** Lowest-id available one-time pre-keys for a user (caller takes the first). */
    List<OneTimePreKey> findByUserIdOrderByKeyIdAsc(UUID userId);

    /** Remaining one-time pre-keys in a user's pool. */
    long countByUserId(UUID userId);

    /** Drop every one-time pre-key for a user (called before storing a fresh bundle). */
    @Modifying
    @Query("delete from OneTimePreKey k where k.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
