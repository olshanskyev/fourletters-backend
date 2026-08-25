package net.fourletters.hub.session;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Owns the Hub's live WebSocket sessions (one per user, last-wins) and their liveness state, and
 * provides safe delivery to a user's current socket. Shared by the message relay and presence, so
 * neither has to reach into the other.
 */
@Component
public class HubSessionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(HubSessionRegistry.class);

    /** How long a single write may block before the session is considered stuck. */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    /** Max bytes allowed to buffer per session before it is treated as too slow. */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    /** userId -> its current open WebSocket session on this Hub. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** WebSocket session id -> epoch millis of the last pong received. */
    private final Map<String, Long> lastPongTimes = new ConcurrentHashMap<>();

    /** Register a new session (wrapped for concurrency-safe writes); last-wins over any previous one. */
    public void register(String userId, WebSocketSession rawSession) {
        WebSocketSession concurrent =
                new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        sessions.put(userId, concurrent);
        lastPongTimes.put(rawSession.getId(), System.currentTimeMillis());
    }

    /** Remove the user's session iff the closing one is current; returns true when it was removed. */
    public boolean removeIfCurrent(String userId, String sessionId) {
        lastPongTimes.remove(sessionId);
        WebSocketSession stored = sessions.get(userId);
        if (stored != null && stored.getId().equals(sessionId)) {
            sessions.remove(userId, stored);
            return true;
        }
        return false;
    }

    public boolean isOnline(String userId) {
        return sessions.containsKey(userId);
    }

    public void recordPong(String sessionId) {
        lastPongTimes.put(sessionId, System.currentTimeMillis());
    }

    /** Best-effort send to a user's current socket; a no-op if they are offline or the write fails. */
    public void sendToUser(String userId, String frameJson) {
        writeText(userId == null ? null : sessions.get(userId), userId, frameJson);
    }

    /**
     * Answer a client ping on the socket that sent it. The user's current session shares a writer
     * with the relay thread, so it goes through the concurrency-safe decorator; a stale socket (a
     * brief reconnect-race leftover) has no competing writer, so it is written directly.
     */
    public void replyToPing(WebSocketSession pinger, String userId, String frameJson) {
        WebSocketSession current = sessions.get(userId);
        WebSocketSession target =
                (current != null && current.getId().equals(pinger.getId())) ? current : pinger;
        writeText(target, userId, frameJson);
    }

    private void writeText(WebSocketSession session, String userId, String frameJson) {
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(frameJson));
            } catch (Exception e) {
                logger.debug("Failed to send frame to user {}", userId, e);
            }
        }
    }

    /**
     * Ping every open session and evict unresponsive ones (removing then closing them). Returns the
     * userIds evicted so the caller can run the same teardown a graceful close performs.
     */
    public List<String> sweep(long pongTimeoutMs) {
        List<String> evicted = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, WebSocketSession>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, WebSocketSession> entry = it.next();
            String userId = entry.getKey();
            WebSocketSession session = entry.getValue();

            Long lastPong = lastPongTimes.get(session.getId());
            boolean stale = lastPong == null || (now - lastPong) > pongTimeoutMs;

            if (!session.isOpen() || stale) {
                logger.info("Evicting unresponsive session for user {}", userId);
                it.remove();
                lastPongTimes.remove(session.getId());
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception e) {
                    logger.debug("Failed to close stale session for user {}", userId, e);
                }
                evicted.add(userId);
                continue;
            }

            try {
                session.sendMessage(new PingMessage());
            } catch (Exception e) {
                logger.debug("Ping failed for user {}; will evict on next cycle", userId, e);
            }
        }
        return evicted;
    }
}
