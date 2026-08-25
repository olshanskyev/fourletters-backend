package net.fourletters.hub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import net.fourletters.broker.RabbitMqTopology;
import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.presence.PresenceService;
import net.fourletters.hub.session.HubSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;

/**
 * WebSocket endpoint for the Hub. Owns the connection lifecycle, inbound-frame dispatch, the
 * server-initiated heartbeat, and the RabbitMQ consume loop.
 */
@Component
public class HubWebSocketHandler extends TextWebSocketHandler implements ChannelAwareMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(HubWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Close a session if no pong has been received within this window. */
    private static final long PONG_TIMEOUT_MS = 70_000;

    /** Reply to a client ping, so a client can tell a live socket from a silently-dropped one. */
    private static final String PONG_FRAME = "{\"type\":\"pong\"}";

    private final HubRabbitMqService rabbitMqService;
    private final HubSessionRegistry registry;
    private final PresenceService presence;
    private final SimpleMessageListenerContainer container;

    public HubWebSocketHandler(HubRabbitMqService rabbitMqService,
                               HubSessionRegistry registry,
                               PresenceService presence,
                               ConnectionFactory connectionFactory) {
        this.rabbitMqService = rabbitMqService;
        this.registry = registry;
        this.presence = presence;

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

    // --- WebSocket lifecycle -----------------------------------------------------

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        var principal = session.getPrincipal();
        if (principal == null) {
            return;
        }
        String userId = principal.getName();
        registry.register(userId, session);
        rabbitMqService.bindUserToHubQueue(userId);
        presence.onUserOnline(userId);
        logger.info("Session connected and bound for user: {}", userId);
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) throws Exception {
        var principal = session.getPrincipal();
        if (principal != null) {
            String userId = principal.getName();
            // Only tear down when the closing session is the user's current one; a stale one closing
            // after a newer socket replaced it must leave the live bindings alone.
            if (registry.removeIfCurrent(userId, session.getId())) {
                teardown(userId);
                logger.info("Session closed and unbound for user: {}", userId);
            } else {
                logger.debug("Stale session closed for user: {}", userId);
            }
        }
        super.afterConnectionClosed(session, status);
    }

    /** Release everything a disconnecting user held: its relay binding and its presence state. */
    private void teardown(String userId) {
        try {
            rabbitMqService.unbindUserFromHubQueue(userId);
        } catch (AmqpApplicationContextClosedException e) {
            logger.debug("Application context is closed. Skipping unbind for user: {}", userId);
        }
        presence.onUserOffline(userId);
    }

    /**
     * The Hub acts on a small set of inbound frames: a {@code ping} liveness probe and the presence
     * control frames ({@code presence_subscribe} / {@code presence_unsubscribe} / {@code typing}).
     */
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        var principal = session.getPrincipal();
        if (principal == null) {
            return;
        }
        String userId = principal.getName();
        JsonNode node;
        try {
            node = MAPPER.readTree(message.getPayload().trim());
        } catch (Exception e) {
            logger.debug("Ignoring malformed inbound WS frame");
            return;
        }
        String type = node.path("type").asText(null);
        if (type == null) {
            return;
        }
        switch (type) {
            case "ping" -> registry.replyToPing(session, userId, PONG_FRAME);
            case "presence_subscribe" -> presence.subscribe(userId, node.path("userId").asText(null));
            case "presence_unsubscribe" -> presence.unsubscribe(userId, node.path("userId").asText(null));
            case "typing" -> presence.typing(userId);
            default -> logger.debug("Ignoring inbound WS frame type {}", type);
        }
    }

    // --- Heartbeat (server-initiated ping / pong) --------------------------------

    @Override
    protected void handlePongMessage(@NonNull WebSocketSession session, @NonNull PongMessage message) {
        registry.recordPong(session.getId());
    }

    /**
     * Periodically ping every open session and evict any that stopped responding. Evicted sessions
     * run the same teardown as a graceful close (unless the user has already reconnected).
     * Interval must be shorter than {@link #PONG_TIMEOUT_MS} and shorter than any idle timeout
     * imposed by intermediaries (proxies, dev tunnels).
     */
    @Scheduled(fixedRate = 30_000)
    public void sendHeartbeats() {
        for (String userId : registry.sweep(PONG_TIMEOUT_MS)) {
            if (!registry.isOnline(userId)) {
                teardown(userId);
            }
        }
    }

    // --- Live relay from RabbitMQ ------------------------------------------------

    @Override
    public void onMessage(Message message, Channel channel) throws Exception {
        String exchange = message.getMessageProperties().getReceivedExchange();
        if (RabbitMqTopology.PRESENCE_EXCHANGE.equals(exchange)) {
            handlePresenceDelivery(message, channel);
            return;
        }
        relayMessage(message, channel);
    }

    /** Relay an opaque, already-accepted message envelope to its recipient's WebSocket, then ack. */
    private void relayMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        String userId = (routingKey != null && routingKey.startsWith(RabbitMqTopology.ROUTING_KEY_PREFIX))
                ? routingKey.substring(RabbitMqTopology.ROUTING_KEY_PREFIX.length())
                : null;

        // Best-effort live delivery: a no-op when the recipient is offline. Always ack-and-drop —
        // the Server retains the copy and the recipient pulls it via GET /inbox.
        registry.sendToUser(userId, new String(message.getBody(), StandardCharsets.UTF_8));
        channel.basicAck(deliveryTag, false);
    }

    /**
     * Handle a delivery from {@code presence.exchange}: a {@code watch.{id}} event is forwarded to
     * that user's local watchers; a {@code presence.{id}} probe (we own that user) triggers an
     * online re-announce. Presence deliveries are always acked-and-dropped.
     */
    private void handlePresenceDelivery(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        try {
            if (routingKey != null && routingKey.startsWith(RabbitMqTopology.WATCH_KEY_PREFIX)) {
                String watched = routingKey.substring(RabbitMqTopology.WATCH_KEY_PREFIX.length());
                presence.onWatchEvent(watched, new String(message.getBody(), StandardCharsets.UTF_8));
            } else if (routingKey != null && routingKey.startsWith(RabbitMqTopology.PRESENCE_KEY_PREFIX)) {
                presence.onProbe(routingKey.substring(RabbitMqTopology.PRESENCE_KEY_PREFIX.length()));
            }
        } finally {
            channel.basicAck(deliveryTag, false);
        }
    }
}
