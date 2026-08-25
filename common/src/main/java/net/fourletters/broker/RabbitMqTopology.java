package net.fourletters.broker;

/**
 * Names of the RabbitMQ objects shared between the Server and the Hub.
 *
 * <p>Phase 1 topology is intentionally tiny: a single live fan-out exchange,
 * one auto-delete queue per Hub, and one {@code user.{id}} binding per online user.
 * RabbitMQ carries best-effort live traffic only and holds no durability
 * responsibility (see doc/algorithms/rabbitmq-exchange.md).
 */
public final class RabbitMqTopology {

    /** Topic exchange the Server publishes accepted messages to. */
    public static final String MESSAGES_EXCHANGE = "messages.exchange";

    /** Routing-key prefix for per-recipient delivery: {@code user.{recipientId}}. */
    public static final String ROUTING_KEY_PREFIX = "user.";

    /** Name prefix for the auto-delete queue the Server provisions per Hub. */
    public static final String HUB_QUEUE_PREFIX = "hub.queue.";

    /** Topic exchange carrying live presence/typing metadata between Hubs (declared in definitions.json). */
    public static final String PRESENCE_EXCHANGE = "presence.exchange";

    /** Routing-key prefix an owner Hub binds/publishes as a user's online marker: {@code presence.{userId}}. */
    public static final String PRESENCE_KEY_PREFIX = "presence.";

    /** Routing-key prefix a watcher Hub binds and events are published to: {@code watch.{userId}}. */
    public static final String WATCH_KEY_PREFIX = "watch.";

    private RabbitMqTopology() {
    }
}
