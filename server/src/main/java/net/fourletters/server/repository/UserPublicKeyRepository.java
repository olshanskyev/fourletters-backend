package net.fourletters.server.repository;

import net.fourletters.server.model.UserPublicKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserPublicKeyRepository extends JpaRepository<UserPublicKey, UUID> {
    List<UserPublicKey> findAllByUserIdIn(List<UUID> userIds);
}

