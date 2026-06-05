package net.fourletters.configuration.vk;

import com.vk.api.sdk.client.TransportClient;
import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.httpclient.HttpTransportClient;
import net.fourletters.configuration.ProxyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VkConfig {

    @Bean
    public VkApiClient vkApiClient() {
        ProxyResolver.ProxyEntry systemProxy = ProxyResolver.getSystemProxy();
        TransportClient transportClient = (systemProxy != null)?
                new VkHttpTransportClientWithProxy(systemProxy.host(), systemProxy.port()) :
                HttpTransportClient.getInstance();
        return new VkApiClient(transportClient);
    }
}
