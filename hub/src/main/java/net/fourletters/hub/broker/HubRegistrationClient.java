package net.fourletters.hub.broker;

import net.fourletters.dto.HubRegistrationRequest;
import net.fourletters.dto.HubRegistrationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import net.fourletters.configuration.ProxyResolver;

/**
 * Calls the Server's {@code POST /hubs/register} on boot to obtain this Hub's relay
 * queue. Authenticates with the registration bearer token from config
 */
@Component
public class HubRegistrationClient {

    private final RestTemplate restTemplate;
    private final String registerUrl;
    private final String registrationToken;
    private final String instanceId = UUID.randomUUID().toString();

    public HubRegistrationClient(RestTemplateBuilder builder,
                                 @Value("${hub.registration.url}") String registerUrl,
                                 @Value("${hub.registration.token}") String registrationToken) {
        this.restTemplate = builder.requestFactory(ProxyResolver::proxyAwareRequestFactory).build();
        this.registerUrl = registerUrl;
        this.registrationToken = registrationToken;
    }

    public HubRegistrationResponse register() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(registrationToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HubRegistrationRequest body = new HubRegistrationRequest();
        body.setInstanceId(instanceId);

        ResponseEntity<HubRegistrationResponse> response = restTemplate.postForEntity(
                registerUrl, new HttpEntity<>(body, headers), HubRegistrationResponse.class);

        HubRegistrationResponse registration = response.getBody();
        if (registration == null) {
            throw new IllegalStateException("Hub registration returned no queue name");
        }
        return registration;
    }
}
