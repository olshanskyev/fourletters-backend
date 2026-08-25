package net.fourletters.hub.presence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.dto.PresenceEvent;
import net.fourletters.dto.PresenceStatus;
import net.fourletters.dto.TypingEvent;
import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.session.HubSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live presence/typing relay over {@code presence.exchange}. Holds only transient subscription
 * routing (which local user watches whom) and persists nothing. Online/offline is derived from
 * routability: a locally-online user answers immediately, otherwise a {@code mandatory} probe is
 * either re-announced online by the owner Hub or returned unroutable (offline).
 */
@Service
public class PresenceService {

    private static final Logger logger = LoggerFactory.getLogger(PresenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HubRabbitMqService rabbitMqService;
    private final HubSessionRegistry registry;

    /** watchedUserId -> set of local watcher userIds. */
    private final Map<String, Set<String>> watchers = new ConcurrentHashMap<>();
    /** Inverse index for cleanup: watcher userId -> set of userIds it watches. */
    private final Map<String, Set<String>> watchedBy = new ConcurrentHashMap<>();
    /** Guards compound updates to {@link #watchers}/{@link #watchedBy} and their bind/unbind decisions. */
    private final Object lock = new Object();

    public PresenceService(HubRabbitMqService rabbitMqService, HubSessionRegistry registry) {
        this.rabbitMqService = rabbitMqService;
        this.registry = registry;
        this.rabbitMqService.setProbeReturnedHandler(this::onProbeReturned);
    }

    // --- Local user lifecycle ----------------------------------------------------

    /** A local user came online: bind its probe target and notify anyone already watching it. */
    public void onUserOnline(String userId) {
        logger.debug("Presence: {} online -> bind presence target + announce online", userId);
        rabbitMqService.bindPresence(userId);
        rabbitMqService.publishToWatch(userId, presenceFrame(userId, PresenceStatus.ONLINE));
    }

    /** A local user went offline: drop its probe target, notify watchers, and release its watches. */
    public void onUserOffline(String userId) {
        logger.debug("Presence: {} offline -> announce offline + release watches", userId);
        try {
            rabbitMqService.unbindPresence(userId);
            rabbitMqService.publishToWatch(userId, presenceFrame(userId, PresenceStatus.OFFLINE));
        } catch (AmqpApplicationContextClosedException e) {
            logger.debug("Context closed; skipping presence offline for {}", userId);
        }
        cleanupWatches(userId);
    }

    // --- Inbound client frames ---------------------------------------------------

    /** A client opened a chat with {@code watched}: start receiving its state and answer a snapshot. */
    public void subscribe(String watcher, String watched) {
        if (watched == null) {
            return;
        }
        boolean first;
        synchronized (lock) {
            watchedBy.computeIfAbsent(watcher, k -> new HashSet<>()).add(watched);
            Set<String> w = watchers.computeIfAbsent(watched, k -> new HashSet<>());
            first = w.isEmpty();
            w.add(watcher);
            if (first) {
                rabbitMqService.bindWatch(watched);
            }
        }
        // Locally-online users are answered immediately; otherwise probe (owner re-announces online,
        // or the probe is returned unroutable -> offline via onProbeReturned).
        boolean online = registry.isOnline(watched);
        logger.debug("Presence: {} subscribed to {} (firstWatcher={}, {})",
                watcher, watched, first, online ? "online" : "probing");
        if (online) {
            registry.sendToUser(watcher, presenceFrame(watched, PresenceStatus.ONLINE));
        } else {
            rabbitMqService.probePresence(watched);
        }
    }

    /** A client closed a chat with {@code watched}: stop receiving its state. */
    public void unsubscribe(String watcher, String watched) {
        if (watched == null) {
            return;
        }
        logger.debug("Presence: {} unsubscribed from {}", watcher, watched);
        removeWatch(watcher, watched);
    }

    /** A client is typing: notify the watchers of that user. */
    public void typing(String userId) {
        rabbitMqService.publishToWatch(userId, typingFrame(userId));
    }

    // --- Inbound presence.exchange deliveries ------------------------------------

    /** A {@code watch.{id}} event: forward the frame verbatim to that user's local watchers. */
    public void onWatchEvent(String watched, String body) {
        for (String watcher : snapshotWatchers(watched)) {
            registry.sendToUser(watcher, body);
        }
    }

    /** An inbound {@code presence.{id}} probe: if we own that user, re-announce it online. */
    public void onProbe(String probed) {
        if (registry.isOnline(probed)) {
            logger.debug("Presence: inbound probe for {} -> re-announcing online", probed);
            rabbitMqService.publishToWatch(probed, presenceFrame(probed, PresenceStatus.ONLINE));
        } else {
            logger.debug("Presence: inbound probe for {} ignored (not local)", probed);
        }
    }

    /** A probe came back unroutable: the watched user is offline; tell its local watchers. */
    private void onProbeReturned(String watched) {
        Set<String> targets = snapshotWatchers(watched);
        logger.debug("Presence: probe for {} returned unroutable -> offline to {} watcher(s)", watched, targets.size());
        for (String watcher : targets) {
            registry.sendToUser(watcher, presenceFrame(watched, PresenceStatus.OFFLINE));
        }
    }

    // --- Watch-table bookkeeping -------------------------------------------------

    private void removeWatch(String watcher, String watched) {
        synchronized (lock) {
            Set<String> wb = watchedBy.get(watcher);
            if (wb != null) {
                wb.remove(watched);
                if (wb.isEmpty()) {
                    watchedBy.remove(watcher);
                }
            }
                detachWatcher(watcher, watched);
        }
    }

    /** Drop every watch held by a disconnecting user, unbinding keys that lose their last watcher. */
    private void cleanupWatches(String watcher) {
        synchronized (lock) {
            Set<String> watched = watchedBy.remove(watcher);
            if (watched != null) {
                for (String x : watched) {
                    detachWatcher(watcher, x);
                }
            }
        }
    }

    /** Remove {@code watcher} from {@code watchers[watched]}, unbinding the key if it is now unwatched.
     *  Caller must hold {@link #lock}; {@code watchedBy} is updated by the caller. */
    private void detachWatcher(String watcher, String watched) {
        Set<String> w = watchers.get(watched);
        if (w != null) {
            w.remove(watcher);
            if (w.isEmpty()) {
                watchers.remove(watched);
                try {
                    logger.debug("Unbind watch {}", watched);
                    rabbitMqService.unbindWatch(watched);
                } catch (AmqpApplicationContextClosedException e) {
                    logger.debug("Context closed; skipping watch unbind for {}", watched);
                }
            }
        }
    }

    private Set<String> snapshotWatchers(String watched) {
        synchronized (lock) {
            Set<String> w = watchers.get(watched);
            return w == null ? Set.of() : new HashSet<>(w);
        }
    }

    // --- Frame serialization (shared DTOs) ---------------------------------------

    private static String presenceFrame(String userId, PresenceStatus status) {
        return toJson(new PresenceEvent()
                .type(PresenceEvent.TypeEnum.PRESENCE)
                .userId(UUID.fromString(userId))
                .status(status));
    }

    private static String typingFrame(String userId) {
        return toJson(new TypingEvent()
                .type(TypingEvent.TypeEnum.TYPING)
                .userId(UUID.fromString(userId)));
    }

    private static String toJson(Object frame) {
        try {
            return MAPPER.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize presence frame", e);
        }
    }
}
