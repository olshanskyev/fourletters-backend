package net.fourletters.hub.call;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.dto.CallSignalEvent;
import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.session.HubSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Blind relay for ephemeral 1:1 call signals over {@code calls.exchange}. The payload is
 * E2E-encrypted and never inspected or stored; the sender id is always the authenticated session's.
 */
@Service
public class CallSignalService {

    private static final Logger logger = LoggerFactory.getLogger(CallSignalService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Upper bound on an encrypted call-signal payload (matches CallSignalCommand.payload maxLength). */
    static final int MAX_PAYLOAD_LENGTH = 64 * 1024;

    private final HubRabbitMqService rabbitMqService;
    private final HubSessionRegistry registry;

    public CallSignalService(HubRabbitMqService rabbitMqService, HubSessionRegistry registry) {
        this.rabbitMqService = rabbitMqService;
        this.registry = registry;
    }

    /** A local client sent a {@code call_signal}: stamp the sender and publish it to the recipient's Hub. */
    public void relay(String senderId, String recipientId, String payload) {
        UUID recipient = parseUuid(recipientId);
        if (recipient == null || payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD_LENGTH) {
            logger.debug("Dropping invalid call_signal from {}", senderId);
            return;
        }
        CallSignalEvent event = new CallSignalEvent()
                .type(CallSignalEvent.TypeEnum.CALL_SIGNAL)
                .senderId(UUID.fromString(senderId))
                .payload(payload);
        rabbitMqService.publishCallSignal(recipient.toString(), toJson(event));
    }

    /** A {@code call.{id}} delivery: forward the frame verbatim to that user's local session. */
    public void onDelivery(String recipientId, String body) {
        registry.sendToUser(recipientId, body);
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String toJson(CallSignalEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize call signal frame", e);
        }
    }
}
