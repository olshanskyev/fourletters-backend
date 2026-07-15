package net.fourletters.server.service.authentication;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("local")
@Service
public class DummyIdentityService implements IdentityService{
    @Override
    public AuthService.AuthProvider provider() {
        return AuthService.AuthProvider.DUMMY;
    }

    @Override
    public UserInfo getUserInfo(String accessToken) throws IdentityVerificationException {
        return new UserInfo(accessToken, "Dummy", "User", null);
    }
}
