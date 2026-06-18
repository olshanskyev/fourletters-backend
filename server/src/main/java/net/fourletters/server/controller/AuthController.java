package net.fourletters.server.controller;


import net.fourletters.server.service.authentication.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import net.fourletters.dto.AuthRequest;
import net.fourletters.dto.UserResponse;
import net.fourletters.server.service.UserService;
import org.springframework.web.server.ResponseStatusException;
import net.fourletters.dto.RefreshError;

@RestController
@RequestMapping("/auth")
class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final IdentityServiceFactory identityServiceFactory;
    private final AuthService authService;
    private final UserService userService;

    public AuthController(IdentityServiceFactory identityServiceFactory, AuthService authService, UserService userService) {
        this.identityServiceFactory = identityServiceFactory;
        this.authService = authService;
        this.userService = userService;
    }

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private ResponseCookie buildRefreshTokenCookie(String refreshToken, Long maxAge) {
        String path = contextPath + "/auth/refresh";
        return ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true)
                .secure(true)
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    private ResponseEntity<?> buildAuthOk(AuthService.AuthResult result) {
        ResponseCookie refreshCookie = buildRefreshTokenCookie(result.refreshToken(), result.refreshTokenMaxAge());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(result.response());
    }

    @PostMapping("/{provider}")
    public ResponseEntity<?> auth(@PathVariable("provider") String provider,
                                  @RequestBody AuthRequest authRequest)
            throws IdentityService.IdentityVerificationException {
        logger.debug("auth request provider={}", provider);
        if (authRequest == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Auth request is empty");
        }

        AuthService.AuthProvider authProvider = AuthService.AuthProvider.fromString(provider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Auth Provider"));

        IdentityService identityService = identityServiceFactory.getIdentityService(authProvider);
        IdentityService.UserInfo userInfo = identityService.getUserInfo(authRequest.getToken());

        AuthService.AuthResult result = authService.processAuth(userInfo, authProvider);
        return buildAuthOk(result);
    }

    @PostMapping(value = "/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            @RequestHeader(value = "X-Session-ID", required = false) String sessionId
    ) {
        logger.debug("Refresh request for user");
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("No refresh token provided");
        }
        if (sessionId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("No X-Session-ID header is provided");
        }

        try {
            AuthService.AuthResult result = authService.processRefresh(refreshToken, sessionId);
            return buildAuthOk(result);
        } catch (AuthService.InvalidTokenException e) {
            logger.warn("Refresh token validation failed: {}", e.getMessage());
            // Clear the cookie when refresh fails
            ResponseCookie deleteCookie = buildRefreshTokenCookie("", 0L);
            RefreshError refreshError = new RefreshError();
            refreshError.setReason(e.getReason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                    .body(refreshError);
        }
    }

    @PostMapping(value = "/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader){
        logger.debug("Logout");

        authService.logout(authHeader);

        // Clear the refresh token cookie
        ResponseCookie deleteCookie = buildRefreshTokenCookie("", 0L);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .build();
    }

    @GetMapping(value = "/user")
    public ResponseEntity<?> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String userIdStr)) {
            logger.warn("Unexpected principal type: {}", principal == null ? "null" : principal.getClass().getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            logger.warn("Invalid user id: {}", userIdStr);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unexpected user id");
        }

        java.util.Optional<UserResponse> userOpt = userService.getUserResponseById(userId);
        return userOpt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());

    }

}
