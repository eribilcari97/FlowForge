package com.flowforge.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowforge.dto.LoginRequest;
import com.flowforge.dto.LoginResponse;
import com.flowforge.dto.RegisterRequest;
import com.flowforge.dto.UserResponse;
import com.flowforge.entity.User;
import com.flowforge.exception.ConflictException;
import com.flowforge.exception.ErrorCode;
import com.flowforge.exception.InvalidCredentialsException;
import com.flowforge.repository.UserRepository;
import com.flowforge.security.TokenService;

@Service
public class AuthService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-unknown-emails");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new ConflictException(ErrorCode.EMAIL_TAKEN, "Email is already registered");
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), request.displayName());
        return UserResponse.from(users.saveAndFlush(user));
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new InvalidCredentialsException();
        }
        Optional<User> user = users.findByEmail(request.email());
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        TokenService.AccessToken token = tokenService.issue(user.get().getId());
        return new LoginResponse(token.value(), token.expiresInSeconds(), UserResponse.from(user.get()));
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Unknown user"));
    }
}
