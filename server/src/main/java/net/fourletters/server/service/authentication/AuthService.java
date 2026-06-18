package net.fourletters.server.service.authentication;

import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import io.jsonwebtoken.Claims;
import net.fourletters.server.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import net.fourletters.dto.AuthResponse;
import net.fourletters.dto.UserResponse;
import net.fourletters.server.model.OAuthIdentity;
import net.fourletters.server.model.RefreshToken;
import net.fourletters.server.model.User;
import net.fourletters.server.repository.OAuthIdentityRepository;
import net.fourletters.server.repository.RefreshTokenRepository;
import net.fourletters.token.JwtTokenCreator;
import net.fourletters.token.JwtTokenVerifier;
import net.fourletters.dto.RefreshError;

@Service
public class AuthService {

    public enum AuthProvider {
        VK,
        GOOGLE;
        public static Optional<AuthProvider> fromString(String s) {
            if (s == null) return Optional.empty();
            try {
                return Optional.of(AuthProvider.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private final String DEFAULT_ROLE = "USER";

    private final UserService userService;
    private final OAuthIdentityRepository oauthIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenCreator jwtTokenCreator;
    private final JwtTokenVerifier jwtTokenVerifier;

    @Autowired
    public AuthService(UserService userService,
                       OAuthIdentityRepository oauthIdentityRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtTokenCreator jwtTokenCreator,
                       JwtTokenVerifier jwtTokenVerifier) {
        this.userService = userService;
        this.oauthIdentityRepository = oauthIdentityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenCreator = jwtTokenCreator;
        this.jwtTokenVerifier = jwtTokenVerifier;
    }

    private record TokenPair(JwtTokenCreator.TokenDetails accessToken, JwtTokenCreator.TokenDetails refreshToken, String sessionId) {}

    private TokenPair generateTokensForUser(User user) {
        String sessionId = UUID.randomUUID().toString();
        JwtTokenCreator.TokenDetails accessTokenObj = jwtTokenCreator.generateAccessToken(user.getId().toString(), user.getRoles(), sessionId);
        JwtTokenCreator.TokenDetails refreshTokenObj = jwtTokenCreator.generateRefreshToken(user.getId().toString(), sessionId);
        return new TokenPair(accessTokenObj, refreshTokenObj, sessionId);
    }

    private AuthResult buildAuthResult(User user, JwtTokenCreator.TokenDetails accessToken, JwtTokenCreator.TokenDetails refreshToken) {
        UserResponse userResponse = userService.mapToResponse(user);

        AuthResponse authResponse = new AuthResponse();
        authResponse.setAccessToken(accessToken.token());
        authResponse.setUser(userResponse);

        return new AuthResult(authResponse, refreshToken.token(), refreshToken.validitySeconds());
    }

    private void safeRefreshToken(JwtTokenCreator.TokenDetails refreshTokenObj, User user, String sessionId) {
        String refreshTokenValue = refreshTokenObj.token();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(refreshTokenValue);
        refreshToken.setSessionId(sessionId);
        refreshToken.setUser(user);

        refreshToken.setExpiryDate(refreshTokenObj.expiryDate());
        refreshTokenRepository.save(refreshToken);
    }

    public record AuthResult(AuthResponse response, String refreshToken, long refreshTokenMaxAge) {}

    @Transactional
    public AuthResult processAuth(IdentityService.UserInfo userInfo, AuthProvider provider) {

        String providerUserId = String.valueOf(userInfo.id());
        String providerName = provider.name();
        // find existing OAuth linking
        Optional<OAuthIdentity> identityOpt = oauthIdentityRepository.findByProviderAndProviderUserId(providerName, providerUserId);

        User user;
        if (identityOpt.isPresent()) {
            user = identityOpt.get().getUser();
        } else {
            user = new User();
            user.setName(userInfo.firstName() + " " + userInfo.lastName());
            if (user.getName().trim().isEmpty()) {
                user.setName("User" + providerUserId);
            }
            user.setAvatarUrl(userInfo.avatarUrl());
            user.setRoles(new String[]{DEFAULT_ROLE});
            user = userService.save(user);

            OAuthIdentity identity = new OAuthIdentity();
            identity.setUser(user);
            identity.setProvider(providerName);
            identity.setProviderUserId(providerUserId);
            oauthIdentityRepository.save(identity);
        }

        // revoke existing refresh tokens for single-active-device policy
        java.util.List<RefreshToken> existing = refreshTokenRepository.findAllByUser(user);
        if (existing != null && !existing.isEmpty()) {
            for (RefreshToken t : existing) {
                t.setRevoked(true);
            }
            refreshTokenRepository.saveAll(existing);
        }

        // generate tokens
        TokenPair tokenPair = generateTokensForUser(user);
        JwtTokenCreator.TokenDetails accessTokenObj = tokenPair.accessToken();
        JwtTokenCreator.TokenDetails refreshTokenObj = tokenPair.refreshToken();

        // save refresh token
        safeRefreshToken(refreshTokenObj, user, tokenPair.sessionId());

        // prepare response
        return buildAuthResult(user, accessTokenObj, refreshTokenObj);
    }

    public static class InvalidTokenException extends Exception {
        private final RefreshError.ReasonEnum reason;

        public InvalidTokenException(RefreshError.ReasonEnum reason, String message) {
            super(message);
            this.reason = reason;
        }

        public RefreshError.ReasonEnum getReason() { return reason; }
    }

    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResult processRefresh(String refreshTokenValue, String sessionId) throws InvalidTokenException {
        Optional<RefreshToken> rTokenOpt = refreshTokenRepository.findByTokenAndSessionId(refreshTokenValue, sessionId);

        // If exact token+session not found, try to detect whether token exists and was revoked or belongs to another session
        if (rTokenOpt.isEmpty()) {
            Optional<RefreshToken> anyTokenOpt = refreshTokenRepository.findByToken(refreshTokenValue);
            if (anyTokenOpt.isPresent()) {
                RefreshToken anyToken = anyTokenOpt.get();
                if (anyToken.isRevoked()) {
                    // token was revoked by a newer login
                    // remove the revoked token record
                    refreshTokenRepository.delete(anyToken);
                    throw new InvalidTokenException(RefreshError.ReasonEnum.REVOKED, "Refresh token was revoked by a newer login");
                } else {
                    // token exists but sessionId mismatch -> possible tampering
                    throw new InvalidTokenException(RefreshError.ReasonEnum.INVALID, "Refresh token session id mismatch");
                }
            }
            // token not found at all
            throw new InvalidTokenException(RefreshError.ReasonEnum.INVALID, "Invalid refresh token or session id. Token is not found");
        }

        Optional<Claims> claimsOpt = jwtTokenVerifier.parseClaims(refreshTokenValue);
        RefreshToken rToken = rTokenOpt.get();
        User user = rToken.getUser();

        if (claimsOpt.isEmpty()) {
            // determine whether token expired or malformed by checking stored expiry
            Date expiry = rToken.getExpiryDate();
            if (expiry != null && expiry.before(new java.util.Date())) {
                // expired
                refreshTokenRepository.delete(rToken);
                throw new InvalidTokenException(RefreshError.ReasonEnum.EXPIRED, "Refresh token is expired");
            } else {
                // invalid/malformed
                refreshTokenRepository.delete(rToken);
                throw new InvalidTokenException(RefreshError.ReasonEnum.INVALID, "Refresh token is invalid");
            }
        }

        // clear old token from DB
        refreshTokenRepository.delete(rToken);

        // generate tokens
        TokenPair tokenPair = generateTokensForUser(user);
        JwtTokenCreator.TokenDetails accessTokenObj = tokenPair.accessToken();
        JwtTokenCreator.TokenDetails refreshTokenObj = tokenPair.refreshToken();

        // Save new refresh token
        safeRefreshToken(refreshTokenObj, user, tokenPair.sessionId());

        // Prepare response
        return buildAuthResult(user, accessTokenObj, refreshTokenObj);
    }

    @Transactional
    public void logout(String authHeader) {
        String accessToken = JwtTokenVerifier.getTokenFromBearerString(authHeader);
        if (accessToken != null) {
            // we need to parse claim ignoring expiration
            Optional<io.jsonwebtoken.Claims> claimsOpt = jwtTokenVerifier.parseClaimsIgnoreExpiration(accessToken);
            if (claimsOpt.isPresent() && claimsOpt.get().getId() != null) {
                refreshTokenRepository.deleteBySessionId(claimsOpt.get().getId());
            }
        }
    }
}