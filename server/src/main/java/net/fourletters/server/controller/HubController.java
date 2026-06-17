package net.fourletters.server.controller;

import net.fourletters.dto.HubRegistrationRequest;
import net.fourletters.dto.HubRegistrationResponse;
import net.fourletters.server.service.HubRegistrationService;
import net.fourletters.token.JwtTokenVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hub registration endpoint. A Hub calls this on boot with its registration bearer
 * token; the Server authenticates it and provisions the Hub's relay queue.
 */
@RestController
@RequestMapping("/hubs")
public class HubController {

    private final HubRegistrationService hubRegistrationService;

    public HubController(HubRegistrationService hubRegistrationService) {
        this.hubRegistrationService = hubRegistrationService;
    }

    @PostMapping(value = "/register", produces = "application/json")
    public ResponseEntity<HubRegistrationResponse> register(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) HubRegistrationRequest request) {

        String token = JwtTokenVerifier.getTokenFromBearerString(authorization);
        if (!hubRegistrationService.isValidToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(hubRegistrationService.register(request));
    }
}
