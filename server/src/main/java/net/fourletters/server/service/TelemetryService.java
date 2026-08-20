package net.fourletters.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.fourletters.dto.TelemetryLogBatch;
import net.fourletters.dto.TelemetryLogRecord;
import net.fourletters.dto.TelemetryResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists client OpenTelemetry LogRecords (warnings/errors from the PWA) to a JSON-lines file for
 * offline analysis. Each record is enriched with the caller's user id and a server receive time,
 * and the batch's resource attributes are merged in so every line is self-contained (Loki/grep
 * friendly). The payload is diagnostics only — it never carries end-to-end message content.
 */
@Service
public class TelemetryService {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long RATE_WINDOW_MILLIS = 60_000L;
    private static final int MAX_BODY_LENGTH = 8192;

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Path logFile;
    private final int maxBatch;
    private final int ratePerMin;

    /** Serializes appends to the single JSON-lines file across concurrent requests. */
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<UUID, Window> rateWindows = new ConcurrentHashMap<>();

    public TelemetryService(ObjectMapper objectMapper,
                            @Value("${telemetry.enabled:true}") boolean enabled,
                            @Value("${telemetry.log-file:./logs/client-telemetry.jsonl}") String logFilePath,
                            @Value("${telemetry.max-batch:500}") int maxBatch,
                            @Value("${telemetry.rate-per-min:600}") int ratePerMin) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.logFile = Path.of(logFilePath);
        this.maxBatch = maxBatch;
        this.ratePerMin = ratePerMin;
    }

    /**
     * Validate, rate-limit and append a batch. Returns the number of records written.
     *
     * @throws IllegalArgumentException        the batch is empty or exceeds the configured cap
     * @throws RateLimitExceededException      the caller exceeded the per-minute record budget
     */
    public int persist(UUID userId, TelemetryLogBatch batch) {
        if (!enabled) {
            return 0;
        }
        List<TelemetryLogRecord> records = batch == null ? null : batch.getLogRecords();
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("Telemetry batch is empty");
        }
        if (records.size() > maxBatch) {
            throw new IllegalArgumentException("Telemetry batch exceeds max size " + maxBatch);
        }
        if (!allowRate(userId, records.size())) {
            throw new RateLimitExceededException();
        }

        long observedNanos = System.currentTimeMillis() * NANOS_PER_MILLI;
        TelemetryResource resource = batch.getResource();
        List<String> lines = new ArrayList<>(records.size());
        for (TelemetryLogRecord record : records) {
            lines.add(toJsonLine(userId, resource, observedNanos, record));
        }
        writeLines(lines);
        return lines.size();
    }

    /** Serialize one enriched LogRecord to a compact JSON line. */
    private String toJsonLine(UUID userId, TelemetryResource resource, long observedNanos,
                              TelemetryLogRecord record) {
        ObjectNode node = objectMapper.valueToTree(record);
        node.put("observedTimeUnixNano", Long.toString(observedNanos));
        if (node.hasNonNull("body") && node.get("body").asText().length() > MAX_BODY_LENGTH) {
            node.put("body", node.get("body").asText().substring(0, MAX_BODY_LENGTH));
        }

        ObjectNode attributes = node.has("attributes") && node.get("attributes").isObject()
                ? (ObjectNode) node.get("attributes")
                : node.putObject("attributes");
        attributes.put("user.id", userId.toString());
        if (resource != null) {
            putIfPresent(attributes, "service.name", resource.getServiceName());
            putIfPresent(attributes, "service.version", resource.getServiceVersion());
            putIfPresent(attributes, "session.id", resource.getSessionId());
            putIfPresent(attributes, "user_agent.original", resource.getUserAgent());
        }
        return node.toString();
    }

    private void writeLines(List<String> lines) {
        synchronized (writeLock) {
            try {
                Path parent = logFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (String line : lines) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to persist {} telemetry record(s) to {}", lines.size(), logFile, e);
            }
        }
    }

    /** Fixed 60s window per user; returns false once the record budget for the window is used up. */
    private boolean allowRate(UUID userId, int count) {
        long now = System.currentTimeMillis();
        Window window = rateWindows.compute(userId, (key, existing) -> {
            if (existing == null || now - existing.startMillis >= RATE_WINDOW_MILLIS) {
                return new Window(now, count);
            }
            existing.count += count;
            return existing;
        });
        return window.count <= ratePerMin;
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    /** Mutable per-user rate window; mutated only inside ConcurrentHashMap.compute (atomic). */
    private static final class Window {
        private final long startMillis;
        private int count;

        private Window(long startMillis, int count) {
            this.startMillis = startMillis;
            this.count = count;
        }
    }

    /** Raised when a caller exceeds the per-minute telemetry budget (mapped to HTTP 429). */
    public static class RateLimitExceededException extends RuntimeException {
    }
}
