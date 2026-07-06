package net.fourletters.configuration;

import net.fourletters.token.JwtTokenCreator;
import net.fourletters.token.JwtTokenVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenAutoConfiguration {

    @Bean
    public JwtTokenVerifier jwtTokenVerifier(JwtProperties jwtProperties, RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder.requestFactory(ProxyResolver::proxyAwareRequestFactory).build();
        return new JwtTokenVerifier(jwtProperties, restTemplate);
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