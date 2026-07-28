package net.fourletters.server.configuration.vk;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import net.fourletters.configuration.ProxyResolver;

@Configuration
public class VkConfig {

    public static final String VK_REST_TEMPLATE = "vkRestTemplate";

    private final String baseUrl;

    public VkConfig(
            @Value("${vk.baseUrl:https://id.vk.ru}") String baseUrl
    ) {
        this.baseUrl = baseUrl;
    }

    /**
     * RestTemplate for VK ID (OAuth 2.1) back-channel calls such as {@code /oauth2/public_info}.
     */
    @Bean
    @Qualifier(VK_REST_TEMPLATE)
    public RestTemplate vkRestTemplate(RestTemplateBuilder builder) {
        return builder
                .rootUri(baseUrl)
                .requestFactory(ProxyResolver::proxyAwareRequestFactory)
                .build();
    }
}
