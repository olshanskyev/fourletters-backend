package net.fourletters.hub.broker;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import net.fourletters.broker.AbstractRabbitMqConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class HubRabbitMqService extends AbstractRabbitMqConfig {

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    // Fast memory cache to avoid thrashing RabbitMQ explicitly declaring queues per send
    private final Map<String, Boolean> declaredHoldingQueues = new ConcurrentHashMap<>();

    // An exclusive, auto-delete queue for THIS specific Hub instance
    private final Queue hubInstanceQueue;

    public HubRabbitMqService(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;

        // Create an exclusive, transient queue for this Hub instance
        String queueName = "hub.queue." + UUID.randomUUID();
        this.hubInstanceQueue = new Queue(queueName, false, true, false,
                java.util.Map.of(
                        "x-dead-letter-exchange", DLX_EXCHANGE,
                        "x-expires", QUEUE_EXPIRES // 5 minutes expiration if unused
                )
        );
        this.amqpAdmin.declareQueue(this.hubInstanceQueue);
    }

    public void ensureUserHoldingQueueExists(String userId) {
        // Only safely declare it over the MQ network once per Hub per user logic constraint
        if (declaredHoldingQueues.putIfAbsent(userId, true) == null) {
            String queueName = HOLDING_QUEUE_PREFIX + userId;
            Queue holdingQueue = new Queue(queueName, true, false, false,
                    java.util.Map.of(
                            "x-message-ttl", TTL_HOLDING_QUEUE,
                            "x-dead-letter-exchange", DLX_EXCHANGE,
                            "x-dead-letter-routing-key", DLQ_DROPPED_ROUTING_KEY,
                            "x-expires", QUEUE_EXPIRES // Self-delete this user's holding queue after 5 mins of non-use to save RAM
                    ));
            amqpAdmin.declareQueue(holdingQueue);
            TopicExchange dlqExchange = new TopicExchange(DLX_EXCHANGE);
            amqpAdmin.declareBinding(BindingBuilder.bind(holdingQueue).to(dlqExchange).with(ROUTING_KEY_PREFIX + userId));
        }
    }

    public void sendMessage(String recipientId, String payload) {
        // Guarantee the target recipient's fallback holding queue actually exists BEFORE sending
        ensureUserHoldingQueueExists(recipientId);
        rabbitTemplate.convertAndSend(MESSAGES_EXCHANGE, ROUTING_KEY_PREFIX + recipientId, payload);
    }

    public void bindUserToHubQueue(String userId) {
        TopicExchange exchange = new TopicExchange(MESSAGES_EXCHANGE);
        Binding binding = BindingBuilder.bind(hubInstanceQueue).to(exchange).with(ROUTING_KEY_PREFIX + userId);
        amqpAdmin.declareBinding(binding);
    }

    public void unbindUserFromHubQueue(String userId) {
        TopicExchange exchange = new TopicExchange(MESSAGES_EXCHANGE);
        Binding binding = BindingBuilder.bind(hubInstanceQueue).to(exchange).with(ROUTING_KEY_PREFIX + userId);
        amqpAdmin.removeBinding(binding);
    }

    public String getHubInstanceQueueName() {
        return hubInstanceQueue.getName();
    }
}
