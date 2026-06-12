package net.fourletters.broker;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.util.Map;

public abstract class AbstractRabbitMqConfig {

    public static final String ROUTING_KEY_PREFIX = "user.";
    public static final String MESSAGES_EXCHANGE = "messages.exchange";

}

