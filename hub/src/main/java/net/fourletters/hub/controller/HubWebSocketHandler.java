package net.fourletters.hub.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.fourletters.dto.AckMessagePayload;
import net.fourletters.dto.EncryptedMessage;
import net.fourletters.dto.SendMessagePayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import net.fourletters.hub.broker.HubRabbitMqService;
import jakarta.annotation.PreDestroy;

@Component
public class HubWebSocketHandler extends TextWebSocketHandler implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(HubWebSocketHandler.class);
    private final ObjectMapper objectMapper;
    private final HubRabbitMqService rabbitMqService;
    private final SimpleMessageListenerContainer container;

    // Track active sessions by User ID (Subject from token)
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public HubWebSocketHandler(ObjectMapper objectMapper, HubRabbitMqService rabbitMqService, ConnectionFactory connectionFactory) {
        this.objectMapper = objectMapper;
        this.rabbitMqService = rabbitMqService;

        // Listen to the specific RabbitMQ queue for this Hub instance
        this.container = new SimpleMessageListenerContainer(connectionFactory);
        this.container.setQueueNames(rabbitMqService.getHubInstanceQueueName());
        this.container.setMessageListener(this);
        this.container.start();
    }

    @PreDestroy
    public void onDestroy() {
        if (this.container != null) {
            this.container.stop();
            this.container.destroy();
        }
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        if (session.getPrincipal() != null) {
            String userId = session.getPrincipal().getName();
            sessions.put(userId, session);
            logger.info("Session connected for user: {}", userId);

            rabbitMqService.bindUserToHubQueue(userId);
        }
    }

    private void handlePayloadMessage(String senderIdStr, JsonNode rootNode) throws JsonProcessingException {

        JsonNode dataNode = rootNode.get(SendMessagePayload.JSON_PROPERTY_DATA);
        if (dataNode != null && dataNode.isObject()) {
            // Inject senderId
            ((ObjectNode) dataNode).put(EncryptedMessage.JSON_PROPERTY_SENDER_ID, senderIdStr);
            String recipientIdStr = dataNode.path(EncryptedMessage.JSON_PROPERTY_RECIPIENT_ID).asText();

            // Convert manipulated tree back to JSON string directly
            String updatedPayload = objectMapper.writeValueAsString(rootNode);
            rabbitMqService.sendMessage(recipientIdStr, updatedPayload);
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        if (session.getPrincipal() == null) {
            logger.error("Received SendMessage from unauthenticated session");
            return;
        }
        String senderIdStr = session.getPrincipal().getName();
        try {
            UUID.fromString(senderIdStr);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid UUID from principal: {}", senderIdStr, e);
            return;
        }

        try {
            String payload = message.getPayload();
            JsonNode rootNode = objectMapper.readTree(payload);

            if (rootNode.has(SendMessagePayload.JSON_PROPERTY_ACTION)) {
                String action = rootNode.get(SendMessagePayload.JSON_PROPERTY_ACTION).asText();
                if (AckMessagePayload.ActionEnum.ACK_MESSAGE.getValue().equals(action)) {
                    System.out.println("Received AckMessage");
                    // Handle AckMessagePayload
                } else if (SendMessagePayload.ActionEnum.SEND_MESSAGE.getValue().equals(action)) {
                    handlePayloadMessage(senderIdStr, rootNode);
                } else {
                    logger.debug("Unknown action: {}", action);
                }
            } else {
                logger.debug("Message without action: {}", payload);
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse message: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        if (session.getPrincipal() != null) {
            String userId = session.getPrincipal().getName();
            sessions.remove(userId, session);
            logger.info("Session closed for user: {}", userId);

            try {
                rabbitMqService.unbindUserFromHubQueue(userId);
            } catch (org.springframework.amqp.AmqpApplicationContextClosedException e) {
                logger.debug("Application context is closed. Skipping RabbitMQ unbind for user: {}", userId);
            }
        }
        super.afterConnectionClosed(session, status);
    }

    /**
     * Receives messages from RabbitMQ and forwards them to the open WebSocket connection.
     */
    @Override
    public void onMessage(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (routingKey != null && routingKey.startsWith(HubRabbitMqService.ROUTING_KEY_PREFIX)) {
            String userId = routingKey.substring(5);
            WebSocketSession session = sessions.get(userId);

            if (session != null && session.isOpen()) {
                try {
                    String payload = new String(message.getBody());
                    session.sendMessage(new TextMessage(payload));
                    logger.debug("Sent message to user {}: {}", userId, payload);
                } catch (IOException e) {
                    logger.error("Error sending message to client: {}", userId, e);
                }
            } else {
                logger.warn("Received message for user {}, but connection is closed or not found", userId);
            }
        }
    }
}
