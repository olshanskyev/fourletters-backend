package net.fourletters.server.service;

import net.fourletters.dto.DeliveryReceipt;
import net.fourletters.dto.MessageReceipt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory backstop for delivery/read acknowledgements owed to an <b>offline sender</b>.
 *
 * <p>Receipts are relayed live to the sender over RabbitMQ; only those that come back
 * <b>unroutable</b> (sender offline) are retained here, so an online sender — who already
 * received the ack live — never accumulates duplicates to be re-sent on its next
 * {@code GET /inbox}. It is deliberately <b>non-durable</b> (plain heap, like the message hot
 * tier): a Server restart drops pending receipts, after which the sender's restart-triggered
 * resync re-drives them.
 *
 * <p>{@code read} supersedes {@code delivered} for the same message, so only the latest status
 * per message is retained.
 */
@Component
public class PendingReceipts {

    private record Entry(UUID recipientId, DeliveryReceipt.TypeEnum type) {}

    /** senderId -> (messageId -> latest status). */
    private final Map<UUID, Map<UUID, Entry>> bySender = new ConcurrentHashMap<>();

    /** Record an acknowledgement owed to {@code senderId}; {@code read} wins over {@code delivered}. */
    public void record(UUID senderId, UUID messageId, UUID recipientId, DeliveryReceipt.TypeEnum type) {
        bySender.computeIfAbsent(senderId, s -> new ConcurrentHashMap<>())
                .merge(messageId, new Entry(recipientId, type),
                        (oldE, newE) -> newE.type() == DeliveryReceipt.TypeEnum.READ ? newE : oldE);
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
            receipt.setType(e.getValue().type() == DeliveryReceipt.TypeEnum.READ
                    ? MessageReceipt.TypeEnum.READ
                    : MessageReceipt.TypeEnum.DELIVERED);
            receipts.add(receipt);
        }
        return receipts;
    }
}

