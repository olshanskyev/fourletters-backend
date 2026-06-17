package net.fourletters.hub.controller;

import net.fourletters.dto.HubRegistrationResponse;
import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.broker.HubRegistrationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drives the Hub registration handshake once the context is ready: registers with the
 * Server, records the provisioned queue, and starts the live relay consumer.
 */
@Component
public class HubBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(HubBootstrap.class);

    private final HubRegistrationClient registrationClient;
    private final HubRabbitMqService rabbitMqService;
    private final HubWebSocketHandler webSocketHandler;

    public HubBootstrap(HubRegistrationClient registrationClient,
                        HubRabbitMqService rabbitMqService,
                        HubWebSocketHandler webSocketHandler) {
        this.registrationClient = registrationClient;
        this.rabbitMqService = rabbitMqService;
        this.webSocketHandler = webSocketHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAndStart() {
        HubRegistrationResponse registration = registrationClient.register();
        rabbitMqService.useProvisionedQueue(registration.getQueueName());
        webSocketHandler.startConsuming(registration.getQueueName());
        logger.info("Hub registered as {} on exchange {}", registration.getHubId(), registration.getExchange());
    }
}
