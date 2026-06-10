package net.fourletters.token;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rest.jwt")
public class JwtProperties {
    private final String issuer;
    private final String publicKeyPath;
    private final String publicKeyUrl; // Optional URL to fetch public key, can be used instead of file path
    private final String privateKeyPath;
    private final long accessTokenValiditySec;
    private final long refreshTokenValiditySec;

    public JwtProperties(
            @DefaultValue("fourletters") String issuer,
            String publicKeyPath,
            String publicKeyUrl,
            String privateKeyPath,
            @DefaultValue("3600") long accessTokenValiditySec,
            @DefaultValue("604800") long refreshTokenValiditySec
    ) {
        this.issuer = issuer;
        this.publicKeyPath = publicKeyPath;
        this.publicKeyUrl = publicKeyUrl;
        this.privateKeyPath = privateKeyPath;
        this.accessTokenValiditySec = accessTokenValiditySec;
        this.refreshTokenValiditySec = refreshTokenValiditySec;
    }

    public String getIssuer() { return issuer; }
    public String getPublicKeyPath() { return publicKeyPath; }
    public String getPublicKeyUrl() { return publicKeyUrl; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public long getAccessTokenValiditySec() { return accessTokenValiditySec; }
    public long getRefreshTokenValiditySec() { return refreshTokenValiditySec; }
}