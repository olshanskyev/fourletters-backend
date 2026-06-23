package net.fourletters.server.repository;

import java.util.UUID;
import net.fourletters.server.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupRepository extends JpaRepository<Group, UUID> {
}
