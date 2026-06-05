package net.fourletters.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {


    private final CommonSecurityConfigurator commonSecConfigurator;
    private final List<String> allowedOriginPatterns;

    public WebSecurityConfig(CommonSecurityConfigurator commonSecConfigurator,
                             @Value("${rest.cors.allowedOriginPatterns}") String allowedOriginPatterns) {
        this.commonSecConfigurator = commonSecConfigurator;
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {

        return commonSecConfigurator
                .applyCommonDefaults(httpSecurity, allowedOriginPatterns)
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/auth/user").authenticated()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/admin/**").hasAuthority("ADMIN")
                        .anyRequest().authenticated()
                )
                .build();
    }

}

