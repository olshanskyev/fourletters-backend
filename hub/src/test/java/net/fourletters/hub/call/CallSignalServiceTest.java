package net.fourletters.hub.call;

import net.fourletters.hub.broker.HubRabbitMqService;
import net.fourletters.hub.session.HubSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CallSignalServiceTest {

    @Mock
    private HubRabbitMqService rabbitMqService;

    private CallSignalService calls;

    private final String sender = UUID.randomUUID().toString();
    private final String recipient = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        calls = new CallSignalService(rabbitMqService, new HubSessionRegistry());
    }

    @Test
    void relaysValidSignalToRecipient() {
        calls.relay(sender, recipient, "1.abc");

        verify(rabbitMqService).publishCallSignal(eq(recipient), anyString());
    }

    @Test
    void acceptsPayloadAtTheSizeLimit() {
        calls.relay(sender, recipient, "x".repeat(CallSignalService.MAX_PAYLOAD_LENGTH));

        verify(rabbitMqService).publishCallSignal(eq(recipient), anyString());
    }

    @Test
    void dropsOversizedPayload() {
        calls.relay(sender, recipient, "x".repeat(CallSignalService.MAX_PAYLOAD_LENGTH + 1));

        verify(rabbitMqService, never()).publishCallSignal(anyString(), anyString());
    }

    @Test
    void dropsMissingOrBlankPayload() {
        calls.relay(sender, recipient, null);
        calls.relay(sender, recipient, " ");

        verify(rabbitMqService, never()).publishCallSignal(anyString(), anyString());
    }

    @Test
    void dropsInvalidRecipient() {
        calls.relay(sender, null, "1.abc");
        calls.relay(sender, "not-a-uuid", "1.abc");

        verify(rabbitMqService, never()).publishCallSignal(anyString(), anyString());
    }
}
