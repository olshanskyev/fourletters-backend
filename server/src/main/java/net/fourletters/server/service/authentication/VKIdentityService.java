package net.fourletters.server.service.authentication;

import com.vk.api.sdk.client.VkApiClient;
import com.vk.api.sdk.client.actors.UserActor;
import com.vk.api.sdk.objects.users.Fields;
import org.springframework.stereotype.Service;
import com.vk.api.sdk.objects.users.responses.GetResponse;

import java.util.List;

@Service
class VKIdentityService implements IdentityService {

    private final VkApiClient vkClient;

    public VKIdentityService(VkApiClient vkClient) {
        this.vkClient = vkClient;
    }

    @Override
    public UserInfo getUserInfo(String accessToken) throws IdentityVerificationException {
        UserActor actor = new UserActor(0L, accessToken);

        List<GetResponse> response;
        try {
            response = vkClient.users()
                    .get(actor)
                    .fields(Fields.PHOTO_MAX)
                    .execute();
        } catch (Exception e) {
            throw new IdentityVerificationException("Failed to fetch VK user info due to communication error", e);
        }

        if (response == null || response.isEmpty()) {
            throw new IdentityVerificationException("VK user profiles response is empty");
        }

        GetResponse user = response.get(0);

        return new UserInfo(
                user.getId().toString(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhotoMax().toString()
        );
    }

    @Override
    public AuthService.AuthProvider provider() {
        return AuthService.AuthProvider.VK;
    }
}
