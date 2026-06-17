package net.fourletters.hub.controller;

import com.rabbitmq.client.Channel;
import net.fourletters.broker.RabbitMqTopology;
import net.fourletters.hub.broker.HubRabbitMqService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receive-only live relay. The Hub pushes already-accepted, E2E-encrypted payloads
 * from its RabbitMQ queue to the recipient's WebSocket.
 */
@Component
public class HubWebSocketHandler extends TextWebSocketHandler implements ChannelAwareMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(HubWebSocketHandler.class);

    private final HubRabbitMqService rabbitMqService;
    private final SimpleMessageListenerContainer container;

    /** Local presence map: userId -> its open WebSocket session on this Hub. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public HubWebSocketHandler(HubRabbitMqService rabbitMqService, ConnectionFactory connectionFactory) {
        this.rabbitMqService = rabbitMqService;

        // The container is prepared but not started: the queue name is only known after
        // the Server provisions it during registration (see startConsuming).
        this.container = new SimpleMessageListenerContainer(connectionFactory);
        this.container.setMessageListener(this);
        this.container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    }

    /**
     * Begin consuming the queue the Server provisioned for this Hub. Called once after
     * a successful registration handshake.
     */
    public void startConsuming(String queueName) {
        this.container.setQueueNames(queueName);
        this.container.start();
        logger.info("Hub consuming relay queue {}", queueName);
    }

    @PreDestroy
    public void onDestroy() {
        if (this.container != null) {
            this.container.stop();
            this.container.destroy();
        }
    }

    // --- WebSocket presence ------------------------------------------------------

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        if (session.getPrincipal() != null) {
            String userId = session.getPrincipal().getName();
            sessions.put(userId, session);
            rabbitMqService.bindUserToHubQueue(userId);
            logger.info("Session connected and bound for user: {}", userId);
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        if (session.getPrincipal() != null) {
            String userId = session.getPrincipal().getName();
            sessions.remove(userId, session);
            try {
                rabbitMqService.unbindUserFromHubQueue(userId);
            } catch (AmqpApplicationContextClosedException e) {
                logger.debug("Application context is closed. Skipping unbind for user: {}", userId);
            }
            logger.info("Session closed and unbound for user: {}", userId);
        }
        super.afterConnectionClosed(session, status);
    }

    /**
     * The Hub is receive-only. Inbound WebSocket frames are ignored.
     */
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        logger.debug("Ignoring inbound WS frame; Hub is receive-only");
    }

    // --- Live relay from RabbitMQ ------------------------------------------------

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        String userId = (routingKey != null && routingKey.startsWith(RabbitMqTopology.ROUTING_KEY_PREFIX))
                ? routingKey.substring(RabbitMqTopology.ROUTING_KEY_PREFIX.length())
                : null;

        WebSocketSession session = userId != null ? sessions.get(userId) : null;

        if (session != null && session.isOpen()) {
            try {
                // Forward the opaque envelope unmodified, then ack.
                session.sendMessage(new TextMessage(new String(message.getBody(), StandardCharsets.UTF_8)));
                channel.basicAck(deliveryTag, false);
                logger.debug("Relayed message to user {}", userId);
            } catch (Exception e) {
                // Write failed: ack-and-drop.
                logger.warn("Failed to relay to user {}; ack-and-drop", userId, e);
                channel.basicAck(deliveryTag, false);
            }
        } else {
            // No live session: ack-and-drop. The Server retains the copy
            logger.debug("No live session for routing key {}; ack-and-drop", routingKey);
            channel.basicAck(deliveryTag, false);
        }
    }
}
