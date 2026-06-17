package net.fourletters.server.broker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.broker.RabbitMqTopology;
import net.fourletters.dto.DeliveryReceipt;
import net.fourletters.dto.EncryptedMessage;
import net.fourletters.dto.MessageEvent;
import net.fourletters.dto.ReceiptData;
import net.fourletters.dto.ReceiptEvent;
import net.fourletters.server.service.PendingReceipts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Server-side RabbitMQ logic. The Server is the only component that declares
 * topology and the only component that publishes to the live fan-out bus.
 */
@Service
public class ServerRabbitMqService {

    private static final Logger logger = LoggerFactory.getLogger(ServerRabbitMqService.class);

    /** Header that marks a publish as a receipt, making only receipts {@code mandatory}. */
    private static final String RECEIPT_HEADER = "x-fl-receipt";

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final PendingReceipts pendingReceipts;

    public ServerRabbitMqService(AmqpAdmin amqpAdmin,
                                 RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper,
                                 PendingReceipts pendingReceipts) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.pendingReceipts = pendingReceipts;

        // The Server owns and declares the single live fan-out exchange at boot.
        amqpAdmin.declareExchange(new TopicExchange(RabbitMqTopology.MESSAGES_EXCHANGE));

        // Only receipts are published mandatory; an unroutable receipt (sender offline) is
        // returned and retained for /inbox pull. Messages carry no header and stay best-effort.
        rabbitTemplate.setMandatoryExpression(new SpelExpressionParser()
                .parseExpression("messageProperties.headers['" + RECEIPT_HEADER + "'] != null"));
        rabbitTemplate.setReturnsCallback(this::onReceiptReturned);
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
        send(RabbitMqTopology.ROUTING_KEY_PREFIX + message.getRecipientId(), event, message.getMessageId(), false);
    }

    /**
     * Relay a delivery/read receipt to the original sender's live stream. Published
     * {@code mandatory}: if the sender is offline the receipt is returned and retained.
     */
    public void publishReceipt(UUID senderId, ReceiptEvent event) {
        send(RabbitMqTopology.ROUTING_KEY_PREFIX + senderId, event, event.getData().getMessageId(), true);
    }

    private void send(String routingKey, Object body, Object idForLog, boolean receipt) {
        try {
            String json = objectMapper.writeValueAsString(body);
            if (receipt) {
                MessagePostProcessor markReceipt = message -> {
                    message.getMessageProperties().setHeader(RECEIPT_HEADER, "1");
                    return message;
                };
                rabbitTemplate.convertAndSend(RabbitMqTopology.MESSAGES_EXCHANGE, routingKey, json, markReceipt);
            } else {
                rabbitTemplate.convertAndSend(RabbitMqTopology.MESSAGES_EXCHANGE, routingKey, json);
            }
        } catch (JsonProcessingException e) {
            throw new AmqpException("Failed to serialize payload for " + idForLog, e);
        }
    }

    /**
     * A receipt that could not be routed means its target sender is offline; retain it so the
     * sender pulls it on the next {@code GET /inbox}. Routable receipts (sender online) are
     * delivered live and never reach here.
     */
    private void onReceiptReturned(ReturnedMessage returned) {
        try {
            ReceiptEvent event = objectMapper.readValue(returned.getMessage().getBody(), ReceiptEvent.class);
            ReceiptData data = event.getData();
            UUID senderId = UUID.fromString(
                    returned.getRoutingKey().substring(RabbitMqTopology.ROUTING_KEY_PREFIX.length()));
            DeliveryReceipt.TypeEnum type = event.getEvent() == ReceiptEvent.EventEnum.MESSAGE_READ
                    ? DeliveryReceipt.TypeEnum.READ
                    : DeliveryReceipt.TypeEnum.DELIVERED;
            pendingReceipts.record(senderId, data.getMessageId(), data.getRecipientId(), type);
            logger.debug("Sender {} offline; retained {} receipt for message {}",
                    senderId, type, data.getMessageId());
        } catch (Exception e) {
            logger.warn("Failed to retain returned receipt (routingKey={})", returned.getRoutingKey(), e);
        }
    }
}
