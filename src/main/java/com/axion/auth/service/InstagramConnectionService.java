package com.axion.auth.service;

import com.axion.auth.domain.dto.instagram.InstagramStatusResponse;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

@Service
public class InstagramConnectionService {

    private final InstagramOAuthTokenRepository tokenRepository;

    public InstagramConnectionService(InstagramOAuthTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public InstagramStatusResponse status(UUID tenantId) {
        return findActiveToken(tenantId)
                .map(token -> new InstagramStatusResponse(
                        true,
                        token.getInstagramAccountId(),
                        token.getInstagramUsername()
                ))
                .orElseGet(() -> new InstagramStatusResponse(false, null, null));
    }

    public void disconnect(UUID tenantId) {
        findActiveToken(tenantId).ifPresent(token -> {
            token.setStatus(InstagramOAuthToken.TokenStatus.REVOKED);
            token.setUpdatedAt(Instant.now());
            tokenRepository.save(token);
        });
    }

    public Optional<InstagramOAuthToken> findActiveToken(UUID tenantId) {
        return tokenRepository.findAllByTenantId(tenantId).stream()
                .filter(token -> token.getStatus() == InstagramOAuthToken.TokenStatus.ACTIVE)
                .max(Comparator.comparing(InstagramOAuthToken::getUpdatedAt));
    }
}
