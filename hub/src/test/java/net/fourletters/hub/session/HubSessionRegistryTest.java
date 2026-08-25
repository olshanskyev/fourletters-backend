package net.fourletters.hub.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HubSessionRegistryTest {

    private HubSessionRegistry registry;

    @Mock
    private WebSocketSession session;

    private final String userId = UUID.randomUUID().toString();
    private final String sessionId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        registry = new HubSessionRegistry();
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.isOpen()).thenReturn(true);
    }

    @Test
    void registerMakesUserOnline() {
        assertFalse(registry.isOnline(userId));
        registry.register(userId, session);
        assertTrue(registry.isOnline(userId));
    }

    @Test
    void sendToUserForwardsFrameToTheSession() throws Exception {
        registry.register(userId, session);

        registry.sendToUser(userId, "{\"type\":\"pong\"}");

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertEquals("{\"type\":\"pong\"}", captor.getValue().getPayload());
    }

    @Test
    void sendToUserIsNoOpWhenOfflineOrNull() throws Exception {
        registry.sendToUser(userId, "x"); // never registered
        registry.sendToUser(null, "x");
        verify(session, never()).sendMessage(any());
    }

    @Test
    void removeIfCurrentRemovesOnlyTheCurrentSession() {
        registry.register(userId, session);

        // A stale session id closing must not evict the live session.
        assertFalse(registry.removeIfCurrent(userId, "some-other-session"));
        assertTrue(registry.isOnline(userId));

        assertTrue(registry.removeIfCurrent(userId, sessionId));
        assertFalse(registry.isOnline(userId));
    }

    @Test
    void sweepPingsLiveSessionsAndEvictsNone() throws Exception {
        registry.register(userId, session);

        List<String> evicted = registry.sweep(70_000);

        assertTrue(evicted.isEmpty());
        verify(session).sendMessage(isA(PingMessage.class));
    }

    @Test
    void sweepEvictsClosedSessionsAndReturnsThem() throws Exception {
        registry.register(userId, session);
        when(session.isOpen()).thenReturn(false);

        List<String> evicted = registry.sweep(70_000);

        assertEquals(List.of(userId), evicted);
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
        assertFalse(registry.isOnline(userId));
    }

    @Test
    void replyToPingAnswersTheSocketThatSentIt() throws Exception {
        registry.register(userId, session); // current session id = sessionId

        // A stale pinger (different id) is answered directly, not routed to the current session.
        WebSocketSession stalePinger = mock(WebSocketSession.class);
        when(stalePinger.getId()).thenReturn("stale-id");
        when(stalePinger.isOpen()).thenReturn(true);

        registry.replyToPing(stalePinger, userId, "{\"type\":\"pong\"}");

        verify(stalePinger).sendMessage(isA(TextMessage.class));
        verify(session, never()).sendMessage(any());
    }
}
