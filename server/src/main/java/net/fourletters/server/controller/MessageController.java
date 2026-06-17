package net.fourletters.server.controller;

import net.fourletters.dto.AcceptedResponse;
import net.fourletters.dto.DeliveryReceipt;
import net.fourletters.dto.EncryptedMessage;
import net.fourletters.dto.InboxResponse;
import net.fourletters.dto.MessageBatchRequest;
import net.fourletters.dto.MessageBatchResponse;
import net.fourletters.server.service.InboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Message send / inbox sync / delivery receipts
 */
@RestController
public class MessageController {

    private static final Logger logger = LoggerFactory.getLogger(MessageController.class);

    /** Maximum messages accepted in one /messages/batch call; clients chunk larger resyncs. */
    private static final int MAX_BATCH_SIZE = 100;

    private final InboxService inboxService;

    public MessageController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @PostMapping(value = "/messages", produces = "application/json")
    public ResponseEntity<AcceptedResponse> sendMessage(@RequestBody EncryptedMessage message) {
        UUID senderId = currentUserId();
        if (senderId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (message.getPayload().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(inboxService.accept(message, senderId));
    }

    @PostMapping(value = "/messages/batch", produces = "application/json")
    public ResponseEntity<MessageBatchResponse> sendMessages(@RequestBody MessageBatchRequest request) {
        UUID senderId = currentUserId();
        if (senderId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<EncryptedMessage> messages = request != null ? request.getMessages() : null;
        if (messages == null || messages.isEmpty() || messages.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        for (EncryptedMessage message : messages) {
            if (message.getPayload().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(inboxService.acceptAll(messages, senderId));
    }

    @GetMapping(value = "/inbox", produces = "application/json")
    public ResponseEntity<InboxResponse> getInbox() {
        UUID recipientId = currentUserId();
        if (recipientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(inboxService.getInbox(recipientId));
    }

    @PostMapping("/receipts")
    public ResponseEntity<Void> submitReceipt(@RequestBody DeliveryReceipt receipt) {
        logger.info("got read request {}", receipt.toString());

        UUID recipientId = currentUserId();
        if (recipientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (receipt == null) {
            return ResponseEntity.badRequest().build();
        }
        inboxService.recordReceipt(recipientId, receipt);
        return ResponseEntity.noContent().build();
    }

    /** The authenticated user's id (JWT subject is the user UUID), or null if unauthenticated. */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof String subject)) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            logger.warn("Authenticated principal is not a UUID: {}", subject);
            return null;
        }
    }
}
