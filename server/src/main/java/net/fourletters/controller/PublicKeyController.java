package net.fourletters.controller;

import net.fourletters.token.JwtProperties;
import net.fourletters.token.KeyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class PublicKeyController {
    private static final Logger logger = LoggerFactory.getLogger(PublicKeyController.class);
    private final JwtProperties jwtProperties;

    public PublicKeyController(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @GetMapping(value = "/publicKey", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> publicKey() {
        try {
            String publicKeyPath = jwtProperties.getPublicKeyPath();
            if (publicKeyPath == null || publicKeyPath.isBlank()) {
                logger.error("Public key path is not configured in application.yml");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("Public key configuration is missing on the server.");
            }
            String pem = KeyLoader.loadPublicKeyPemString(publicKeyPath);
            return ResponseEntity.ok(pem);

        } catch (java.io.FileNotFoundException | java.nio.file.NoSuchFileException ex) {
            logger.error("JWT Public key file not found: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Public key file could not be found on the server.");

        } catch (Exception ex) {
            logger.error("Unexpected error while reading public key", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred while retrieving the public key.");
        }
    }
}

