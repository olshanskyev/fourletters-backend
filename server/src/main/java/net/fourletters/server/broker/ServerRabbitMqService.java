package net.fourletters.server.broker;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.stereotype.Service;
import net.fourletters.broker.AbstractRabbitMqConfig;

import java.util.Map;

@Service
public class ServerRabbitMqService extends AbstractRabbitMqConfig {

    public ServerRabbitMqService(AmqpAdmin amqpAdmin) {
        // 1. Set up Alternate/DLQ Exchange
        TopicExchange dlqExchange = new TopicExchange("dlx.exchange");
        amqpAdmin.declareExchange(dlqExchange);

        // 2. Ensure Core Routing Exchange exists, configured with Alternate Exchange
        TopicExchange messagesExchange = new TopicExchange(
                MESSAGES_EXCHANGE,
                true,   // durable (survives restarts)
                false,  // autoDelete
                Map.of("alternate-exchange", "dlx.exchange") // configure alternate exchange mapping
        );
        amqpAdmin.declareExchange(messagesExchange);

        // 3. Holding Queue (TTL = 30s)
        Queue holdingQueue = new Queue("unrouted.holding.queue", true, false, false,
                Map.of(
                        "x-message-ttl", 30000, // 30 seconds
                        "x-dead-letter-exchange", "dlx.exchange",
                        "x-dead-letter-routing-key", "dlq.dropped"
                ));
        amqpAdmin.declareQueue(holdingQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(holdingQueue).to(dlqExchange).with(ROUTING_KEY_PREFIX + "*"));

        // 4. Real Dead Letter Queue (Gets messages after 30s TTL expires)
        Queue offlineMessagesQueue = new Queue("offline.messages.queue", true);
        amqpAdmin.declareQueue(offlineMessagesQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(offlineMessagesQueue).to(dlqExchange).with("dlq.dropped"));
    }
}

