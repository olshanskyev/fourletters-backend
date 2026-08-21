package net.fourletters.server.service;

import net.fourletters.dto.MessageReceipt;
import net.fourletters.dto.ReceiptType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory backstop for delivery/read/undecryptable acknowledgements owed to a sender.
 *
 * <p>Every relayed receipt is retained here (in addition to the best-effort live publish), so an
 * ack survives a <b>zombie sender binding</b> — a live publish routed to a silently-dropped
 * connection. The sender pulls owed acks on its next {@code GET /inbox} and applies them
 * idempotently. It is non-durable (plain heap, like the message hot tier): a Server restart drops
 * pending receipts, after which the sender's restart-triggered resync re-drives them.
 *
 * <p>{@code read} supersedes {@code delivered} for the same message; otherwise the latest status
 * per message is retained.
 */
@Component
public class PendingReceipts {

    private record Entry(UUID recipientId, ReceiptType type, String signature) {}

    /** senderId -> (messageId -> latest status). */
    private final Map<UUID, Map<UUID, Entry>> bySender = new ConcurrentHashMap<>();

    /** Record an acknowledgement owed to {@code senderId}; {@code read} is sticky, else newest wins. */
    public void record(UUID senderId, UUID messageId, UUID recipientId, ReceiptType type, String signature) {
        bySender.computeIfAbsent(senderId, s -> new ConcurrentHashMap<>())
                .merge(messageId, new Entry(recipientId, type, signature),
                        (oldE, newE) -> oldE.type() == ReceiptType.READ ? oldE : newE);
    }

    /** Return and clear all pending acknowledgements for {@code senderId}. */
    public List<MessageReceipt> drain(UUID senderId) {
        Map<UUID, Entry> pending = bySender.remove(senderId);
        if (pending == null) {
            return List.of();
        }
        List<MessageReceipt> receipts = new ArrayList<>(pending.size());
        for (Map.Entry<UUID, Entry> e : pending.entrySet()) {
            MessageReceipt receipt = new MessageReceipt();
            receipt.setMessageId(e.getKey());
            receipt.setRecipientId(e.getValue().recipientId());
            // Return the real type so an undecryptable NACK still drives the sender's re-key path.
            receipt.setType(e.getValue().type());
            receipt.setSignature(e.getValue().signature());
            receipts.add(receipt);
        }
        return receipts;
    }
}

