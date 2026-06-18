package net.fourletters.server.repository;

import net.fourletters.server.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    Optional<RefreshToken> findByTokenAndSessionId(String token, String sessionId);
    Optional<RefreshToken> findBySessionId(String sessionId);
    List<RefreshToken> findAllByUser(net.fourletters.server.model.User user);
    void deleteBySessionId(String sessionId);
}
