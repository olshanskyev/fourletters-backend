package net.fourletters.server.service.authentication;

public interface IdentityService {
    record UserInfo(String id, String firstName, String lastName, String avatarUrl) {}

    class IdentityVerificationException extends Exception {
        public IdentityVerificationException(String message) {
            super(message);
        }
        public IdentityVerificationException(String message, Throwable throwable) {
            super(message, throwable);
        }
    }

    AuthService.AuthProvider provider();

    UserInfo getUserInfo(String accessToken) throws IdentityVerificationException;
}

