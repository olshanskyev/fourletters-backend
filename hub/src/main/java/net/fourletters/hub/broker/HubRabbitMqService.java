package net.fourletters.hub.broker;

import net.fourletters.broker.RabbitMqTopology;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.stereotype.Service;

/**
 * Hub-side RabbitMQ logic. The Hub is a receive-only blind relay
 */
@Service
public class HubRabbitMqService {

    private final AmqpAdmin amqpAdmin;
    private final TopicExchange messagesExchange =
            new TopicExchange(RabbitMqTopology.MESSAGES_EXCHANGE);

    private volatile String hubQueueName;

    public HubRabbitMqService(AmqpAdmin amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
    }

    public void useProvisionedQueue(String hubQueueName) {
        this.hubQueueName = hubQueueName;
    }

    public String getHubInstanceQueueName() {
        return hubQueueName;
    }

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
}
