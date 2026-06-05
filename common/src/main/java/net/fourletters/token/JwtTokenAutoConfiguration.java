package net.fourletters.token;

import net.fourletters.configuration.JwtRequestFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenAutoConfiguration {

    @Bean
    public JwtTokenVerifier jwtTokenVerifier(JwtProperties jwtProperties) {
        return new JwtTokenVerifier(jwtProperties);
    }

    @Bean
    public JwtRequestFilter jwtRequestFilter(JwtTokenVerifier jwtTokenVerifier) {
        return new JwtRequestFilter(jwtTokenVerifier);
    }

    // Instantiates the Creator ONLY on the Auth service where the private key path is provided
    @Bean
    @ConditionalOnProperty(name = "rest.jwt.privateKeyPath")
    public JwtTokenCreator jwtTokenCreator(JwtProperties jwtProperties) {
        return new JwtTokenCreator(jwtProperties);
    }
}