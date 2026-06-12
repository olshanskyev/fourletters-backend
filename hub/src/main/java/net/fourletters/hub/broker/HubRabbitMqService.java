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

@Service
public class HubRabbitMqService extends AbstractRabbitMqConfig {

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    // An exclusive, auto-delete queue for THIS specific Hub instance
    private final Queue hubInstanceQueue;

    public HubRabbitMqService(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;

        // Create an auto-delete, exclusive queue for this Hub instance
        String queueName = "hub.queue." + UUID.randomUUID();
        this.hubInstanceQueue = new Queue(queueName, false, true, true);
        this.amqpAdmin.declareQueue(this.hubInstanceQueue);
    }

    public void sendMessage(String userId, String payload) {
        rabbitTemplate.convertAndSend(MESSAGES_EXCHANGE, ROUTING_KEY_PREFIX + userId, payload);
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

