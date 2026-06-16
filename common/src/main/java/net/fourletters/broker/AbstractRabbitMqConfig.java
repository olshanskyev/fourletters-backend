package net.fourletters.broker;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.util.Map;

public abstract class AbstractRabbitMqConfig {

    public static final String ROUTING_KEY_PREFIX = "user.";
    public static final String MESSAGES_EXCHANGE = "messages.exchange";
    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String HOLDING_QUEUE_PREFIX = "holding.queue.";
    public static final String DLQ_DROPPED_ROUTING_KEY = "dlq.dropped";
    public static final int TTL_HOLDING_QUEUE = 30000;
    public static final int QUEUE_EXPIRES = 300000; // 5 minutes

}
