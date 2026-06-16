package net.fourletters.hub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.hub.broker.HubRabbitMqService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.util.UUID;

import com.rabbitmq.client.Channel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HubWebSocketHandlerTest {

    private HubWebSocketHandler handler;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private HubRabbitMqService rabbitMqService;

    @Mock
    private ConnectionFactory connectionFactory;

    @Mock
    private WebSocketSession session;

    @Mock
    private Principal principal;

    @Mock
    private Channel channel;

    private final String userId = UUID.randomUUID().toString();
    private final String recipientId = UUID.randomUUID().toString();
    private final String messageId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        when(rabbitMqService.getHubInstanceQueueName()).thenReturn("test-queue");
        handler = new HubWebSocketHandler(objectMapper, rabbitMqService, connectionFactory);

        lenient().when(session.getPrincipal()).thenReturn(principal);
        lenient().when(principal.getName()).thenReturn(userId);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(channel.isOpen()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        handler.onDestroy();
    }

    @Test
    void testAfterConnectionEstablished() throws Exception {
        handler.afterConnectionEstablished(session);
        verify(rabbitMqService, times(1)).bindUserToHubQueue(userId);
    }

    @Test
    void testSendMessage() throws Exception {
        handler.afterConnectionEstablished(session);

        String payload = """
            {
                "action": "sendMessage",
                "data": {
                    "messageId": "%s",
                    "recipientId": "%s",
                    "payload": "encryptedData123"
                }
            }
            """.formatted(messageId, recipientId);

        handler.handleTextMessage(session, new TextMessage(payload));

        ArgumentCaptor<String> mqPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).sendMessage(eq(recipientId), mqPayloadCaptor.capture());

        String sentToMq = mqPayloadCaptor.getValue();
        assertTrue(sentToMq.contains("messageReceived"));
        assertTrue(sentToMq.contains(userId));

        ArgumentCaptor<TextMessage> wsMessageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(wsMessageCaptor.capture());

        String sentToWs = wsMessageCaptor.getValue().getPayload();
        assertTrue(sentToWs.contains("messageDispatched"));
        assertTrue(sentToWs.contains(messageId));
    }

    @Test
    void testOnMessageAndAck() throws Exception {
        handler.afterConnectionEstablished(session);

        // 1. Simulate RabbitMQ sending a message to this hub (intended for userId)
        String mqEventPayload = """
            {
                "event": "messageReceived",
                "data": {
                    "messageId": "%s",
                    "recipientId": "%s",
                    "senderId": "%s",
                    "payload": "encryptedData"
                }
            }
            """.formatted(messageId, userId, recipientId);

        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("user." + userId);
        props.setDeliveryTag(12345L);
        Message amqpMessage = new Message(mqEventPayload.getBytes(), props);

        handler.onMessage(amqpMessage, channel);

        // Verify the message was sent to the Web Socket
        ArgumentCaptor<TextMessage> wsMessageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(wsMessageCaptor.capture());
        assertTrue(wsMessageCaptor.getValue().getPayload().contains(messageId));

        // 2. Simulate Client sending an ACK
        // The senderId in the generic ACK payload defines who originally sent it (recipientId from this hub's perspective)
        String ackPayload = """
            {
                "action": "ackMessage",
                "data": {
                    "messageId": "%s",
                    "senderId": "%s"
                }
            }
            """.formatted(messageId, recipientId);

        handler.handleTextMessage(session, new TextMessage(ackPayload));

        // Verify basicAck was called on the channel for delivery tag 12345
        verify(channel).basicAck(12345L, false);

        // Verify the Read Receipt was forwarded to the original sender
        ArgumentCaptor<String> forwardedAckCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).sendMessage(eq(recipientId), forwardedAckCaptor.capture());
        assertTrue(forwardedAckCaptor.getValue().contains("messageRead"));
    }

    @Test
    void testAfterConnectionClosedNacksUnackedMessages() throws Exception {
        handler.afterConnectionEstablished(session);

        // Simulate incoming message
        String mqEventPayload = """
            {
                "event": "messageReceived",
                "data": {
                    "messageId": "%s",
                    "recipientId": "%s",
                    "senderId": "%s",
                    "payload": "encryptedData"
                }
            }
            """.formatted(messageId, userId, recipientId);

        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("user." + userId);
        props.setDeliveryTag(999L);
        Message amqpMessage = new Message(mqEventPayload.getBytes(), props);

        handler.onMessage(amqpMessage, channel);

        // Do not send ACK. Instead, close the connection
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Verify the unacked message was NACKed natively
        verify(channel).basicNack(999L, false, false);
        verify(rabbitMqService).unbindUserFromHubQueue(userId);
    }
}

