package net.fourletters.server.repository;

import net.fourletters.server.model.PushSubscriptionEntity;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PushSubscriptionRepository
        extends JpaRepository<@NonNull PushSubscriptionEntity, @NonNull UUID> {
}
