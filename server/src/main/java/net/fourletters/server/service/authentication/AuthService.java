package net.fourletters.server.service.authentication;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

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

    private record TokenPair(JwtTokenCreator.TokenDetails accessToken, JwtTokenCreator.TokenDetails refreshToken, String sessionId) {};

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
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResult processRefresh(String refreshTokenValue, String sessionId) throws InvalidTokenException {
        Optional<RefreshToken> rTokenOpt = refreshTokenRepository.findByTokenAndSessionId(refreshTokenValue, sessionId);
        if (rTokenOpt.isEmpty()) {
            throw new InvalidTokenException("Invalid refresh token or session id. Token is not found");
        }

        Optional<io.jsonwebtoken.Claims> claimsOpt = jwtTokenVerifier.parseClaims(refreshTokenValue);
        if (claimsOpt.isEmpty()) {
            // delete the token from DB if it's invalid or expired
            refreshTokenRepository.delete(rTokenOpt.get());
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        RefreshToken rToken = rTokenOpt.get();
        User user = rToken.getUser();

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