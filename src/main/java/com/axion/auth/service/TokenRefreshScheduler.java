package com.axion.auth.service;

import com.axion.auth.domain.dto.TokenExchangeResponse;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Background job to proactively refresh long-lived Meta access tokens before they expire.
 * Long-lived tokens expire in 60 days. This job runs daily and targets tokens expiring
 * within the next 2 days.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRefreshScheduler {

    private final InstagramOAuthTokenRepository tokenRepository;
    private final MetaGraphApiClient            graphApiClient;
    private final TokenEncryptionService        encryptionService;
    private final MeterRegistry                 meterRegistry;

    // Run once a day at 2:00 AM server time
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void proactivelyRefreshTokens() {
        log.info("Starting proactive Meta access token refresh job.");

        // Target tokens that will expire within the next 48 hours
        Instant threshold = Instant.now().plus(48, ChronoUnit.HOURS);
        List<InstagramOAuthToken> expiringTokens = tokenRepository.findExpiringTokens(threshold);

        if (expiringTokens.isEmpty()) {
            log.info("No tokens require proactive refresh.");
            return;
        }

        log.info("Found {} tokens expiring before {}. Starting refresh.", expiringTokens.size(), threshold);

        int successfulRefreshes = 0;
        int failedRefreshes = 0;

        for (InstagramOAuthToken tokenEntity : expiringTokens) {
            try {
                // 1. Decrypt current token
                String currentToken = encryptionService.decrypt(
                        tokenEntity.getAccessTokenEncrypted(),
                        tokenEntity.getAccessTokenIv(),
                        tokenEntity.getAccessTokenTag()
                );

                // 2. Extend token via Meta Graph API
                TokenExchangeResponse response = graphApiClient.extendToken(currentToken);

                // 3. Encrypt new token
                TokenEncryptionService.EncryptedTokenResult encryptionResult =
                        encryptionService.encrypt(response.accessToken());

                // Calculate exact new expiry time or default to 60 days
                Instant newExpiry = Instant.now().plusSeconds(
                        response.expiresIn() != null ? response.expiresIn() : 5184000L);

                // 4. Update the entity
                tokenEntity.setAccessTokenEncrypted(encryptionResult.cipherText());
                tokenEntity.setAccessTokenIv(encryptionResult.iv());
                tokenEntity.setAccessTokenTag(encryptionResult.authTag());
                tokenEntity.setTokenExpiry(newExpiry);
                tokenEntity.setLastRefreshedAt(Instant.now());
                tokenEntity.setRefreshAttempts(0); // reset failures

                tokenRepository.save(tokenEntity);
                successfulRefreshes++;

                log.info("Successfully refreshed token for tenant: {}, IG Account: {}. New expiry: {}",
                        tokenEntity.getTenantId(), tokenEntity.getInstagramAccountId(), newExpiry);

            } catch (Exception e) {
                failedRefreshes++;
                log.error("Failed to refresh token for tenant: {}, IG Account: {}",
                        tokenEntity.getTenantId(), tokenEntity.getInstagramAccountId(), e);

                int attempts = tokenEntity.getRefreshAttempts() + 1;
                tokenEntity.setRefreshAttempts(attempts);

                if (attempts >= 3) {
                    log.warn("Max refresh attempts reached for tenant {} IG Account {}. Marking as REFRESH_FAILED.",
                            tokenEntity.getTenantId(), tokenEntity.getInstagramAccountId());
                    tokenEntity.setStatus(InstagramOAuthToken.TokenStatus.REFRESH_FAILED);
                }

                tokenRepository.save(tokenEntity);
            }
        }

        log.info("Finished proactive refresh job. Success: {}, Failed: {}", successfulRefreshes, failedRefreshes);

        // Emit counters so Grafana/Alertmanager can detect refresh failures
        meterRegistry.counter("pipeline.token.refresh.success").increment(successfulRefreshes);
        meterRegistry.counter("pipeline.token.refresh.failed").increment(failedRefreshes);

        if (failedRefreshes > 0) {
            log.warn("[token-refresh] {} token(s) failed to refresh — check pipeline.token.refresh.failed metric",
                    failedRefreshes);
        }
    }
}
