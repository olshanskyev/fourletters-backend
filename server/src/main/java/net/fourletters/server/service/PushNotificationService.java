package net.fourletters.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.fourletters.dto.PushSubscription;
import net.fourletters.server.model.Group;
import net.fourletters.server.model.PushSubscriptionEntity;
import net.fourletters.server.model.User;
import net.fourletters.server.repository.GroupRepository;
import net.fourletters.server.repository.PushSubscriptionRepository;
import net.fourletters.server.repository.UsersRepository;
import net.fourletters.configuration.ProxyResolver;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends a Web Push (VAPID) wake-up to an offline recipient.
 */
@Service
public class PushNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(PushNotificationService.class);

    private static final String GENERIC_BODY = "New message";
    private static final String DEFAULT_ICON = "/web-app-manifest-192x192.png";
    /** Monochrome silhouette shown as the Android status-bar (small) icon. */
    private static final String BADGE_ICON = "/notification-badge.svg";
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_GONE = 410;

    private final PushSubscriptionRepository subscriptionRepository;
    private final UsersRepository usersRepository;
    private final GroupRepository groupRepository;
    private final ObjectMapper objectMapper;

    private final String vapidPublicKey;
    private final String vapidPrivateKey;
    private final String vapidSubject;
    private final long debounceMillis;

    /** Last-sent epoch millis per recipient, used to collapse bursts. */
    private final ConcurrentHashMap<UUID, Long> lastPushAt = new ConcurrentHashMap<>();

    /**
     * Single-threaded executor for the cheap CPU/DB work: subscription lookup, payload build
     */
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-push-sender");
        t.setDaemon(true);
        return t;
    });

    private PushService pushService;

    /** Proxy-aware Apache async client used to POST to the browser vendor's push endpoint. */
    private CloseableHttpAsyncClient httpClient;

    public PushNotificationService(PushSubscriptionRepository subscriptionRepository,
                                   UsersRepository usersRepository,
                                   GroupRepository groupRepository,
                                   ObjectMapper objectMapper,
                                   @Value("${push.vapid.public-key:}") String vapidPublicKey,
                                   @Value("${push.vapid.private-key:}") String vapidPrivateKey,
                                   @Value("${push.vapid.subject:}") String vapidSubject,
                                   @Value("${push.debounce-seconds:15}") long debounceSeconds) {
        this.subscriptionRepository = subscriptionRepository;
        this.usersRepository = usersRepository;
        this.groupRepository = groupRepository;
        this.objectMapper = objectMapper;
        this.vapidPublicKey = vapidPublicKey;
        this.vapidPrivateKey = vapidPrivateKey;
        this.vapidSubject = vapidSubject;
        this.debounceMillis = debounceSeconds * 1000L;
    }

    @PostConstruct
    void init() {
        if (vapidPublicKey.isBlank() || vapidPrivateKey.isBlank()) {
            logger.info("Web Push disabled: no VAPID key pair configured (push.vapid.*)");
            return;
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        try {
            pushService = new PushService(vapidPublicKey, vapidPrivateKey,
                    vapidSubject.isBlank() ? null : vapidSubject);
            httpClient = buildHttpClient();
            httpClient.start();
            logger.info("Web Push enabled (VAPID)");
        } catch (Exception e) {
            logger.error("Web Push disabled: failed to initialize VAPID PushService", e);
            pushService = null;
            httpClient = null;
        }
    }

    /**
     * Build the async HTTP client that talks to the push endpoint
     */
    private CloseableHttpAsyncClient buildHttpClient() {
        HttpAsyncClientBuilder builder = HttpAsyncClients.custom();
        ProxyResolver.ProxyEntry proxy = ProxyResolver.getSystemProxy();
        if (proxy != null) {
            builder.setProxy(new HttpHost(proxy.host(), proxy.port()));
            logger.info("Web Push routing through proxy {}:{}", proxy.host(), proxy.port());
        }
        return builder.build();
    }

    @PreDestroy
    void shutdown() {
        sender.shutdownNow();
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception e) {
                logger.debug("Error closing Web Push HTTP client", e);
            }
        }
    }

    /**
     * Register (upsert) the caller's single active Web Push subscription. A new registration
     * replaces any previous one for the user (single-device model) and resets the debounce.
     */
    @Transactional
    public void register(UUID userId, PushSubscription dto, String userAgent) {
        if (dto == null || dto.getEndpoint().isBlank() || dto.getKeys().getP256dh().isBlank() || dto.getKeys().getAuth().isBlank()) {
            throw new IllegalArgumentException("Push subscription must carry endpoint and keys");
        }
        PushSubscriptionEntity entity = subscriptionRepository.findById(userId)
                .orElseGet(PushSubscriptionEntity::new);
        entity.setUserId(userId);
        entity.setEndpoint(dto.getEndpoint());
        entity.setP256dh(dto.getKeys().getP256dh());
        entity.setAuth(dto.getKeys().getAuth());
        entity.setUserAgent(userAgent);
        subscriptionRepository.save(entity);
        lastPushAt.remove(userId);
        logger.debug("Registered push subscription for {}", userId);
    }

    /** Remove the caller's push subscription (logout / notifications disabled). Idempotent. */
    @Transactional
    public void unregister(UUID userId) {
        if (subscriptionRepository.existsById(userId)) {
            subscriptionRepository.deleteById(userId);
        }
        lastPushAt.remove(userId);
        logger.debug("Removed push subscription for {}", userId);
    }

    /**
     * Wake an offline recipient with a metadata-only push. No-op when push is disabled, the
     * recipient has no subscription, or a push was already sent within the debounce window.
     *
     * @param recipientId the offline user to wake
     * @param senderId    the message sender (for the notification title/avatar and click routing)
     * @param groupId     the group id for a group message, or {@code null} for a 1:1 message
     */
    public void notifyRecipient(UUID recipientId, UUID senderId, UUID groupId) {
        if (pushService == null || recipientId == null) {
            return;
        }
        if (!passesDebounce(recipientId)) {
            return;
        }
        sender.execute(() -> send(recipientId, senderId, groupId));
    }

    /** Returns true at most once per debounce window for a given recipient. */
    private boolean passesDebounce(UUID recipientId) {
        long now = System.currentTimeMillis();
        AtomicBoolean allow = new AtomicBoolean(false);
        lastPushAt.compute(recipientId, (key, previous) -> {
            if (previous != null && now - previous < debounceMillis) {
                return previous;
            }
            allow.set(true);
            return now;
        });
        return allow.get();
    }

    private void send(UUID recipientId, UUID senderId, UUID groupId) {
        try {
            Optional<PushSubscriptionEntity> subscription = subscriptionRepository.findById(recipientId);
            if (subscription.isEmpty()) {
                return;
            }
            PushSubscriptionEntity sub = subscription.get();
            byte[] payload = buildPayload(recipientId, senderId, groupId).getBytes(StandardCharsets.UTF_8);
            Notification notification = new Notification(sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), payload);

            // Use our proxy-aware client with the library-prepared POST (encryption + VAPID headers).
            HttpPost httpPost = pushService.preparePost(notification, Encoding.AES128GCM);
            httpClient.execute(httpPost, new FutureCallback<>() {
                @Override
                public void completed(HttpResponse response) {
                    onSendResult(recipientId, response.getStatusLine().getStatusCode());
                }

                @Override
                public void failed(Exception ex) {
                    logger.warn("Failed to send push notification to {}", recipientId, ex);
                }

                @Override
                public void cancelled() {
                    logger.debug("Push notification to {} was cancelled", recipientId);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to prepare push notification for {}", recipientId, e);
        }
    }

    /**
     * Handle a push response status.
     */
    private void onSendResult(UUID recipientId, int status) {
        if (status == HTTP_NOT_FOUND || status == HTTP_GONE) {
            // The push subscription is dead; drop it so we stop trying.
            sender.execute(() -> {
                subscriptionRepository.deleteById(recipientId);
                logger.debug("Pruned stale push subscription for {} (status {})", recipientId, status);
            });
        } else if (status >= 300) {
            logger.warn("Push to {} returned status {}", recipientId, status);
        } else {
            logger.debug("Sent push wake-up to {}", recipientId);
        }
    }

    /**
     * Build the ngsw-worker push payload: a top-level {@code notification} object the Angular
     * service worker shows automatically and surfaces via {@code SwPush.notificationClicks}. The
     * click carries {@code senderId}/{@code groupId} so the client resolves its local conversation.
     */
    private String buildPayload(UUID recipientId, UUID senderId, UUID groupId) {
        User sender = senderId != null ? usersRepository.findById(senderId).orElse(null) : null;
        String title = resolveTitle(sender, groupId);
        String icon = resolveIcon(sender);

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode notification = root.putObject("notification");
        notification.put("title", title);
        notification.put("body", GENERIC_BODY);
        notification.put("icon", icon);
        // Android renders the small status-bar icon monochrome (alpha only);
        notification.put("badge", BADGE_ICON);
        // Large media (sender avatar). iOS shows it as the expanded image; the payload icon is
        // ignored there. Only an absolute URL is fetchable by the OS push service.
        String image = resolveImage(sender);
        if (image != null) {
            notification.put("image", image);
        }
        // Collapse repeated wake-ups for the same recipient into one visible notification.
        notification.put("tag", "fourletters-" + recipientId);
        notification.put("renotify", true);

        ObjectNode data = notification.putObject("data");
        if (senderId != null) {
            data.put("senderId", senderId.toString());
        }
        if (groupId != null) {
            data.put("groupId", groupId.toString());
        }
        // ngsw click handling: focus/open the app; the client then routes to the conversation.
        ObjectNode onActionClick = data.putObject("onActionClick");
        ObjectNode defaultAction = onActionClick.putObject("default");
        defaultAction.put("operation", "openWindow");
        defaultAction.put("url", "/m");

        return root.toString();
    }

    /** Group name for a group message; otherwise the sender's display name. */
    private String resolveTitle(User sender, UUID groupId) {
        if (groupId != null) {
            String name = groupRepository.findById(groupId).map(Group::getName).orElse(null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        if (sender != null) {
            String name = sender.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return "Fourletters";
    }

    private String resolveIcon(User sender) {
        if (sender != null) {
            String avatar = sender.getAvatarUrl();
            if (avatar != null && !avatar.isBlank()) {
                return avatar;
            }
        }
        return DEFAULT_ICON;
    }

    private String resolveImage(User sender) {
        if (sender != null) {
            String avatar = sender.getAvatarUrl();
            if (avatar != null && (avatar.startsWith("https://") || avatar.startsWith("http://"))) {
                return avatar;
            }
        }
        return null;
    }
}
