package com.axion.auth.service;

import com.axion.auth.domain.entity.UserAccount;
import com.axion.auth.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapUserService {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostConstruct
    void ensureBootstrapUsers() {
        seed("demo@axion.io", "demo123", "Demo User", "ADMIN",
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
        seed("admin@axion.io", "admin123", "Admin", "OWNER",
                UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"));
    }

    private void seed(String email, String password, String name, String role, UUID tenantId) {
        userRepository.findByEmailIgnoreCase(email).ifPresentOrElse(existing -> {
            if (existing.getPasswordHash() == null || existing.getPasswordHash().isBlank()) {
                existing.setPasswordHash(passwordEncoder.encode(password));
                existing.setRole(role);
                existing.setDisplayName(name);
                existing.setStatus("ACTIVE");
                existing.setPlan(existing.getPlan() == null ? "STARTER" : existing.getPlan());
                userRepository.save(existing);
            }
        }, () -> {
            userRepository.save(UserAccount.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .email(email)
                    .displayName(name)
                    .passwordHash(passwordEncoder.encode(password))
                    .plan("STARTER")
                    .status("ACTIVE")
                    .role(role)
                    .build());
            log.info("Seeded bootstrap Phase 1 user {}", email);
        });
    }
}
