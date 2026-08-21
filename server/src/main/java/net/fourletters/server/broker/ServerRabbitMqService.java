package net.fourletters.server.broker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.broker.RabbitMqTopology;
import net.fourletters.dto.*;
import net.fourletters.server.service.PushNotificationService;
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
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Server-side RabbitMQ logic. The Server is the only component that declares
 * topology and the only component that publishes to the live fan-out bus.
 */
@Service
public class ServerRabbitMqService {

    private static final Logger logger = LoggerFactory.getLogger(ServerRabbitMqService.class);

    /** Header that marks a publish as a receipt, so the returns callback can tell it from a message. */
    private static final String RECEIPT_HEADER = "x-fl-receipt";

    private final AmqpAdmin amqpAdmin;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final PushNotificationService pushNotificationService;

    public ServerRabbitMqService(AmqpAdmin amqpAdmin,
                                 RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper,
                                 PushNotificationService pushNotificationService) {
        this.amqpAdmin = amqpAdmin;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.pushNotificationService = pushNotificationService;

        // The Server owns and declares the single live fan-out exchange at boot.
        amqpAdmin.declareExchange(new TopicExchange(RabbitMqTopology.MESSAGES_EXCHANGE));

        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReturnsCallback(this::onMessageReturned);
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
     * An unroutable publish is returned here: a returned <b>message</b> (recipient has no live Hub
     * binding = offline) triggers a best-effort push wake-up. Receipts are retained in
     * PendingReceipts and pulled via {@code GET /inbox}, so a returned receipt needs no action.
     */
    private void onMessageReturned(ReturnedMessage returned) {
        Object receiptHeader = returned.getMessage().getMessageProperties().getHeaders().get(RECEIPT_HEADER);
        if (receiptHeader != null) {
            return;
        }
        pushForReturnedMessage(returned);
    }

    /** Wake an offline recipient whose live message could not be routed to any Hub. */
    private void pushForReturnedMessage(ReturnedMessage returned) {
        try {
            MessageEvent event = objectMapper.readValue(returned.getMessage().getBody(), MessageEvent.class);
            EncryptedMessage message = event.getData();
            UUID recipientId = UUID.fromString(
                    returned.getRoutingKey().substring(RabbitMqTopology.ROUTING_KEY_PREFIX.length()));
            pushNotificationService.notifyRecipient(
                    recipientId, message.getSenderId(), message.getGroupId(), message.getMessageId(), false);
            logger.debug("Recipient {} offline; triggered push for message {}",
                    recipientId, message.getMessageId());
        } catch (Exception e) {
            logger.warn("Failed to trigger push for returned message (routingKey={})", returned.getRoutingKey(), e);
        }
    }
}
