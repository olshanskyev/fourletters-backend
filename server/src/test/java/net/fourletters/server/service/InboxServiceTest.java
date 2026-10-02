package net.fourletters.server.service;

import net.fourletters.dto.*;
import net.fourletters.server.broker.ServerRabbitMqService;
import net.fourletters.server.model.InboxMessage;
import net.fourletters.server.model.InboxPending;
import net.fourletters.server.repository.InboxMessageRepository;
import net.fourletters.server.repository.InboxPendingRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock
    private InboxPendingRepository pendingRepository;
    @Mock
    private GroupService groupService;

    @Mock
    private PushNotificationService pushNotificationService;

    private InboxService service;

    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(repository.findPendingForRecipient(any()))
                .thenReturn(Collections.emptyList());
        // hold window and backstop delays of 0s so flushExpired() treats accepted messages as expired.
        service = newService(0L, 0L, 0L);
    }

    private InboxService newService(long holdWindow, long backstopDelay, long callBackstopDelay) {
        return new InboxService(rabbitMqService, repository, pendingRepository, groupService,
                new PendingReceipts(), pushNotificationService, holdWindow, backstopDelay, callBackstopDelay);
    }

    private EncryptedMessage newMessage() {
        EncryptedMessage m = new EncryptedMessage();
        m.setMessageId(UUID.randomUUID());
        m.setRecipientId(recipient);
        m.setPayload("cipher");
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
        verify(pendingRepository, never()).save(any());
        verify(pendingRepository, never()).deleteByMessageIdAndRecipientId(any(), any());
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
        assertThat(saved.getSenderId()).isEqualTo(sender);
        // A pending row is written for the single recipient.
        ArgumentCaptor<InboxPending> pendingCaptor = ArgumentCaptor.forClass(InboxPending.class);
        verify(pendingRepository).save(pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getRecipientId()).isEqualTo(recipient);

        // Unacknowledged past the backstop delay, so the recipient is woken with a best-effort push
        // (backstop for a silently-dropped Hub binding), and the marker is released on flush.
        verify(pushNotificationService)
                .notifyRecipient(eq(recipient), eq(sender), any(), eq(m.getMessageId()), isNull());
        verify(pushNotificationService).clearPushed(m.getMessageId(), recipient);

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
        when(repository.findPendingForRecipient(recipient))
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
        row.setSenderId(sender);
        row.setPayload("cipher");
        // Nothing in the hot tier; the message was already flushed.
        when(repository.findById(messageId)).thenReturn(Optional.of(row));

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(messageId);
        receipt.setType(ReceiptType.READ);
        receipt.setSignature("sig");
        service.recordReceipt(recipient, receipt);

        verify(pendingRepository).deleteByMessageIdAndRecipientId(messageId, recipient);
        verify(rabbitMqService).publishReceipt(any(), any());
    }

    @Test
    void receiptFromWrongRecipientIsIgnored() {
        UUID messageId = UUID.randomUUID();
        when(repository.findById(any())).thenReturn(Optional.empty());

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(messageId);
        receipt.setType(ReceiptType.DELIVERED);
        receipt.setSignature("sig");
        service.recordReceipt(UUID.randomUUID(), receipt);

        verify(rabbitMqService, never()).publishReceipt(any(), any());
    }

    @Test
    void undecryptableReceiptDropsCopyAndRelaysAsNegativeAck() {
        EncryptedMessage m = newMessage();
        AcceptedResponse accepted = service.accept(m, sender);

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(accepted.getMessageId());
        receipt.setOriginalSenderId(sender);
        receipt.setType(ReceiptType.UNDECRYPTABLE);
        receipt.setSignature("sig");
        service.recordReceipt(recipient, receipt);

        // The copy is dropped while still in the hot tier (no key can decrypt it) — zero DB writes.
        verify(repository, never()).save(any());
        verify(pendingRepository, never()).deleteByMessageIdAndRecipientId(any(), any());

        // It is relayed to the sender as MESSAGE_UNDECRYPTABLE (not a delivery), preserving type.
        ArgumentCaptor<ReceiptEvent> eventCaptor = ArgumentCaptor.forClass(ReceiptEvent.class);
        verify(rabbitMqService).publishReceipt(eq(sender), eventCaptor.capture());
        ReceiptEvent relayed = eventCaptor.getValue();
        assertThat(relayed.getEvent()).isEqualTo(ReceiptEvent.EventEnum.MESSAGE_UNDECRYPTABLE);
        assertThat(relayed.getData().getType()).isEqualTo(ReceiptType.UNDECRYPTABLE);
        assertThat(relayed.getData().getMessageId()).isEqualTo(m.getMessageId());
        assertThat(relayed.getData().getRecipientId()).isEqualTo(recipient);

        // The dropped copy is gone from the inbox afterward.
        assertThat(service.getInbox(recipient).getMessages()).isEmpty();
    }

    @Test
    void backstopFiresBeforeFlushAndOnlyOncePerMessage() {
        service = newService(30L, 0L, 0L);
        EncryptedMessage m = newMessage();
        service.accept(m, sender);

        service.flushExpired();
        service.flushExpired();

        verify(pushNotificationService, times(1))
                .notifyRecipient(eq(recipient), eq(sender), any(), eq(m.getMessageId()), isNull());
        // Still within the hold window: nothing is written to the cold tier yet.
        verify(repository, never()).save(any());
    }

    @Test
    void acknowledgedMessageIsNeverBackstopPushed() {
        service = newService(30L, 0L, 0L);
        EncryptedMessage m = newMessage();
        service.accept(m, sender);
        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(m.getMessageId());
        receipt.setType(ReceiptType.DELIVERED);
        receipt.setSignature("sig");
        service.recordReceipt(recipient, receipt);

        service.flushExpired();

        verify(pushNotificationService, never()).notifyRecipient(any(), any(), any(), any(), any());
    }

    @Test
    void callOfferUsesTheShorterBackstopDelay() {
        service = newService(30L, 30L, 0L);
        EncryptedMessage chat = newMessage();
        EncryptedMessage offer = newMessage();
        offer.setHint(MessageHint.CALL);
        service.accept(chat, sender);
        service.accept(offer, sender);

        service.flushExpired();

        verify(pushNotificationService)
                .notifyRecipient(eq(recipient), eq(sender), any(), eq(offer.getMessageId()), eq(MessageHint.CALL));
        verify(pushNotificationService, never())
                .notifyRecipient(any(), any(), any(), eq(chat.getMessageId()), any());
    }

    @Test
    void callHintIsPublishedAsHeaderButNeverRelayedOrStored() {
        EncryptedMessage offer = newMessage();
        offer.setHint(MessageHint.CALL);
        service.accept(offer, sender);

        ArgumentCaptor<EncryptedMessage> published = ArgumentCaptor.forClass(EncryptedMessage.class);
        verify(rabbitMqService).publishMessage(published.capture(), eq(MessageHint.CALL));
        assertThat(published.getValue().getHint()).isNull();
        assertThat(service.getInbox(recipient).getMessages())
                .allSatisfy(msg -> assertThat(msg.getHint()).isNull());
    }

    @Test
    void groupMessageIgnoresCallHint() {
        UUID groupId = UUID.randomUUID();
        when(groupService.groupRoster(groupId)).thenReturn(List.of(sender, recipient));
        EncryptedMessage m = new EncryptedMessage();
        m.setMessageId(UUID.randomUUID());
        m.setGroupId(groupId);
        m.setPayload("cipher");
        m.setHint(MessageHint.CALL);

        service.accept(m, sender);
        service.flushExpired();

        verify(rabbitMqService).publishMessage(any(), isNull());
        verify(pushNotificationService)
                .notifyRecipient(eq(recipient), eq(sender), eq(groupId), eq(m.getMessageId()), isNull());
    }

    @Test
    void rejectsBackstopDelayLongerThanHoldWindow() {
        assertThatThrownBy(() -> newService(30L, 31L, 5L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newService(30L, 10L, 31L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void receiptIsRetainedForSenderInboxPullEvenWhenRelayedLive() {
        EncryptedMessage m = newMessage();
        AcceptedResponse accepted = service.accept(m, sender);

        DeliveryReceipt receipt = new DeliveryReceipt();
        receipt.setMessageId(accepted.getMessageId());
        receipt.setOriginalSenderId(sender);
        receipt.setType(ReceiptType.DELIVERED);
        receipt.setSignature("sig");
        service.recordReceipt(recipient, receipt);

        // Always retained (in addition to the live relay), so a zombie sender binding still recovers
        // the ack on the sender's next /inbox pull.
        verify(rabbitMqService).publishReceipt(eq(sender), any());
        InboxResponse senderInbox = service.getInbox(sender);
        assertThat(senderInbox.getReceipts()).hasSize(1);
        assertThat(senderInbox.getReceipts().get(0).getMessageId()).isEqualTo(m.getMessageId());
        assertThat(senderInbox.getReceipts().get(0).getType()).isEqualTo(ReceiptType.DELIVERED);
    }
}



