package net.fourletters.server.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;



import net.fourletters.configuration.CommonSecurityConfigurator;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfig {


    private final CommonSecurityConfigurator commonSecConfigurator;

    public WebSecurityConfig(CommonSecurityConfigurator commonSecConfigurator) {
        this.commonSecConfigurator = commonSecConfigurator;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {

        return commonSecConfigurator
                .applyCommonDefaults(httpSecurity)
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/auth/user").authenticated()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/admin/**").hasAuthority("ADMIN")
                        .anyRequest().authenticated()
                )
                .build();
    }

}

