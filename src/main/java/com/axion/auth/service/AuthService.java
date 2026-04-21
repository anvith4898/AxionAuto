package com.axion.auth.service;

import com.axion.auth.domain.dto.auth.AuthSessionResponse;
import com.axion.auth.domain.dto.auth.LoginRequest;
import com.axion.auth.domain.entity.UserAccount;
import com.axion.auth.repository.UserAccountRepository;
import com.axion.auth.security.AuthTokenService;
import com.axion.auth.security.AuthenticatedUser;
import com.axion.auth.security.CurrentUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService tokenService;
    private final CurrentUserService currentUserService;

    public AuthService(
            UserAccountRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthTokenService tokenService,
            CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.currentUserService = currentUserService;
    }

    public AuthSessionResponse login(LoginRequest request) {
        UserAccount user = userRepository.findByEmailIgnoreCase(request.email())
                .filter(found -> "ACTIVE".equalsIgnoreCase(found.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        return toSession(user);
    }

    public AuthSessionResponse currentSession() {
        AuthenticatedUser user = currentUserService.require();
        return new AuthSessionResponse(
                tokenService.createToken(user),
                user.userId(),
                user.tenantId(),
                user.name(),
                user.role(),
                user.email()
        );
    }

    private AuthSessionResponse toSession(UserAccount user) {
        AuthenticatedUser principal = new AuthenticatedUser(
                user.getId().toString(),
                user.getTenantId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole()
        );
        return new AuthSessionResponse(
                tokenService.createToken(principal),
                principal.userId(),
                principal.tenantId(),
                principal.name(),
                principal.role(),
                principal.email()
        );
    }
}
