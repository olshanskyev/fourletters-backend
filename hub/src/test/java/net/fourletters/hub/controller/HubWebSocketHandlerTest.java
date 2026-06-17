package net.fourletters.hub.controller;

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

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;

import com.rabbitmq.client.Channel;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HubWebSocketHandlerTest {

    private HubWebSocketHandler handler;

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
    private final String senderId = UUID.randomUUID().toString();
    private final String messageId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        handler = new HubWebSocketHandler(rabbitMqService, connectionFactory);

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
    void testAfterConnectionEstablishedBindsUser() throws Exception {
        handler.afterConnectionEstablished(session);
        verify(rabbitMqService, times(1)).bindUserToHubQueue(userId);
    }

    @Test
    void testAfterConnectionClosedUnbindsUser() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(rabbitMqService, times(1)).unbindUserFromHubQueue(userId);
    }

    @Test
    void testInboundFramesAreIgnored() throws Exception {
        handler.afterConnectionEstablished(session);

        // Hub is receive-only: an inbound client frame must never be published or echoed.
        handler.handleTextMessage(session, new TextMessage("{\"anything\":true}"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void testRelayForwardsOpaquePayloadAndAcks() throws Exception {
        handler.afterConnectionEstablished(session);

        String opaquePayload = """
            {
                "event": "messageReceived",
                "data": {
                    "messageId": "%s",
                    "recipientId": "%s",
                    "senderId": "%s",
                    "payload": "encryptedData"
                }
            }
            """.formatted(messageId, userId, senderId);

        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("user." + userId);
        props.setDeliveryTag(12345L);
        Message amqpMessage = new Message(opaquePayload.getBytes(StandardCharsets.UTF_8), props);

        handler.onMessage(amqpMessage, channel);

        // Payload is forwarded unmodified to the recipient's WebSocket.
        ArgumentCaptor<TextMessage> wsCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(wsCaptor.capture());
        assertTrue(wsCaptor.getValue().getPayload().contains(messageId));

        // Acked after the write (manual ack).
        verify(channel).basicAck(12345L, false);
    }

    @Test
    void testRelayAckAndDropWhenNoSession() throws Exception {
        // No session registered for the routing key's user -> ack-and-drop, no WS write.
        String payload = "{\"event\":\"messageReceived\"}";
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("user." + userId);
        props.setDeliveryTag(999L);
        Message amqpMessage = new Message(payload.getBytes(StandardCharsets.UTF_8), props);

        handler.onMessage(amqpMessage, channel);

        verify(session, never()).sendMessage(any());
        verify(channel).basicAck(999L, false);
    }
}
