package net.fourletters.hub.broker;

import net.fourletters.broker.RabbitMqTopology;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Hub-side RabbitMQ logic. The Hub is a receive-only blind relay for messages; for presence it also
 * manages per-user bindings on {@code presence.exchange} and publishes online/offline/typing events.
 */
@Service
public class HubRabbitMqService {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange messagesExchange =
            new TopicExchange(RabbitMqTopology.MESSAGES_EXCHANGE);
    private final TopicExchange presenceExchange =
            new TopicExchange(RabbitMqTopology.PRESENCE_EXCHANGE);

    private volatile String hubQueueName;
    /** Notified with a userId when a presence probe is returned unroutable, i.e. that user is offline. */
    private volatile Consumer<String> probeReturnedHandler = userId -> { };

    public HubRabbitMqService(AmqpAdmin amqpAdmin, RabbitTemplate rabbitTemplate) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        // Presence probes rely on the mandatory-return of an unroutable publish to mean "offline".
        this.rabbitTemplate.setMandatory(true);
        this.rabbitTemplate.setReturnsCallback(returned -> {
            String routingKey = returned.getRoutingKey();
            if (routingKey != null && routingKey.startsWith(RabbitMqTopology.PRESENCE_KEY_PREFIX)) {
                probeReturnedHandler.accept(routingKey.substring(RabbitMqTopology.PRESENCE_KEY_PREFIX.length()));
            }
            // A returned watch.{id} event (no watchers bound) is harmless and ignored.
        });
    }

    public void useProvisionedQueue(String hubQueueName) {
        this.hubQueueName = hubQueueName;
    }

    public String getHubInstanceQueueName() {
        return hubQueueName;
    }

    /** Register the callback invoked when a presence probe comes back unroutable (user offline). */
    public void setProbeReturnedHandler(Consumer<String> handler) {
        this.probeReturnedHandler = handler;
    }

    // --- Message relay bindings (messages.exchange) ------------------------------

    public void bindUserToHubQueue(String userId) {
        amqpAdmin.declareBinding(userBinding(userId));
    }

    public void unbindUserFromHubQueue(String userId) {
        amqpAdmin.removeBinding(userBinding(userId));
    }

    private Binding userBinding(String userId) {
        // The Queue here is only a name reference for the binding; the Hub never declares it.
        return BindingBuilder
                .bind(new Queue(hubQueueName))
                .to(messagesExchange)
                .with(RabbitMqTopology.ROUTING_KEY_PREFIX + userId);
    }

    // --- Presence bindings (presence.exchange) -----------------------------------

    /** Mark a locally-connected user as online: bind its {@code presence.{id}} probe target. */
    public void bindPresence(String userId) {
        amqpAdmin.declareBinding(presenceBinding(RabbitMqTopology.PRESENCE_KEY_PREFIX, userId));
    }

    public void unbindPresence(String userId) {
        amqpAdmin.removeBinding(presenceBinding(RabbitMqTopology.PRESENCE_KEY_PREFIX, userId));
    }

    /** Start receiving a watched user's events: bind {@code watch.{id}} to this Hub's queue. */
    public void bindWatch(String userId) {
        amqpAdmin.declareBinding(presenceBinding(RabbitMqTopology.WATCH_KEY_PREFIX, userId));
    }

    public void unbindWatch(String userId) {
        amqpAdmin.removeBinding(presenceBinding(RabbitMqTopology.WATCH_KEY_PREFIX, userId));
    }

    private Binding presenceBinding(String keyPrefix, String userId) {
        return BindingBuilder
                .bind(new Queue(hubQueueName))
                .to(presenceExchange)
                .with(keyPrefix + userId);
    }

    // --- Presence publishing (presence.exchange) ---------------------------------

    /** Publish a presence/typing event frame to a user's watchers ({@code watch.{id}}). */
    public void publishToWatch(String userId, String frameJson) {
        rabbitTemplate.convertAndSend(
                RabbitMqTopology.PRESENCE_EXCHANGE, RabbitMqTopology.WATCH_KEY_PREFIX + userId, frameJson);
    }

    /**
     * Probe whether a user is online: a {@code mandatory} publish to {@code presence.{id}}. If bound
     * (online) it reaches the owner Hub, which re-announces the user; if not, it is returned here.
     */
    public void probePresence(String userId) {
        rabbitTemplate.convertAndSend(
                RabbitMqTopology.PRESENCE_EXCHANGE, RabbitMqTopology.PRESENCE_KEY_PREFIX + userId, "");
    }
}
