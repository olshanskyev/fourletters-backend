package net.fourletters.hub.controller;

import net.fourletters.broker.AbstractRabbitMqConfig;
import net.fourletters.dto.*;
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
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.AcknowledgeMode;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import net.fourletters.hub.broker.HubRabbitMqService;
import jakarta.annotation.PreDestroy;

import java.util.Timer;
import java.util.TimerTask;

@Component
public class HubWebSocketHandler extends TextWebSocketHandler implements ChannelAwareMessageListener {
    private static final Logger logger = LoggerFactory.getLogger(HubWebSocketHandler.class);
    private final ObjectMapper objectMapper;
    private final HubRabbitMqService rabbitMqService;
    private final SimpleMessageListenerContainer container;

    // Track active sessions by User ID (Subject from token)
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // Track unacked messages by Message ID -> Delivery Tag
    private final Map<String, Long> unackedMessages = new ConcurrentHashMap<>();
    // Track Delivery Tag -> Channel (since we need the channel to ack/nack)
    private final Map<Long, Channel> deliveryChannels = new ConcurrentHashMap<>();
    // Track timeouts for unacked messages
    private final Timer ackTimeoutTimer = new Timer(true);

    public HubWebSocketHandler(ObjectMapper objectMapper, HubRabbitMqService rabbitMqService, ConnectionFactory connectionFactory) {
        this.objectMapper = objectMapper;
        this.rabbitMqService = rabbitMqService;

        // Listen to the specific RabbitMQ queue for this Hub instance
        this.container = new SimpleMessageListenerContainer(connectionFactory);
        this.container.setQueueNames(rabbitMqService.getHubInstanceQueueName());
        this.container.setMessageListener(this);
        this.container.setAcknowledgeMode(AcknowledgeMode.MANUAL); // Manually handle ACKs
        this.container.start();
    }

    @PreDestroy
    public void onDestroy() {
        this.ackTimeoutTimer.cancel();
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

            // Ensure their fallback queue physically exists
            rabbitMqService.ensureUserHoldingQueueExists(userId);

            // Connect to real-time streams
            rabbitMqService.bindUserToHubQueue(userId);

            // Start officially consuming any pending messages directly out of the user's specific holding queue!
            // The exact same `onMessage` code below will flawlessly catch them!
            container.addQueueNames(AbstractRabbitMqConfig.HOLDING_QUEUE_PREFIX + userId);
        }
    }

    private void handleClientSendMessage(String senderIdStr, String rawPayload, WebSocketSession session) {
        try {
            // Map JSON to the literal OpenAPI DTO
            SendMessagePayload sendPayload = objectMapper.readValue(rawPayload, SendMessagePayload.class);
            EncryptedMessage data = sendPayload.getData();

            // Securely set the sender identity
            data.setSenderId(UUID.fromString(senderIdStr));

            // Map Action to Event DTO for the RabbitMQ Bus BEFORE sending it over the network
            ReceiveMessagePayload receiveEvent = new ReceiveMessagePayload();
            receiveEvent.setEvent(ReceiveMessagePayload.EventEnum.MESSAGE_RECEIVED);
            receiveEvent.setData(data);

            String mqPayload = objectMapper.writeValueAsString(receiveEvent);
            rabbitMqService.sendMessage(data.getRecipientId().toString(), mqPayload);

            // Send `messageDispatched` event back to the original sender
            EventMessageReceipt receiptData = new EventMessageReceipt();
            receiptData.setMessageId(data.getMessageId());

            MessageDispatchedPayload dispatchEvent = new MessageDispatchedPayload();
            dispatchEvent.setEvent(MessageDispatchedPayload.EventEnum.MESSAGE_DISPATCHED);
            dispatchEvent.setData(receiptData);

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(dispatchEvent)));
        } catch (Exception e) {
            logger.error("Failed to process sendMessage payload", e);
        }
    }

    private void handleClientAck(String rawPayload) {
        try {
            // Map JSON to the literal OpenAPI DTO
            AckMessagePayload ackPayload = objectMapper.readValue(rawPayload, AckMessagePayload.class);
            ClientMessageAck ackData = ackPayload.getData();
            String messageIdStr = ackData.getMessageId().toString();

            // Settle the manual RabbitMQ Lock
            Long deliveryTag = unackedMessages.remove(messageIdStr);
            if (deliveryTag != null) {
                Channel channel = deliveryChannels.remove(deliveryTag);
                if (channel != null && channel.isOpen()) {
                    try {
                        channel.basicAck(deliveryTag, false);
                        logger.debug("Successfully acknowledged message {} with tag {}", messageIdStr, deliveryTag);
                    } catch (IOException ex) {
                        logger.error("Failed to ACK message {} with tag {}", messageIdStr, deliveryTag, ex);
                    }
                }
            } else {
                logger.warn("Received ACK for unknown or already processed messageId: {}", messageIdStr);
            }

            // Forward the Read Receipt (Event) to the original sender via RabbitMQ
            EventMessageReceipt receiptData = new EventMessageReceipt();
            receiptData.setMessageId(ackData.getMessageId());

            MessageReadPayload readEvent = new MessageReadPayload();
            readEvent.setEvent(MessageReadPayload.EventEnum.MESSAGE_READ);
            readEvent.setData(receiptData);

            String mqPayload = objectMapper.writeValueAsString(readEvent);
            rabbitMqService.sendMessage(ackData.getSenderId().toString(), mqPayload);
        } catch (Exception e) {
            logger.error("Failed to process ackMessage payload", e);
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
            String payload = message.getPayload();
            // We just peek at the JSON to route it to the correct strongly-typed DTO handler
            JsonNode rootNode = objectMapper.readTree(payload);

            if (rootNode.has("action")) {
                String action = rootNode.get("action").asText();
                if (AckMessagePayload.ActionEnum.ACK_MESSAGE.getValue().equals(action)) {
                    handleClientAck(payload);
                } else if (SendMessagePayload.ActionEnum.SEND_MESSAGE.getValue().equals(action)) {
                    handleClientSendMessage(senderIdStr, payload, session);
                } else {
                    logger.debug("Unknown action: {}", action);
                }
            } else {
                logger.debug("Message without action: {}", payload);
            }
        } catch (Exception e) {
            logger.error("Failed to parse incoming WS text message", e);
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        if (session.getPrincipal() != null) {
            String userId = session.getPrincipal().getName();
            sessions.remove(userId, session);
            logger.info("Session closed for user: {}", userId);

            // Important: if the user disconnects without ACKing messages, NACK them so they hit the Data Store
            unackedMessages.forEach((messageId, deliveryTag) -> {
                Channel channel = deliveryChannels.remove(deliveryTag);
                if (channel != null && channel.isOpen()) {
                    try {
                        logger.info("NACKing unacknowledged message {} to Dead Letter Exchange", messageId);
                        channel.basicNack(deliveryTag, false, false);
                    } catch (IOException e) {
                        logger.error("Failed to NACK unacknowledged message {}", messageId, e);
                    }
                }
            });
            // Clear unacked messages specifically tied to this user logically (we clear all here for safety in a full sweep, but ideally indexed by user)
            unackedMessages.clear();

            try {
                rabbitMqService.unbindUserFromHubQueue(userId);
                container.removeQueueNames(AbstractRabbitMqConfig.HOLDING_QUEUE_PREFIX + userId);
            } catch (org.springframework.amqp.AmqpApplicationContextClosedException e) {
                logger.debug("Application context is closed. Skipping RabbitMQ unbind for user: {}", userId);
            }
        }
        super.afterConnectionClosed(session, status);
    }

    private void setAckTimeoutTimer(String messageId) {
        ackTimeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Long tag = unackedMessages.remove(messageId);
                if (tag != null) {
                    Channel ch = deliveryChannels.remove(tag);
                    if (ch != null && ch.isOpen()) {
                        try {
                            logger.warn("ACK timeout reached for message {}. NACKing to Store-and-Forward DLQ", messageId);
                            ch.basicNack(tag, false, false);
                        } catch (IOException ex) {
                            logger.error("Failed to NACK unacknowledged message {} after timeout", messageId, ex);
                        }
                    }
                }
            }
        }, 10000); // Wait exactly 10 seconds for standard client ACK
    }

    private void processIncomingRabbitMessage(Message message, Channel channel, String userId, WebSocketSession session) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            String payload = new String(message.getBody());
            // Peek at the structured event that was created by the publishing Hub
            JsonNode rootNode = objectMapper.readTree(payload);
            String event = rootNode.path("event").asText();

            if (MessageReadPayload.EventEnum.MESSAGE_READ.getValue().equals(event)) {
                // We don't need the client to ACK the ACK!
                channel.basicAck(deliveryTag, false);
            } else {
                // It's a standard payload event requiring confirmation. Hook up the timer.
                String messageId = rootNode
                        .path(ReceiveMessagePayload.JSON_PROPERTY_DATA)
                        .path(EncryptedMessage.JSON_PROPERTY_MESSAGE_ID).asText(null);
                if (messageId != null) {

                    unackedMessages.put(messageId, deliveryTag);
                    deliveryChannels.put(deliveryTag, channel);
                    setAckTimeoutTimer(messageId);
                } else {
                    channel.basicAck(deliveryTag, false);
                }
            }

            // Immediately send the exact payload unmodified to the client!
            session.sendMessage(new TextMessage(payload));
            logger.debug("Sent event {} to user {}: {}", event, userId, payload);
        } catch (Exception e) {
            logger.error("Error sending message to client: {}", userId, e);
            // If an error happens processing the message, reject it and push it to DLQ instantly
            channel.basicNack(deliveryTag, false, false);
        }
    }


    /**
     * Receives messages from RabbitMQ and forwards them to the open WebSocket connection.
     */
    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        if (routingKey != null && routingKey.startsWith(HubRabbitMqService.ROUTING_KEY_PREFIX)) {
            String userId = routingKey.substring(HubRabbitMqService.ROUTING_KEY_PREFIX.length());
            WebSocketSession session = sessions.get(userId);

            if (session != null && session.isOpen()) {
                processIncomingRabbitMessage(message, channel, userId, session);
            } else {
                logger.warn("Received message for user {}, but connection is closed or not found", userId);
                // User is essentially offline, immediately NACK so it hits the Alternate/DLQ routing
                channel.basicNack(deliveryTag, false, false);
            }
        } else {
            // Not a known routing prefix, immediately NACK so it hits the DLX
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
