package net.fourletters.service.authentication;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.springframework.stereotype.Service;

@Service
class GoogleIdentityService implements IdentityService {

    private final GoogleIdTokenVerifier tokenVerifier;

    public GoogleIdentityService(GoogleIdTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public UserInfo getUserInfo(String token) throws IdentityVerificationException {
        try {
            GoogleIdToken idToken = tokenVerifier.verify(token);
            if (idToken == null) {
                throw new IdentityVerificationException("Invalid Google ID token");
            }
            GoogleIdToken.Payload payload = idToken.getPayload();

            String googleUserId = payload.getSubject();
            boolean emailVerified = payload.getEmailVerified();
            String pictureUrl = (String) payload.get("picture");
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");

            if (!emailVerified) {
                throw new IdentityVerificationException("Google account email is not verified.");
            }
            return new UserInfo(googleUserId, firstName, lastName, pictureUrl);
        } catch (Exception e) {
            throw new IdentityVerificationException("Google account verification exception.", e);
        }
    }

    @Override
    public AuthService.AuthProvider provider() {
        return AuthService.AuthProvider.GOOGLE;
    }
}
