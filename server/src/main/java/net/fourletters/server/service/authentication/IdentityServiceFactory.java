package net.fourletters.server.service.authentication;


import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class IdentityServiceFactory {

    private final Map<AuthService.AuthProvider, IdentityService> services;

    public IdentityServiceFactory(List<IdentityService> identityServices) {
        EnumMap<AuthService.AuthProvider, IdentityService> map = new EnumMap<>(AuthService.AuthProvider.class);
        for (IdentityService s : identityServices) {
            AuthService.AuthProvider p = s.provider();
            if (map.putIfAbsent(p, s) != null) {
                throw new IllegalStateException("Multiple IdentityServices for provider " + p);
            }
        }
        this.services = Map.copyOf(map);
    }

    public IdentityService getIdentityService(AuthService.AuthProvider authProvider) {
        IdentityService s = services.get(authProvider);
        if (s == null) {
            throw new IllegalArgumentException("No IdentityService for provider " + authProvider);
        }
        return s;
    }
}
