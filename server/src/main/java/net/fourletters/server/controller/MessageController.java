package net.fourletters.server.controller;

import net.fourletters.dto.AcceptedResponse;
import net.fourletters.dto.DeliveryReceipt;
import net.fourletters.dto.EncryptedMessage;
import net.fourletters.dto.InboxResponse;
import net.fourletters.dto.MessageBatchRequest;
import net.fourletters.dto.MessageBatchResponse;
import net.fourletters.dto.ReceiptBatchRequest;
import net.fourletters.server.service.InboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fourletters.server.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * Maximum length (chars) of an opaque message payload.
     */
    private static final int MAX_PAYLOAD_LENGTH = 10 * 1024 * 1024;

    private final InboxService inboxService;

    public MessageController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    @PostMapping(value = "/messages", produces = "application/json")
    public ResponseEntity<AcceptedResponse> sendMessage(@RequestBody EncryptedMessage message) {
        UUID senderId = SecurityUtils.currentUserId();
        if (senderId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (message.getPayload().isBlank() || message.getPayload().length() > MAX_PAYLOAD_LENGTH) {
            return ResponseEntity.badRequest().build();
        }
        // A message targets exactly one destination: a 1:1 recipient or a group (never both/neither).
        boolean hasRecipient = message.getRecipientId() != null;
        boolean hasGroup = message.getGroupId() != null;
        if (hasRecipient == hasGroup) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(inboxService.accept(message, senderId));
    }

    @PostMapping(value = "/messages/batch", produces = "application/json")
    public ResponseEntity<MessageBatchResponse> sendMessages(@RequestBody MessageBatchRequest request) {
        UUID senderId = SecurityUtils.currentUserId();
        if (senderId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<EncryptedMessage> messages = request != null ? request.getMessages() : null;
        if (messages == null || messages.isEmpty() || messages.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        for (EncryptedMessage message : messages) {
            if (message.getPayload().isBlank() || message.getPayload().length() > MAX_PAYLOAD_LENGTH) {
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(inboxService.acceptAll(messages, senderId));
    }

    @GetMapping(value = "/inbox", produces = "application/json")
    public ResponseEntity<InboxResponse> getInbox() {
        UUID recipientId = SecurityUtils.currentUserId();
        if (recipientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(inboxService.getInbox(recipientId));
    }

    @PostMapping(value = "/receipts", produces = "application/json")
    public ResponseEntity<Void> submitReceipt(@RequestBody DeliveryReceipt receipt) {

        UUID recipientId = SecurityUtils.currentUserId();
        if (recipientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (receipt == null) {
            return ResponseEntity.badRequest().build();
        }
        inboxService.recordReceipt(recipientId, receipt);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/receipts/batch", produces = "application/json")
    public ResponseEntity<Void> submitReceipts(@RequestBody ReceiptBatchRequest request) {
        UUID recipientId = SecurityUtils.currentUserId();
        if (recipientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<DeliveryReceipt> receipts = request != null ? request.getReceipts() : null;
        if (receipts == null || receipts.isEmpty() || receipts.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        inboxService.recordReceipts(recipientId, receipts);
        return ResponseEntity.noContent().build();
    }
}
