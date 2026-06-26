package net.fourletters.server.service;

import net.fourletters.dto.PublicUser;
import net.fourletters.dto.UserBatchResponse;
import net.fourletters.dto.UserResponse;
import net.fourletters.server.model.User;
import net.fourletters.server.repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Arrays;

@Service
public class UserService {

    private final UsersRepository usersRepository;

    @Autowired
    public UserService(UsersRepository usersRepository) {
        this.usersRepository = usersRepository;
    }

    @Transactional(readOnly = true)
    public Optional<User> getUserById(UUID id) {
        return usersRepository.findById(id);
    }

    @Transactional
    public User save(User user) {
        return usersRepository.save(user);
    }

    public UserResponse mapToResponse(User user) {
        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setUsername(user.getName());
        try {
            if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
                userResponse.setAvatarUrl(new java.net.URI(user.getAvatarUrl()));
            }
        } catch (Exception ignored) {}
        userResponse.setRoles(Arrays.asList(user.getRoles()));
        return userResponse;
    }

    @Transactional(readOnly = true)
    public Optional<UserResponse> getUserResponseById(UUID id) {
        return getUserById(id).map(this::mapToResponse);
    }

    /** A single user's public profile (display name and avatar), or 404 if no such user. */
    @Transactional(readOnly = true)
    public PublicUser getPublicUser(UUID id) {
        return getUserById(id)
                .map(this::mapToPublicUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
    }

    /** Public profiles for a batch of ids. Unknown ids are silently omitted from the result. */
    @Transactional(readOnly = true)
    public UserBatchResponse getUsersBatch(List<UUID> ids) {
        List<PublicUser> profiles = usersRepository.findAllById(ids).stream()
                .map(this::mapToPublicUser)
                .toList();

        UserBatchResponse response = new UserBatchResponse();
        response.setUsers(profiles);
        return response;
    }

    private PublicUser mapToPublicUser(User user) {
        PublicUser publicUser = new PublicUser();
        publicUser.setId(user.getId());
        publicUser.setUsername(user.getName());
        try {
            if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
                publicUser.setAvatarUrl(new java.net.URI(user.getAvatarUrl()));
            }
        } catch (java.net.URISyntaxException ignored) {
            // a malformed stored avatar URL simply yields no avatar in the public profile
        }
        return publicUser;
    }
}
