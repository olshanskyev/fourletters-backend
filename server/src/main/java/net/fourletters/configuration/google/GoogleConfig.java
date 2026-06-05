package net.fourletters.configuration.google;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import net.fourletters.configuration.ProxyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;

@Configuration
public class GoogleConfig {

    private final String clientId;

    public GoogleConfig(
            @Value("${google.clientId:}") String clientId
    ) {
        this.clientId = clientId;
    }
    @Bean
    public GoogleIdTokenVerifier tokenVerifier() {
        ProxyResolver.ProxyEntry systemProxy = ProxyResolver.getSystemProxy();
        HttpTransport transport = (systemProxy!= null)?
                new NetHttpTransport.Builder()
                        .setProxy(new Proxy(
                                Proxy.Type.HTTP,
                                new InetSocketAddress(systemProxy.host(),systemProxy.port()))
                        )
                        .build() :
                new NetHttpTransport();

        return new GoogleIdTokenVerifier.Builder(
                transport,
                new GsonFactory()
        )
        .setAudience(Collections.singletonList(clientId))
        .build();
    }
}
