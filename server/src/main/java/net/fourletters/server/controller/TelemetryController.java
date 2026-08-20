package net.fourletters.server.controller;

import net.fourletters.dto.TelemetryAcceptedResponse;
import net.fourletters.dto.TelemetryLogBatch;
import net.fourletters.server.service.TelemetryService;
import net.fourletters.server.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping(value = "/logs", consumes = "application/json")
    public ResponseEntity<TelemetryAcceptedResponse> submitLogs(@RequestBody TelemetryLogBatch batch) {
        UUID userId = SecurityUtils.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            int accepted = telemetryService.persist(userId, batch);
            return ResponseEntity.accepted().body(new TelemetryAcceptedResponse()
                    .status(TelemetryAcceptedResponse.StatusEnum.ACCEPTED)
                    .acceptedCount(accepted));
        } catch (TelemetryService.RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
