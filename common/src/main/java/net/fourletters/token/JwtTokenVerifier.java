package net.fourletters.token;

import io.jsonwebtoken.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.util.List;
import java.util.Optional;


public class JwtTokenVerifier {
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenVerifier.class);
    private final String issuer;
    private final PublicKey publicKey;

    public JwtTokenVerifier(
            JwtProperties jwtProperties
    ) {
        this.issuer = jwtProperties.getIssuer();
        String publicKeyPath = jwtProperties.getPublicKeyPath();
        if (publicKeyPath == null || publicKeyPath.isBlank()) {
            throw new IllegalStateException("rest.jwt.publicKeyPath must be configured for JWT verification");
        }
        try {
            this.publicKey = KeyLoader.loadPublicKey(publicKeyPath);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load public key for JWT", ex);
        }
    }

    /**
     * Try to parse claims from token and return Optional.empty() if parsing/validation fails.
     */
    public Optional<Claims> parseClaims(String token) {
        try {
            return Optional.of(verifyAndGetPayload(token));
        } catch (ExpiredJwtException ex) {
            logger.debug("Token expired: {}", ex.getMessage());
            return Optional.empty(); // Standard requests reject expired tokens
        } catch (JwtException | IllegalArgumentException ex) {
            logger.debug("Token validation failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Try to parse claims from token but ignore expiration
     */
    public Optional<Claims> parseClaimsIgnoreExpiration(String token) {
        try {
            return Optional.of(verifyAndGetPayload(token));
        } catch (ExpiredJwtException ex) {
            logger.debug("Token expired but returning claims: {}", ex.getMessage());
            return Optional.of(ex.getClaims()); // Securely extract from exception
        } catch (JwtException | IllegalArgumentException ex) {
            logger.debug("Token validation failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // Single, readable source of truth for parsing
    private Claims verifyAndGetPayload(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extract roles list from a Claims instance safely.
     */
    public static List<String> getRolesFromClaims(Claims claims) {
        if (claims == null) return java.util.List.of();
        Object rolesObj = claims.get("roles");
        if (rolesObj == null) return java.util.List.of();
        if (rolesObj instanceof List<?> raw) {
            return raw.stream().map(Object::toString).collect(java.util.stream.Collectors.toList());
        }
        return java.util.List.of(rolesObj.toString());
    }

    /**
     *
     * @param bearerString Bearer <tokenInBase64>
     * @return null if bearer string not found
     */
    public static String getTokenFromBearerString(String bearerString) {
        if (bearerString != null && bearerString.startsWith("Bearer ")) {
            return bearerString.substring(7);
        }
        return null;
    }
}
