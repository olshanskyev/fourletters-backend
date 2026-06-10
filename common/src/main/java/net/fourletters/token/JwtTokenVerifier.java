package net.fourletters.token;

import java.security.PublicKey;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;


public class JwtTokenVerifier {
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenVerifier.class);
    private final String issuer;
    private final JwtProperties jwtProperties;
    private PublicKey publicKey;

    public JwtTokenVerifier(
            JwtProperties jwtProperties
    ) {
        this.issuer = jwtProperties.getIssuer();
        this.jwtProperties = jwtProperties;

        String publicKeyPath = jwtProperties.getPublicKeyPath();
        if ((publicKeyPath == null || publicKeyPath.isBlank()) &&
            (jwtProperties.getPublicKeyUrl() == null || jwtProperties.getPublicKeyUrl().isBlank())) {
            throw new IllegalStateException("Either rest.jwt.publicKeyPath or rest.jwt.publicKeyUrl must be configured for JWT verification");
        }

        // Eager load if local file is provided, otherwise deferred to lazy load.
        if (publicKeyPath != null && !publicKeyPath.isBlank()) {
            try {
                this.publicKey = KeyLoader.loadPublicKey(publicKeyPath);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load public key for JWT from path", ex);
            }
        }
    }

    private synchronized PublicKey getPublicKey() {
        if (this.publicKey == null) {
            String url = jwtProperties.getPublicKeyUrl();
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("Neither public key path nor URL is configured.");
            }
            try {
                this.publicKey = KeyLoader.loadPublicKeyFromUrl(url);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load public key from URL", ex);
            }
        }
        return this.publicKey;
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
                .verifyWith(getPublicKey())
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
