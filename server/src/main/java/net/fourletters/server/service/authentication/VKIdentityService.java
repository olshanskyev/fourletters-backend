package net.fourletters.server.service.authentication;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
class VKIdentityService implements IdentityService {

    private final RestTemplate vkRestTemplate;
    private final String clientId;

    public VKIdentityService(RestTemplate vkRestTemplate,
                             @Value("${vk.clientId:}") String clientId) {
        this.vkRestTemplate = vkRestTemplate;
        this.clientId = clientId;
    }

    /**
     * Verifies the VK ID {@code id_token} and reads the (privacy-masked) profile via VK ID's
     * back-channel {@code /oauth2/public_info} endpoint. VK validates the token signature server
     * side, so no JWKS/client_secret is required, and the id_token is not IP-bound (unlike the
     * access_token), which avoids error 5 "access_token was given to another ip address".
     */
    @Override
    public UserInfo getUserInfo(String idToken) throws IdentityVerificationException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id_token", idToken);

        PublicInfoResponse response;
        try {
            response = vkRestTemplate.postForObject(
                    "/oauth2/public_info?client_id={clientId}",
                    new HttpEntity<>(form, headers),
                    PublicInfoResponse.class,
                    clientId);
        } catch (RestClientException e) {
            throw new IdentityVerificationException("Failed to fetch VK public info due to communication error", e);
        }

        if (response == null || response.error() != null) {
            throw new IdentityVerificationException(
                    "VK id_token verification failed: " + (response == null ? "empty response" : response.error()));
        }
        if (response.user() == null || response.user().userId() == null) {
            throw new IdentityVerificationException("VK public info response contains no user");
        }

        PublicInfoUser user = response.user();
        return new UserInfo(
                user.userId(),
                user.firstName(),
                user.lastName(),
                user.avatar()
        );
    }

    @Override
    public AuthService.AuthProvider provider() {
        return AuthService.AuthProvider.VK;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PublicInfoResponse(PublicInfoUser user, String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PublicInfoUser(
            @JsonProperty("user_id") String userId,
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            String avatar
    ) {}
}
