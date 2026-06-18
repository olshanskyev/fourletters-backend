package net.fourletters.server.service;

import net.fourletters.dto.*;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.InboxMessage;
import net.fourletters.server.repository.InboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two-tier inbox: hot-tier hold, durable flush, union reads, and
 * cross-tier receipt handling.
 */
@ExtendWith(MockitoExtension.class)
class InboxServiceTest {

    @Mock
    private ServerRabbitMqService rabbitMqService;
    @Mock
    private InboxMessageRepository repository;

    private InboxService service;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(repository.findByRecipientIdOrderByCreatedAtAsc(any()))
                .thenReturn(Collections.emptyList());
        // hold window of 0s so flushExpired() treats accepted messages as already expired.
        service = new InboxService(rabbitMqService, repository, new PendingReceipts(), 0L);
    }

    private EncryptedMessage newMessage() {
        EncryptedMessage m = new EncryptedMessage();
        m.setMessageId(UUID.randomUUID());
        m.setRecipientId(recipient);
        m.setPayload("cipher");
        m.setSignature("sig");
        return m;
    }

    @Test
    void confirmedWithinWindowCostsZeroDbWrites() {
        EncryptedMessage m = newMessage();
        AcceptedResponse accepted = service.accept(m, sender);

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(accepted.getMessageId());
        receipt.setType(ReceiptType.DELIVERED);
        receipt.setSignature("sig");
        service.recordReceipt(recipient, receipt);

        // Confirmed while still in the hot tier: the whole point of the hot tier is that
        // this path touches the database zero times — no read, no delete, no write.
        verify(repository, never()).save(any());
        verify(repository, never()).findById(any());
        verify(repository, never()).deleteByMessageId(any());
        verify(rabbitMqService).publishReceipt(any(), any());
    }

    @Test
    void expiredMessageIsFlushedThenEvicted() {
        EncryptedMessage m = newMessage();
        service.accept(m, sender);

        service.flushExpired();

        ArgumentCaptor<InboxMessage> captor = ArgumentCaptor.forClass(InboxMessage.class);
        verify(repository).save(captor.capture());
        InboxMessage saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo(m.getMessageId());
        assertThat(saved.getRecipientId()).isEqualTo(recipient);
        assertThat(saved.getSenderId()).isEqualTo(sender);

        // After eviction the hot tier no longer returns it (only the cold tier would).
        InboxResponse response = service.getInbox(recipient);
        assertThat(response.getMessages()).isEmpty();
    }

    @Test
    void getInboxReturnsUnionOfBothTiers() {
        // One message already flushed to the cold tier...
        EncryptedMessage cold = newMessage();
        cold.setSenderId(sender);
        InboxMessage coldRow = InboxMessage.from(cold);
        when(repository.findByRecipientIdOrderByCreatedAtAsc(recipient))
                .thenReturn(List.of(coldRow));

        // ...and one accepted live into the hot tier.
        EncryptedMessage hot = newMessage();
        service.accept(hot, sender);

        InboxResponse response = service.getInbox(recipient);

        assertThat(response.getMessages()).hasSize(2);
        // Cold (older) first, then the still-held hot message.
        assertThat(response.getMessages().get(0).getMessageId()).isEqualTo(cold.getMessageId());
        assertThat(response.getMessages().get(1).getMessageId()).isEqualTo(hot.getMessageId());
    }

    @Test
    void receiptAfterFlushDeletesDurableRowAndRelays() {
        UUID messageId = UUID.randomUUID();
        InboxMessage row = new InboxMessage();
        row.setMessageId(messageId);
        row.setRecipientId(recipient);
        row.setSenderId(sender);
        row.setPayload("cipher");
        row.setSignature("sig");
        // Nothing in the hot tier; the message was already flushed.
        when(repository.findById(messageId)).thenReturn(Optional.of(row));

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(messageId);
        receipt.setType(ReceiptType.READ);
        receipt.setSignature("sig");
        service.recordReceipt(recipient, receipt);

        verify(repository).deleteByMessageId(messageId);
        verify(rabbitMqService).publishReceipt(any(), any());
    }

    @Test
    void receiptFromWrongRecipientIsIgnored() {
        UUID messageId = UUID.randomUUID();
        when(repository.findById(messageId)).thenReturn(Optional.empty());

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(messageId);
        receipt.setType(ReceiptType.DELIVERED);
        receipt.setSignature("sig");
        service.recordReceipt(UUID.randomUUID(), receipt);

        verify(rabbitMqService, never()).publishReceipt(any(), any());
    }
}



