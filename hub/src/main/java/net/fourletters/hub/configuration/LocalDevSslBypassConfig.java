package net.fourletters.hub.configuration;

import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.NonNull;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Configuration
@Profile("local")
public class LocalDevSslBypassConfig {

    @Bean
    public RestTemplateCustomizer localSslBypassCustomizer() {
        return restTemplate -> {
            try {
                // Create a trust manager that does not validate certificate chains
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };

                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new SecureRandom());

                // Inject it into a custom request factory
                SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(@NonNull HttpURLConnection connection, @NonNull String httpMethod) throws IOException {
                        if (connection instanceof HttpsURLConnection httpsConnection) {
                            httpsConnection.setSSLSocketFactory(sslContext.getSocketFactory());
                            httpsConnection.setHostnameVerifier((hostname, session) -> true);
                        }
                        super.prepareConnection(connection, httpMethod);
                    }
                };

                restTemplate.setRequestFactory(requestFactory);

            } catch (Exception e) {
                throw new IllegalStateException("Failed to customize RestTemplate for local SSL bypass", e);
            }
        };
    }
}

