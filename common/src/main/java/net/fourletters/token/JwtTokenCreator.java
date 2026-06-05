package net.fourletters.token;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;

import io.jsonwebtoken.Jwts;

public class JwtTokenCreator {

    public record TokenDetails(String token, Date expiryDate, long validitySeconds) {}

    private final long ACCESS_TOKEN_VALIDITY_SEC;
    private final long REFRESH_TOKEN_VALIDITY_SEC;
    private final String issuer;

    private final PrivateKey privateKey;

    public JwtTokenCreator(
            JwtProperties jwtProperties
    ) {
        this.ACCESS_TOKEN_VALIDITY_SEC = jwtProperties.getAccessTokenValiditySec();
        this.REFRESH_TOKEN_VALIDITY_SEC = jwtProperties.getRefreshTokenValiditySec();
        this.issuer = jwtProperties.getIssuer();
        String privateKeyPath = jwtProperties.getPrivateKeyPath();

        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            throw new IllegalStateException("rest.jwt.privateKeyPath must be configured for JWT signing");
        }
        try {
            this.privateKey = KeyLoader.loadPrivateKey(privateKeyPath);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load private keys for JWT", ex);
        }
    }


    private TokenDetails doGenerateToken(String username, long expirationSeconds, java.util.Map<String, Object> additionalClaims, String sessionId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(expirationSeconds);
        Date expiryDate = Date.from(expiry);

        var builder = Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .id(sessionId)
                .expiration(expiryDate);

        if (additionalClaims != null) {
            additionalClaims.forEach(builder::claim);
        }

        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        return new TokenDetails(token, expiryDate, expirationSeconds);
    }

    public TokenDetails generateAccessToken(String username, String sessionId) {
        return doGenerateToken(username, ACCESS_TOKEN_VALIDITY_SEC, null, sessionId);
    }

    public TokenDetails generateAccessToken(String username, String[] roles, String sessionId) {
        java.util.Map<String, Object> claims = java.util.Map.of("roles", java.util.Arrays.asList(roles));
        return doGenerateToken(username, ACCESS_TOKEN_VALIDITY_SEC, claims, sessionId);
    }

    public TokenDetails generateRefreshToken(String username, String sessionId) {
        return doGenerateToken(username, REFRESH_TOKEN_VALIDITY_SEC, null, sessionId);
    }

}
