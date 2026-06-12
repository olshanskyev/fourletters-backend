package net.fourletters.token;

import io.jsonwebtoken.Claims;
import net.fourletters.configuration.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenCreatorAndVerifierTest {

    private JwtTokenCreator creator;
    private JwtTokenVerifier verifier;

    @BeforeEach
    public void setUp() throws Exception {
        // Initialize properties
        JwtProperties properties = new JwtProperties(
                "fourletters-test",
                "classpath:certs/test_public_key.pem",
                null,
                "classpath:certs/test_private_key.pem",
                3600,
                604800
        );

        // Initialize verifier (null for RestTemplate since we use file path)
        verifier = new JwtTokenVerifier(properties, null);

        // Initialize creator
        creator = new JwtTokenCreator(properties);
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
                null,
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

    @Test
    void testLoadPublicKeyFromUrl() throws Exception {
        // 1. Prepare JWKS JSON mapping to our test public key
        PublicKey pubKey = KeyLoader.loadPublicKey("classpath:certs/test_public_key.pem");
        RSAPublicKey rsaPubKey = (RSAPublicKey) pubKey;

        // Remove leading zero sign-byte typically added by BigInteger two's complement representation
        byte[] nBytes = rsaPubKey.getModulus().toByteArray();
        if (nBytes[0] == 0) {
            byte[] tmp = new byte[nBytes.length - 1];
            System.arraycopy(nBytes, 1, tmp, 0, tmp.length);
            nBytes = tmp;
        }

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String nStr = encoder.encodeToString(nBytes);
        String eStr = encoder.encodeToString(rsaPubKey.getPublicExponent().toByteArray());

        String jwksJson = "{\"keys\":[{\"kty\":\"RSA\",\"n\":\"" + nStr + "\",\"e\":\"" + eStr + "\"}]}";

        // 2. Mock RestTemplate to simulate HTTP response
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        Mockito.when(restTemplate.getForObject("http://localhost/jwks", String.class)).thenReturn(jwksJson);

        // 3. Configure properties with URL only, path is left blank
        JwtProperties urlProps = new JwtProperties(
                "fourletters-test",
                "",
                "http://localhost/jwks",
                "classpath:certs/test_private_key.pem",
                3600,
                604800
        );

        // 4. Initialize verifier and test
        JwtTokenVerifier urlVerifier = new JwtTokenVerifier(urlProps, restTemplate);

        // Create a valid token using the existing file-backed creator
        JwtTokenCreator.TokenDetails tokenDetails = creator.generateAccessToken("urltestuser", new String[]{"USER"}, "session-url");

        // The verifier should lazily try to fetch the key from the mocked RestTemplate
        Optional<Claims> claimsOpt = urlVerifier.parseClaims(tokenDetails.token());

        assertTrue(claimsOpt.isPresent());
        assertEquals("urltestuser", claimsOpt.get().getSubject());

        // Confirm the RestTemplate was indeed called
        Mockito.verify(restTemplate).getForObject("http://localhost/jwks", String.class);
    }
}
