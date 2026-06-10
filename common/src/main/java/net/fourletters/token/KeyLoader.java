package net.fourletters.token;

import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class KeyLoader {

    private static final ResourceLoader resourceLoader = new DefaultResourceLoader();

    static PrivateKey loadPrivateKey(String location) throws Exception {
        String pem = readResourceToString(location);
        String base64 = cleanPemContent(pem);

        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    public static PublicKey loadPublicKey(String location) throws Exception {
        String pem = readResourceToString(location);
        String base64 = cleanPemContent(pem);

        byte[] der = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    public static PublicKey loadPublicKeyFromUrl(String urlString) throws Exception {
        // Here we parse the JWKS JSON string obtained from the server
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = new URI(urlString).toURL().openStream()) {
            JsonNode jwksNode = mapper.readTree(is);
            JsonNode keysNode = jwksNode.get("keys");
            if (keysNode == null || !keysNode.isArray() || keysNode.isEmpty()) {
                throw new IllegalStateException("Invalid JWKS: No keys found.");
            }
            JsonNode keyNode = keysNode.get(0); // We take the first key for simplicity

            String nStr = keyNode.get("n").asText();
            String eStr = keyNode.get("e").asText();

            byte[] nBytes = Base64.getUrlDecoder().decode(nStr);
            byte[] eBytes = Base64.getUrlDecoder().decode(eStr);

            BigInteger modulus = new BigInteger(1, nBytes);
            BigInteger exponent = new BigInteger(1, eBytes);

            RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        }
    }

    private static String readResourceToString(String location) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream inputStream = resource.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String cleanPemContent(String pem) {
        return pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s+", "");
    }
}
