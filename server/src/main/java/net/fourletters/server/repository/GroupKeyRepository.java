package net.fourletters.server.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fourletters.server.model.GroupKey;
import net.fourletters.server.model.GroupKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupKeyRepository extends JpaRepository<GroupKey, GroupKeyId> {

    /** Undelivered wrapped keys owed to a member, oldest epoch first, for {@code /inbox} drain. */
    List<GroupKey> findByRecipientIdAndDeliveredFalseOrderByEpochAsc(UUID recipientId);

    Optional<GroupKey> findByGroupIdAndEpochAndRecipientId(UUID groupId, long epoch, UUID recipientId);
}
