package net.fourletters.server.repository;

import java.time.Instant;
import java.util.UUID;
import net.fourletters.server.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupRepository extends JpaRepository<Group, UUID> {

    /**
     * Atomic compare-and-set of the group epoch. Succeeds (returns 1) only if the current epoch
     * still equals {@code expectedEpoch}, making concurrent membership changes / rotations
     * serialize: the first writer wins and any other is rejected with a 409. {@code updated_at}
     * is advanced in the same statement so it can never lag the epoch it describes.
     *
     * @return rows affected (1 on success, 0 if another writer already advanced the epoch)
     */
    @Modifying
    @Query("UPDATE Group g SET g.epoch = :newEpoch, g.updatedAt = :updatedAt "
            + "WHERE g.id = :groupId AND g.epoch = :expectedEpoch")
    int compareAndSetEpoch(@Param("groupId") UUID groupId,
                           @Param("expectedEpoch") long expectedEpoch,
                           @Param("newEpoch") long newEpoch,
                           @Param("updatedAt") Instant updatedAt);
}
