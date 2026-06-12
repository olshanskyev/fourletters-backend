package net.fourletters.server.controller;

import net.fourletters.configuration.JwtProperties;
import net.fourletters.token.KeyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class PublicKeyController {
    private static final Logger logger = LoggerFactory.getLogger(PublicKeyController.class);
    private final JwtProperties jwtProperties;

    public PublicKeyController(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @GetMapping(value = "/jwks", produces = "application/json")
    public ResponseEntity<Map<String, Object>> jwks() {
        try {
            String publicKeyPath = jwtProperties.getPublicKeyPath();
            if (publicKeyPath == null || publicKeyPath.isBlank()) {
                logger.error("Public key path is not configured in application.yml");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }

            RSAPublicKey rsaPublicKey = (RSAPublicKey) KeyLoader.loadPublicKey(publicKeyPath);

            Map<String, Object> jwk = Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "n", encodeBase64Url(rsaPublicKey.getModulus()),
                "e", encodeBase64Url(rsaPublicKey.getPublicExponent())
            );

            return ResponseEntity.ok(Map.of("keys", List.of(jwk)));

        } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException ex) {
            logger.error("JWT Public key file not found: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        } catch (Exception ex) {
            logger.error("Unexpected error while reading public key", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String encodeBase64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] withoutContext = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, withoutContext, 0, withoutContext.length);
            bytes = withoutContext;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

