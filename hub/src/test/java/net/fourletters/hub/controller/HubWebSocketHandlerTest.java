package net.fourletters.hub.controller;

import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.call.CallSignalService;
import net.fourletters.hub.presence.PresenceService;
import net.fourletters.hub.session.HubSessionRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // Real registry + presence over the mocked broker, so relay/session behaviour is exercised.
        HubSessionRegistry registry = new HubSessionRegistry();
        PresenceService presence = new PresenceService(rabbitMqService, registry);
        CallSignalService calls = new CallSignalService(rabbitMqService, registry);
        handler = new HubWebSocketHandler(rabbitMqService, registry, presence, calls, connectionFactory);

        lenient().when(session.getPrincipal()).thenReturn(principal);
        lenient().when(principal.getName()).thenReturn(userId);
        lenient().when(session.isOpen()).thenReturn(true);
        lenient().when(session.getId()).thenReturn(UUID.randomUUID().toString());
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
        verify(rabbitMqService, times(1)).bindCallSignals(userId);
        verify(rabbitMqService, times(1)).bindTypingUser(userId);
    }

    @Test
    void testAfterConnectionClosedUnbindsUser() throws Exception {
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(rabbitMqService, times(1)).unbindUserFromHubQueue(userId);
        verify(rabbitMqService, times(1)).unbindCallSignals(userId);
        verify(rabbitMqService, times(1)).unbindTypingUser(userId);
    }

    @Test
    void testDirectTypingUsesAuthenticatedSenderAndRecipientRoute() {
        handler.handleTextMessage(session, new TextMessage("""
            {"type":"typing","recipientId":"%s","userId":"%s"}
            """.formatted(senderId, messageId)));

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishTypingUser(eq(senderId), frame.capture());
        assertTrue(frame.getValue().contains("\"userId\":\"" + userId + "\""));
        assertFalse(frame.getValue().contains(messageId));
        verify(rabbitMqService, never()).publishToWatch(anyString(), anyString());
    }

    @Test
    void testGroupTypingUsesAuthenticatedSenderAndGroupRoute() {
        handler.handleTextMessage(session, new TextMessage("""
            {"type":"typing","groupId":"%s","userId":"%s"}
            """.formatted(messageId, senderId)));

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishTypingGroup(eq(messageId), frame.capture());
        assertTrue(frame.getValue().contains("\"type\":\"typing\""));
        assertTrue(frame.getValue().contains("\"userId\":\"" + userId + "\""));
        assertTrue(frame.getValue().contains("\"groupId\":\"" + messageId + "\""));
        assertFalse(frame.getValue().contains(senderId));
        verify(rabbitMqService, never()).publishTypingUser(anyString(), anyString());
        verify(rabbitMqService, never()).publishToWatch(anyString(), anyString());
    }

    @Test
    void testTypingWithMissingOrBothDestinationsIsIgnored() {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"typing\"}"));
        handler.handleTextMessage(session, new TextMessage("""
            {"type":"typing","recipientId":"%s","groupId":"%s"}
            """.formatted(senderId, messageId)));

        verify(rabbitMqService, never()).publishTypingUser(anyString(), anyString());
        verify(rabbitMqService, never()).publishTypingGroup(anyString(), anyString());
        verify(rabbitMqService, never()).publishToWatch(anyString(), anyString());
    }

    @Test
    void testDirectTypingDeliveryIsForwardedAndAcked() throws Exception {
        handler.afterConnectionEstablished(session);
        String frame = "{\"type\":\"typing\",\"userId\":\"%s\"}".formatted(senderId);
        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("presence.exchange");
        props.setReceivedRoutingKey("typing.user." + userId);
        props.setDeliveryTag(78L);

        handler.onMessage(new Message(frame.getBytes(StandardCharsets.UTF_8), props), channel);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        assertEquals(frame, sent.getValue().getPayload());
        verify(channel).basicAck(78L, false);
    }

    @Test
    void testDirectTypingForAnotherRecipientIsNotForwarded() throws Exception {
        handler.afterConnectionEstablished(session);
        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("presence.exchange");
        props.setReceivedRoutingKey("typing.user." + senderId);
        props.setDeliveryTag(79L);

        handler.onMessage(new Message("{}".getBytes(StandardCharsets.UTF_8), props), channel);

        verify(session, never()).sendMessage(any());
        verify(channel).basicAck(79L, false);
    }

    @Test
    void testCallSignalIsStampedWithSessionUserAndPublished() throws Exception {
        handler.afterConnectionEstablished(session);
        String recipient = UUID.randomUUID().toString();
        String spoofed = UUID.randomUUID().toString();

        handler.handleTextMessage(session, new TextMessage("""
            {"type":"call_signal","recipientId":"%s","senderId":"%s","payload":"1.abc"}
            """.formatted(recipient, spoofed)));

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishCallSignal(eq(recipient), frame.capture());
        // The sender is the authenticated session user; a client-supplied senderId is ignored.
        assertTrue(frame.getValue().contains("\"senderId\":\"" + userId + "\""));
        assertFalse(frame.getValue().contains(spoofed));
        assertTrue(frame.getValue().contains("\"type\":\"call_signal\""));
    }

    @Test
    void testCallDeliveryIsForwardedToRecipientAndAcked() throws Exception {
        handler.afterConnectionEstablished(session);
        String frame = "{\"type\":\"call_signal\",\"senderId\":\"%s\",\"payload\":\"1.abc\"}"
                .formatted(senderId);
        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("calls.exchange");
        props.setReceivedRoutingKey("call." + userId);
        props.setDeliveryTag(77L);

        handler.onMessage(new Message(frame.getBytes(StandardCharsets.UTF_8), props), channel);

        ArgumentCaptor<TextMessage> wsCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(wsCaptor.capture());
        assertEquals(frame, wsCaptor.getValue().getPayload());
        verify(channel).basicAck(77L, false);
    }

    @Test
    void testInboundFramesAreIgnored() throws Exception {
        handler.afterConnectionEstablished(session);

        // An unknown inbound client frame (no recognised "type") must never be echoed or acted on.
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
