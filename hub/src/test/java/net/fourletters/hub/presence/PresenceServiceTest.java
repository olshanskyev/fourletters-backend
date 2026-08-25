package net.fourletters.hub.presence;

import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.session.HubSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    private PresenceService presence;

    @Mock
    private HubRabbitMqService rabbitMqService;

    @Mock
    private HubSessionRegistry registry;

    @BeforeEach
    void setUp() {
        presence = new PresenceService(rabbitMqService, registry);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    // --- Local user lifecycle ----------------------------------------------------

    @Test
    void onUserOnlineBindsProbeTargetAndAnnouncesOnline() {
        String user = uuid();

        presence.onUserOnline(user);

        verify(rabbitMqService).bindPresence(user);
        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishToWatch(eq(user), frame.capture());
        assertTrue(frame.getValue().contains("\"status\":\"online\""));
        assertTrue(frame.getValue().contains(user));
    }

    @Test
    void onUserOfflineAnnouncesOfflineAndReleasesWatches() {
        String user = uuid();
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(false);
        presence.subscribe(user, watched); // user watches someone

        presence.onUserOffline(user);

        verify(rabbitMqService).unbindPresence(user);
        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishToWatch(eq(user), frame.capture());
        assertTrue(frame.getValue().contains("\"status\":\"offline\""));
        // user was the only watcher of `watched`, so its watch key is released
        verify(rabbitMqService).unbindWatch(watched);
    }

    // --- Subscribe / snapshot ----------------------------------------------------

    @Test
    void subscribeToOnlineContactBindsAndAnswersOnlineImmediately() {
        String watcher = uuid();
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(true);

        presence.subscribe(watcher, watched);

        verify(rabbitMqService).bindWatch(watched);
        verify(rabbitMqService, never()).probePresence(anyString());
        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(registry).sendToUser(eq(watcher), frame.capture());
        assertTrue(frame.getValue().contains("\"status\":\"online\""));
        assertTrue(frame.getValue().contains(watched));
    }

    @Test
    void subscribeToOfflineContactProbes() {
        String watcher = uuid();
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(false);

        presence.subscribe(watcher, watched);

        verify(rabbitMqService).bindWatch(watched);
        verify(rabbitMqService).probePresence(watched);
        verify(registry, never()).sendToUser(anyString(), anyString());
    }

    @Test
    void secondWatcherOfSameContactDoesNotRebind() {
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(false);

        presence.subscribe(uuid(), watched);
        presence.subscribe(uuid(), watched);

        verify(rabbitMqService, times(1)).bindWatch(watched);
    }

    @Test
    void subscribeIgnoresNullWatched() {
        presence.subscribe(uuid(), null);
        verify(rabbitMqService, never()).bindWatch(anyString());
    }

    @Test
    void unsubscribeLastWatcherUnbinds() {
        String watcher = uuid();
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(false);
        presence.subscribe(watcher, watched);

        presence.unsubscribe(watcher, watched);

        verify(rabbitMqService).unbindWatch(watched);
    }

    // --- Typing ------------------------------------------------------------------

    @Test
    void typingPublishesTypingFrame() {
        String user = uuid();

        presence.typing(user);

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishToWatch(eq(user), frame.capture());
        assertTrue(frame.getValue().contains("\"type\":\"typing\""));
        assertTrue(frame.getValue().contains(user));
    }

    // --- Inbound presence.exchange deliveries ------------------------------------

    @Test
    void onWatchEventForwardsBodyToWatchers() {
        String watcher = uuid();
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(false);
        presence.subscribe(watcher, watched);

        presence.onWatchEvent(watched, "BODY");

        verify(registry).sendToUser(watcher, "BODY");
    }

    @Test
    void onProbeReannouncesOnlineWhenWeOwnTheUser() {
        String probed = uuid();
        when(registry.isOnline(probed)).thenReturn(true);

        presence.onProbe(probed);

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishToWatch(eq(probed), frame.capture());
        assertTrue(frame.getValue().contains("\"status\":\"online\""));
    }

    @Test
    void onProbeIgnoredWhenUserNotLocallyOnline() {
        String probed = uuid();
        when(registry.isOnline(probed)).thenReturn(false);

        presence.onProbe(probed);

        verify(rabbitMqService, never()).publishToWatch(anyString(), anyString());
    }

    @Test
    void returnedProbeNotifiesWatchersOffline() {
        String watcher = uuid();
        String watched = uuid();
        when(registry.isOnline(watched)).thenReturn(false);
        presence.subscribe(watcher, watched);

        // The offline signal arrives via the returns-callback the service registered on construction.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(rabbitMqService).setProbeReturnedHandler(callback.capture());
        callback.getValue().accept(watched);

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(registry).sendToUser(eq(watcher), frame.capture());
        assertTrue(frame.getValue().contains("\"status\":\"offline\""));
    }
}
