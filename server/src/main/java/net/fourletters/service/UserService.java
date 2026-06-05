package net.fourletters.service;

import net.fourletters.dto.UserResponse;
import net.fourletters.model.User;
import net.fourletters.repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public Optional<User> getUserById(long id) {
        return usersRepository.findById(id);
    }

    public User save(User user) {
        return usersRepository.save(user);
    }

    public UserResponse mapToResponse(User user) {
        UserResponse userResponse = new UserResponse();
        userResponse.setId(UUID.nameUUIDFromBytes(user.getId().toString().getBytes()));
        userResponse.setUsername(user.getName());
        try {
            if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
                userResponse.setAvatarUrl(new java.net.URI(user.getAvatarUrl()));
            }
        } catch (Exception ignored) {}
        userResponse.setRoles(Arrays.asList(user.getRoles()));
        return userResponse;
    }

    public Optional<UserResponse> getUserResponseById(long id) {
        return getUserById(id).map(this::mapToResponse);
    }
}

