package net.fourletters.hub.configuration;

import net.fourletters.hub.controller.HubWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import net.fourletters.configuration.CommonSecurityConfigurator;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableWebSocket
public class WebSecurityConfig implements WebSocketConfigurer {

    /** Cap on every client -> Hub frame; sized for the largest one, a 64 KiB call_signal (container default 8 KiB). */
    private static final int MAX_INBOUND_FRAME_BYTES = 72 * 1024;

    private final CommonSecurityConfigurator commonSecConfigurator;
    private final HubWebSocketHandler hubWebSocketHandler;

    public WebSecurityConfig(CommonSecurityConfigurator commonSecConfigurator, HubWebSocketHandler hubWebSocketHandler) {
        this.commonSecConfigurator = commonSecConfigurator;
        this.hubWebSocketHandler = hubWebSocketHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {

        return commonSecConfigurator
                .applyCommonDefaults(httpSecurity)
                .authorizeHttpRequests(authz -> authz
                        .anyRequest().authenticated()
                )
                .build();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hubWebSocketHandler, "/ws")
                .setAllowedOriginPatterns("*"); // Spring WebSocket does its own Origin check; we bypass it here and rely on CommonSecurityConfigurator
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_INBOUND_FRAME_BYTES);
        return container;
    }
}
