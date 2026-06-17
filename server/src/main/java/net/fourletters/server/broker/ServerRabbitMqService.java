package net.fourletters.server.broker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.broker.RabbitMqTopology;
import net.fourletters.dto.EncryptedMessage;
import net.fourletters.dto.MessageEvent;
import net.fourletters.dto.ReceiptEvent;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Server-side RabbitMQ logic. The Server is the only component that declares
 * topology and the only component that publishes to the live fan-out bus.
 */
@Service
public class ServerRabbitMqService {

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public ServerRabbitMqService(AmqpAdmin amqpAdmin,
                                 RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;

        // The Server owns and declares the single live fan-out exchange at boot.
        amqpAdmin.declareExchange(new TopicExchange(RabbitMqTopology.MESSAGES_EXCHANGE));
    }

    /**
     * Provision the auto-delete queue for an authenticating Hub and return its name.
     */
    public String provisionHubQueue(String hubId) {
        String queueName = RabbitMqTopology.HUB_QUEUE_PREFIX + hubId;
        Queue hubQueue = QueueBuilder.durable(queueName).autoDelete().build();
        amqpAdmin.declareQueue(hubQueue);
        return queueName;
    }

    /**
     * Publish an accepted message to the live fan-out bus, routed to its recipient.
     */
    public void publishMessage(EncryptedMessage message) {
        MessageEvent event = new MessageEvent();
        event.setEvent(MessageEvent.EventEnum.MESSAGE_RECEIVED);
        event.setData(message);
        send(RabbitMqTopology.ROUTING_KEY_PREFIX + message.getRecipientId(), event, message.getMessageId());
    }

    /**
     * Relay a delivery/read receipt to the original sender's live stream.
     */
    public void publishReceipt(UUID senderId, ReceiptEvent event) {
        send(RabbitMqTopology.ROUTING_KEY_PREFIX + senderId, event, event.getData().getMessageId());
    }

    private void send(String routingKey, Object body, Object idForLog) {
        try {
            String json = objectMapper.writeValueAsString(body);
            rabbitTemplate.convertAndSend(RabbitMqTopology.MESSAGES_EXCHANGE, routingKey, json);
        } catch (JsonProcessingException e) {
            throw new AmqpException("Failed to serialize payload for " + idForLog, e);
        }
    }
}
