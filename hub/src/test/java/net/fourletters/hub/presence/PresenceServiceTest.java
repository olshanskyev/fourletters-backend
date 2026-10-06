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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void typingPublishesOnlyToRecipient() {
        String user = uuid();
        String recipient = uuid();

        presence.typing(user, recipient);

        ArgumentCaptor<String> frame = ArgumentCaptor.forClass(String.class);
        verify(rabbitMqService).publishTypingUser(eq(recipient), frame.capture());
        assertTrue(frame.getValue().contains("\"type\":\"typing\""));
        assertTrue(frame.getValue().contains(user));
        assertFalse(frame.getValue().contains("groupId"));
        verify(rabbitMqService, never()).publishToWatch(anyString(), anyString());
    }

    @Test
    void typingWithoutValidRecipientIsIgnored() {
        String user = uuid();

        presence.typing(user, null);
        presence.typing(user, "");
        presence.typing(user, "not-a-uuid");
        presence.typing(user, "1-1-1-1-1");

        verify(rabbitMqService, never()).publishTypingUser(anyString(), anyString());
        verify(rabbitMqService, never()).publishToWatch(anyString(), anyString());
    }

    // --- Inbound presence.exchange deliveries ------------------------------------

    @Test
    void typingGroupWithoutValidGroupIsIgnored() {
        String user = uuid();
        presence.typingGroup(user, null);
        presence.typingGroup(user, "bad-id");
        presence.typingGroup(user, "1-1-1-1-1");
        verify(rabbitMqService, never()).publishTypingGroup(anyString(), anyString());
    }

    @Test
    void groupBindingIsSharedAndReleasedByLastWatcher() {
        String group = uuid();
        String first = uuid();
        String second = uuid();
        presence.subscribeGroupTyping(first, group);
        presence.subscribeGroupTyping(first, group);
        presence.subscribeGroupTyping(second, group);
        verify(rabbitMqService, times(1)).bindTypingGroup(group);

        presence.unsubscribeGroupTyping(first, group);
        verify(rabbitMqService, never()).unbindTypingGroup(group);
        presence.unsubscribeGroupTyping(second, group);
        verify(rabbitMqService).unbindTypingGroup(group);
    }

    @Test
    void groupEventsReachOnlyWatchersAndDisconnectCleansUp() {
        String group = uuid();
        String otherGroup = uuid();
        String watcher = uuid();
        String other = uuid();
        presence.subscribeGroupTyping(watcher, group);
        presence.subscribeGroupTyping(other, otherGroup);

        presence.onGroupTypingEvent(group, "BODY");
        verify(registry).sendToUser(watcher, "BODY");
        verify(registry, never()).sendToUser(other, "BODY");

        presence.onUserOffline(watcher);
        verify(rabbitMqService).unbindTypingGroup(group);
        presence.onGroupTypingEvent(group, "AFTER");
        verify(registry, never()).sendToUser(watcher, "AFTER");
        verify(rabbitMqService, never()).unbindTypingGroup(otherGroup);
    }

    @Test
    void sharedWatchMapsKeepPresenceAndGroupRoutesSeparate() {
        String target = uuid();
        String presenceWatcher = uuid();
        String groupWatcher = uuid();
        presence.subscribe(presenceWatcher, target);
        presence.subscribeGroupTyping(groupWatcher, target);

        presence.onWatchEvent(target, "PRESENCE");
        presence.onGroupTypingEvent(target, "TYPING");
        verify(registry).sendToUser(presenceWatcher, "PRESENCE");
        verify(registry).sendToUser(groupWatcher, "TYPING");
        verify(registry, never()).sendToUser(groupWatcher, "PRESENCE");
        verify(registry, never()).sendToUser(presenceWatcher, "TYPING");

        presence.subscribeGroupTyping(presenceWatcher, target);
        presence.onUserOffline(presenceWatcher);
        verify(rabbitMqService).unbindWatch(target);
        verify(rabbitMqService, never()).unbindTypingGroup(target);
        presence.onGroupTypingEvent(target, "AFTER");
        verify(registry).sendToUser(groupWatcher, "AFTER");
        verify(registry, never()).sendToUser(presenceWatcher, "AFTER");

        presence.onUserOffline(groupWatcher);
        verify(rabbitMqService).unbindTypingGroup(target);
    }

    @Test
    void invalidGroupSubscriptionsAreIgnored() {
        presence.subscribeGroupTyping(uuid(), null);
        presence.subscribeGroupTyping(uuid(), "bad-id");
        presence.unsubscribeGroupTyping(uuid(), "1-1-1-1-1");
        verify(rabbitMqService, never()).bindTypingGroup(anyString());
        verify(rabbitMqService, never()).unbindTypingGroup(anyString());
    }

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
