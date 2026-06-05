package net.fourletters.token;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenCreatorAndVerifierTest {

    private JwtTokenCreator creator;
    private JwtTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(
                "fourletters-test",
                "classpath:certs/test_public_key.pem",
                "classpath:certs/test_private_key.pem",
                3600,
                604800
        );
        creator = new JwtTokenCreator(properties);
        verifier = new JwtTokenVerifier(properties);
    }

    @Test
    void testGenerateAndVerifyAccessToken() {
        String username = "testuser";
        String sessionId = "session-123";
        String[] roles = {"USER", "ADMIN"};

        JwtTokenCreator.TokenDetails tokenDetails = creator.generateAccessToken(username, roles, sessionId);
        assertNotNull(tokenDetails.token());
        assertEquals(3600, tokenDetails.validitySeconds());

        Optional<Claims> claimsOpt = verifier.parseClaims(tokenDetails.token());
        assertTrue(claimsOpt.isPresent());

        Claims claims = claimsOpt.orElseThrow();
        assertEquals(username, claims.getSubject());
        assertEquals("fourletters-test", claims.getIssuer());
        assertEquals(sessionId, claims.getId());

        java.util.List<String> parsedRoles = JwtTokenVerifier.getRolesFromClaims(claims);
        assertEquals(2, parsedRoles.size());
        assertTrue(parsedRoles.contains("USER"));
        assertTrue(parsedRoles.contains("ADMIN"));
    }

    @Test
    void testGenerateAndVerifyRefreshToken() {
        String username = "testuser";
        String sessionId = "session-456";

        JwtTokenCreator.TokenDetails tokenDetails = creator.generateRefreshToken(username, sessionId);
        assertNotNull(tokenDetails.token());
        assertEquals(604800, tokenDetails.validitySeconds());

        Optional<Claims> claimsOpt = verifier.parseClaims(tokenDetails.token());
        assertTrue(claimsOpt.isPresent());

        Claims claims = claimsOpt.orElseThrow();
        assertEquals(username, claims.getSubject());
        assertEquals(sessionId, claims.getId());
    }

    @Test
    void testGetTokenFromBearerString() {
        String bearer = "Bearer my.jwt.token";
        String token = JwtTokenVerifier.getTokenFromBearerString(bearer);
        assertEquals("my.jwt.token", token);

        assertNull(JwtTokenVerifier.getTokenFromBearerString("Invalid my.jwt.token"));
        assertNull(JwtTokenVerifier.getTokenFromBearerString(null));
    }

    @Test
    void testInvalidToken() {
        Optional<Claims> claimsOpt = verifier.parseClaims("invalid.token.string");
        assertFalse(claimsOpt.isPresent());

        Optional<Claims> claimsIgnoreExp = verifier.parseClaimsIgnoreExpiration("invalid.token.string");
        assertFalse(claimsIgnoreExp.isPresent());
    }

    @Test
    void testExpiredToken() {
        JwtProperties expiredProps = new JwtProperties(
                "fourletters-test",
                "classpath:certs/test_public_key.pem",
                "classpath:certs/test_private_key.pem",
                -10, // negative validity ensures immediate expiration
                -10
        );
        JwtTokenCreator expiredCreator = new JwtTokenCreator(expiredProps);
        JwtTokenCreator.TokenDetails expiredTokenDetails = expiredCreator.generateAccessToken("testuser", new String[]{"USER"}, "session-expired");

        Optional<Claims> strictClaimsOpt = verifier.parseClaims(expiredTokenDetails.token());
        assertFalse(strictClaimsOpt.isPresent(), "Strict claims parsing should reject expired tokens");

        Optional<Claims> ignoredExpClaimsOpt = verifier.parseClaimsIgnoreExpiration(expiredTokenDetails.token());
        assertTrue(ignoredExpClaimsOpt.isPresent(), "parseClaimsIgnoreExpiration should extract claims from expired tokens");

        Claims extractedClaims = ignoredExpClaimsOpt.orElseThrow();
        assertEquals("testuser", extractedClaims.getSubject());
        assertEquals("session-expired", extractedClaims.getId());
    }
}
