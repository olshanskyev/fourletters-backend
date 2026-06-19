package net.fourletters.server.service;

import net.fourletters.dto.UserResponse;
import net.fourletters.server.model.User;
import net.fourletters.server.repository.UsersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
