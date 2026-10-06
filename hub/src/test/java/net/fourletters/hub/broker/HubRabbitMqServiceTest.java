package net.fourletters.hub.broker;

import net.fourletters.broker.RabbitMqTopology;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HubRabbitMqServiceTest {

    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final HubRabbitMqService broker = new HubRabbitMqService(admin, template);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typingBindingsUseDestinationRoutesOnPresenceExchange(boolean group) {
        broker.useProvisionedQueue("hub.queue.test");
        if (group) {
            broker.bindTypingGroup("target");
            broker.unbindTypingGroup("target");
        } else {
            broker.bindTypingUser("target");
            broker.unbindTypingUser("target");
        }

        var bound = ArgumentCaptor.forClass(Binding.class);
        var unbound = ArgumentCaptor.forClass(Binding.class);
        verify(admin).declareBinding(bound.capture());
        verify(admin).removeBinding(unbound.capture());
        for (Binding binding : new Binding[]{bound.getValue(), unbound.getValue()}) {
            assertEquals("hub.queue.test", binding.getDestination());
            assertEquals(RabbitMqTopology.PRESENCE_EXCHANGE, binding.getExchange());
            assertEquals(group ? "typing.group.target" : "typing.user.target", binding.getRoutingKey());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typingPublicationsAreNonPersistentAndExpire(boolean group) {
        String frame = "{\"type\":\"typing\"}";
        if (group) {
            broker.publishTypingGroup("target", frame);
        } else {
            broker.publishTypingUser("target", frame);
        }

        var processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(eq(RabbitMqTopology.PRESENCE_EXCHANGE),
                eq(group ? "typing.group.target" : "typing.user.target"), eq(frame), processor.capture());
        Message message = processor.getValue().postProcessMessage(
                new Message(new byte[0], new MessageProperties()));
        assertEquals(MessageDeliveryMode.NON_PERSISTENT, message.getMessageProperties().getDeliveryMode());
        assertEquals("3000", message.getMessageProperties().getExpiration());
    }
}