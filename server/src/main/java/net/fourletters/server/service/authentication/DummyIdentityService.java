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
        return new UserInfo("ef65f3f9-26dc-4f1b-b674-5182b634fa9d", "Dummy", "User", null);
    }
}
