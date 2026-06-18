package net.fourletters.server.service.authentication;

import net.fourletters.dto.AuthResponse;
import net.fourletters.dto.UserResponse;
import net.fourletters.dto.RefreshError;
import net.fourletters.server.model.RefreshToken;
import net.fourletters.server.model.User;
import net.fourletters.server.repository.OAuthIdentityRepository;
import net.fourletters.server.repository.RefreshTokenRepository;
import net.fourletters.server.service.UserService;
import net.fourletters.token.JwtTokenCreator;
import net.fourletters.token.JwtTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private OAuthIdentityRepository oauthIdentityRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private JwtTokenCreator jwtTokenCreator;
    @Mock
    private JwtTokenVerifier jwtTokenVerifier;

    private AuthService authService;

    private final User user = new User();

    @BeforeEach
    void setUp() {
        authService = new AuthService(userService, oauthIdentityRepository, refreshTokenRepository, jwtTokenCreator, jwtTokenVerifier);
        user.setId(UUID.randomUUID());
    }

    @Test
    void refreshWithRevokedTokenThrowsRevoked() {
        String token = "old-token";
        String sessionId = "sid";

        RefreshToken anyToken = new RefreshToken();
        anyToken.setToken(token);
        anyToken.setRevoked(true);

        when(refreshTokenRepository.findByTokenAndSessionId(token, sessionId)).thenReturn(Optional.empty());
        when(refreshTokenRepository.findByToken(token)).thenReturn(Optional.of(anyToken));

        assertThatThrownBy(() -> authService.processRefresh(token, sessionId))
                .isInstanceOf(AuthService.InvalidTokenException.class)
                .satisfies(ex -> {
                    AuthService.InvalidTokenException ite = (AuthService.InvalidTokenException) ex;
                    assertThat(ite.getReason()).isEqualTo(RefreshError.ReasonEnum.REVOKED);
                });

        verify(refreshTokenRepository).delete(anyToken);
    }

    @Test
    void refreshWithExpiredTokenThrowsExpired() {
        String token = "expired-token";
        String sessionId = "sid2";

        RefreshToken rToken = new RefreshToken();
        rToken.setToken(token);
        rToken.setSessionId(sessionId);
        rToken.setUser(user);
        // expiry in the past
        rToken.setExpiryDate(new Date(System.currentTimeMillis() - 10_000));

        when(refreshTokenRepository.findByTokenAndSessionId(token, sessionId)).thenReturn(Optional.of(rToken));
        when(jwtTokenVerifier.parseClaims(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.processRefresh(token, sessionId))
                .isInstanceOf(AuthService.InvalidTokenException.class)
                .satisfies(ex -> {
                    AuthService.InvalidTokenException ite = (AuthService.InvalidTokenException) ex;
                    assertThat(ite.getReason()).isEqualTo(RefreshError.ReasonEnum.EXPIRED);
                });

        verify(refreshTokenRepository).delete(rToken);
    }

    @Test
    void refreshWithInvalidTokenThrowsInvalid() {
        String token = "invalid-token";
        String sessionId = "sid3";

        RefreshToken rToken = new RefreshToken();
        rToken.setToken(token);
        rToken.setSessionId(sessionId);
        rToken.setUser(user);
        // expiry in the future (so not expired) but JWT parse fails -> invalid
        rToken.setExpiryDate(new Date(System.currentTimeMillis() + 60_000));

        when(refreshTokenRepository.findByTokenAndSessionId(token, sessionId)).thenReturn(Optional.of(rToken));
        when(jwtTokenVerifier.parseClaims(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.processRefresh(token, sessionId))
                .isInstanceOf(AuthService.InvalidTokenException.class)
                .satisfies(ex -> {
                    AuthService.InvalidTokenException ite = (AuthService.InvalidTokenException) ex;
                    assertThat(ite.getReason()).isEqualTo(RefreshError.ReasonEnum.INVALID);
                });

        verify(refreshTokenRepository).delete(rToken);
    }

    @Test
    void refreshSuccessGeneratesNewTokens() throws Exception {
        String token = "good-token";
        String sessionId = "sid4";

        RefreshToken rToken = new RefreshToken();
        rToken.setToken(token);
        rToken.setSessionId(sessionId);
        rToken.setUser(user);
        rToken.setExpiryDate(new Date(System.currentTimeMillis() + 60_000));

        when(refreshTokenRepository.findByTokenAndSessionId(token, sessionId)).thenReturn(Optional.of(rToken));

        // token parses correctly
        when(jwtTokenVerifier.parseClaims(token)).thenReturn(Optional.of(mock(io.jsonwebtoken.Claims.class)));

        // jwtTokenCreator should produce tokens for saving
        JwtTokenCreator.TokenDetails newAccess = new JwtTokenCreator.TokenDetails("access", new Date(System.currentTimeMillis() + 30_000), 30);
        JwtTokenCreator.TokenDetails newRefresh = new JwtTokenCreator.TokenDetails("refresh-new", new Date(System.currentTimeMillis() + 120_000), 120);
        lenient().when(jwtTokenCreator.generateAccessToken(anyString(), any(), anyString())).thenReturn(newAccess);
        lenient().when(jwtTokenCreator.generateRefreshToken(anyString(), anyString())).thenReturn(newRefresh);

        // userService mapping
        UserResponse userResp = new UserResponse();
        userResp.setId(UUID.randomUUID());
        userResp.setUsername("bob");
        userResp.setRoles(java.util.List.of("USER"));
        when(userService.mapToResponse(user)).thenReturn(userResp);

        var result = authService.processRefresh(token, sessionId);

        assertThat(result).isNotNull();
        AuthResponse resp = result.response();
        assertThat(resp.getAccessToken()).isEqualTo("access");

        // old token should be deleted and new refresh saved
        verify(refreshTokenRepository).delete(rToken);
        verify(refreshTokenRepository).save(org.mockito.ArgumentMatchers.argThat(rt -> "refresh-new".equals(rt.getToken())));
    }
}

