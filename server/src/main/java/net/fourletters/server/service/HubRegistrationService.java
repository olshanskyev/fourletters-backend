package net.fourletters.server.service;

import net.fourletters.broker.RabbitMqTopology;
import net.fourletters.dto.HubRegistrationRequest;
import net.fourletters.dto.HubRegistrationResponse;
import net.fourletters.server.broker.ServerRabbitMqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Authenticates a registering Hub and provisions its relay queue.
 */
@Service
public class HubRegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(HubRegistrationService.class);

    private final ServerRabbitMqService rabbitMqService;
    private final String registrationSecret;

    public HubRegistrationService(ServerRabbitMqService rabbitMqService,
                                  @Value("${hub.registration.secret}") String registrationSecret) {
        this.rabbitMqService = rabbitMqService;
        this.registrationSecret = registrationSecret;
    }

    /** Constant-time comparison of the presented bearer token against the configured secret. */
    public boolean isValidToken(String token) {
        if (token == null || registrationSecret == null || registrationSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                registrationSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Assign a hubId, provision its auto-delete relay queue, and return the names the
     * Hub needs to bind user routing keys and consume.
     */
    public HubRegistrationResponse register(HubRegistrationRequest request) {
        String hubId = UUID.randomUUID().toString();
        String queueName = rabbitMqService.provisionHubQueue(hubId);

        String instanceId = request != null ? request.getInstanceId() : null;
        logger.info("Registered Hub {} (instance {}), provisioned queue {}", hubId, instanceId, queueName);

        HubRegistrationResponse response = new HubRegistrationResponse();
        response.setHubId(hubId);
        response.setQueueName(queueName);
        response.setExchange(RabbitMqTopology.MESSAGES_EXCHANGE);
        return response;
    }
}
