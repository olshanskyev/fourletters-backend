package net.fourletters.server.controller;

import net.fourletters.dto.PushSubscription;
import net.fourletters.server.service.PushNotificationService;
import net.fourletters.server.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/push")
public class PushController {

    private final PushNotificationService pushNotificationService;

    public PushController(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    @PostMapping(value = "/subscribe", consumes = "application/json")
    public ResponseEntity<Void> subscribe(@RequestBody PushSubscription subscription,
                                          @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            pushNotificationService.register(userId, subscription, userAgent);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscribe")
    public ResponseEntity<Void> unsubscribe() {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        pushNotificationService.unregister(userId);
        return ResponseEntity.noContent().build();
    }
}
