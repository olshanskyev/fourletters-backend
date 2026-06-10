package net.fourletters.server.repository;


import net.fourletters.server.model.User;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UsersRepository extends JpaRepository<@NonNull User, @NonNull Long> {
}
