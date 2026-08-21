package net.fourletters.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fourletters.dto.TelemetryLogBatch;
import net.fourletters.dto.TelemetryLogRecord;
import net.fourletters.dto.TelemetryResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for telemetry persistence. Pure — no Spring, no database. Writes JSON lines to a
 * temp file and asserts the enrichment (user id, receive time, resource attributes) and the
 * validation / rate-limit guards.
 */
class TelemetryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private TelemetryService service(boolean enabled, int maxBatch, int ratePerMin, Path file) {
        return new TelemetryService(mapper, enabled, file.toString(), maxBatch, ratePerMin);
    }

    private TelemetryLogRecord record(String body) {
        return new TelemetryLogRecord()
                .timeUnixNano("1700000000000000000")
                .severityText(TelemetryLogRecord.SeverityTextEnum.ERROR)
                .severityNumber(17)
                .body(body)
                .attributes(Map.of("url.path", "/m"));
    }

    private TelemetryLogBatch batch(TelemetryLogRecord... records) {
        return new TelemetryLogBatch()
                .resource(new TelemetryResource()
                        .serviceName("fourletters-gui")
                        .sessionId("session-1")
                        .userAgent("jest-UA"))
                .logRecords(new ArrayList<>(List.of(records)));
    }

    @Test
    void persistWritesOneEnrichedJsonLinePerRecord() throws IOException {
        Path file = tempDir.resolve("telemetry.jsonl");
        TelemetryService service = service(true, 500, 600, file);

        int written = service.persist(userId, batch(record("first"), record("second")));

        assertThat(written).isEqualTo(2);
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);

        JsonNode first = mapper.readTree(lines.get(0));
        assertThat(first.get("body").asText()).isEqualTo("first");
        assertThat(first.get("severityText").asText()).isEqualTo("ERROR");
        assertThat(first.get("timeUnixNano").asText()).isEqualTo("1700000000000000000");
        // Server receive time is added as an OTLP/JSON int64 string.
        assertThat(first.get("observedTimeUnixNano").isTextual()).isTrue();

        JsonNode attributes = first.get("attributes");
        assertThat(attributes.get("user.id").asText()).isEqualTo(userId.toString());
        assertThat(attributes.get("service.name").asText()).isEqualTo("fourletters-gui");
        assertThat(attributes.get("session.id").asText()).isEqualTo("session-1");
        assertThat(attributes.get("user_agent.original").asText()).isEqualTo("jest-UA");
        // Client-supplied attribute is preserved.
        assertThat(attributes.get("url.path").asText()).isEqualTo("/m");
    }

    @Test
    void appendsAcrossMultipleCalls() throws IOException {
        Path file = tempDir.resolve("telemetry.jsonl");
        TelemetryService service = service(true, 500, 600, file);

        service.persist(userId, batch(record("a")));
        service.persist(userId, batch(record("b")));

        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void rejectsEmptyBatch() {
        Path file = tempDir.resolve("telemetry.jsonl");
        TelemetryService service = service(true, 500, 600, file);

        assertThatThrownBy(() -> service.persist(userId, batch()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBatchOverMaxSize() {
        Path file = tempDir.resolve("telemetry.jsonl");
        TelemetryService service = service(true, 1, 600, file);

        assertThatThrownBy(() -> service.persist(userId, batch(record("a"), record("b"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesPerUserRateLimit() {
        Path file = tempDir.resolve("telemetry.jsonl");
        TelemetryService service = service(true, 500, 2, file);

        service.persist(userId, batch(record("a"), record("b"))); // 2 records, at the budget
        assertThatThrownBy(() -> service.persist(userId, batch(record("c"))))
                .isInstanceOf(TelemetryService.RateLimitExceededException.class);
    }

    @Test
    void disabledServiceWritesNothing() {
        Path file = tempDir.resolve("telemetry.jsonl");
        TelemetryService service = service(false, 500, 600, file);

        int written = service.persist(userId, batch(record("a")));

        assertThat(written).isZero();
        assertThat(Files.exists(file)).isFalse();
    }
}
