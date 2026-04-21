package com.axion.auth.service;

import com.axion.auth.config.MetaOAuthProperties;
import com.axion.auth.domain.dto.InstagramAccountResponse;
import com.axion.auth.domain.dto.OAuthConnectionResult;
import com.axion.auth.domain.dto.OAuthStatePayload;
import com.axion.auth.domain.dto.TokenExchangeResponse;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import com.axion.auth.exception.OAuthException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the full OAuth 2.0 flow for Instagram Business connections.
 */
@Slf4j
@Service
public class InstagramOAuthService {

    private final MetaOAuthProperties metaProperties;
    private final MetaGraphApiClient graphApiClient;
    private final TokenEncryptionService encryptionService;
    private final InstagramOAuthTokenRepository tokenRepository;
    private final RedisTemplate<String, String> sessionRedisTemplate;
    private final ObjectMapper objectMapper;

    public InstagramOAuthService(
            MetaOAuthProperties metaProperties,
            MetaGraphApiClient graphApiClient,
            TokenEncryptionService encryptionService,
            InstagramOAuthTokenRepository tokenRepository,
            @Qualifier("oauthStateRedisTemplate") RedisTemplate<String, String> sessionRedisTemplate,
            ObjectMapper objectMapper) {
        this.metaProperties = metaProperties;
        this.graphApiClient = graphApiClient;
        this.encryptionService = encryptionService;
        this.tokenRepository = tokenRepository;
        this.sessionRedisTemplate = sessionRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Step 1: Generates the authorization URL and stores state in Redis for CSRF protection.
     */
    public String generateAuthorizationUrl(UUID tenantId, String userId, String redirectAfterCallback) {
        if (!metaProperties.isConfigured()) {
            throw new IllegalStateException(
                    "Instagram OAuth is not configured yet. Set META_APP_ID, META_APP_SECRET, and META_OAUTH_REDIRECT_URI.");
        }

        String stateToken = UUID.randomUUID().toString();
        OAuthStatePayload statePayload = OAuthStatePayload.create(tenantId, userId, stateToken, redirectAfterCallback);

        try {
            String stateJson = objectMapper.writeValueAsString(statePayload);
            sessionRedisTemplate.opsForValue().set(
                    "oauth:state:" + stateToken,
                    stateJson,
                    Duration.ofMinutes(10) // 10-minute TTL for the flow
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OAuth state payload", e);
            throw new OAuthException(OAuthException.OAuthErrorCode.UNEXPECTED_ERROR, "Failed to start OAuth flow");
        }

        return UriComponentsBuilder.fromUriString(metaProperties.dialogBaseUrl())
                .queryParam("client_id", metaProperties.appId())
                .queryParam("redirect_uri", metaProperties.redirectUri())
                .queryParam("scope", metaProperties.scopesAsString())
                .queryParam("response_type", "code")
                .queryParam("state", stateToken)
                .build()
                .toUriString();
    }

    public Optional<String> peekRedirectAfterCallback(String stateToken) {
        if (stateToken == null || stateToken.isBlank()) {
            return Optional.empty();
        }

        String stateJson = sessionRedisTemplate.opsForValue().get("oauth:state:" + stateToken);
        if (stateJson == null) {
            return Optional.empty();
        }

        try {
            OAuthStatePayload statePayload = objectMapper.readValue(stateJson, OAuthStatePayload.class);
            return Optional.ofNullable(statePayload.redirectAfterCallback());
        } catch (JsonProcessingException e) {
            log.warn("Failed to decode OAuth state while peeking redirect URL", e);
            return Optional.empty();
        }
    }

    /**
     * Step 2 & 3: Handles the callback, exchanges code for token, extends it, and persists.
     */
    @Transactional
    public OAuthConnectionResult handleCallback(String code, String stateToken) {
        if (!metaProperties.isConfigured()) {
            throw new IllegalStateException(
                    "Instagram OAuth is not configured yet. Set META_APP_ID, META_APP_SECRET, and META_OAUTH_REDIRECT_URI.");
        }

        // 1. Verify CSRF state
        String stateJson = sessionRedisTemplate.opsForValue().getAndDelete("oauth:state:" + stateToken);
        if (stateJson == null) {
            log.warn("Invalid or expired OAuth state token provided");
            throw new OAuthException(OAuthException.OAuthErrorCode.INVALID_STATE, "The OAuth session is invalid or has expired.");
        }

        OAuthStatePayload statePayload;
        try {
            statePayload = objectMapper.readValue(stateJson, OAuthStatePayload.class);
        } catch (JsonProcessingException e) {
            throw new OAuthException(OAuthException.OAuthErrorCode.UNEXPECTED_ERROR, "Failed to decode state payload");
        }

        UUID tenantId = statePayload.tenantId();
        String userId = statePayload.userId();

        log.info("Processing OAuth callback for tenantId={} userId={}", tenantId, userId);

        // 2. Exchange code for short-lived token
        TokenExchangeResponse shortLivedResponse;
        try {
            shortLivedResponse = graphApiClient.exchangeCodeForToken(code);
        } catch (Exception e) {
            throw new OAuthException(OAuthException.OAuthErrorCode.CODE_EXCHANGE_FAILED, "Failed to exchange authorization code.", e);
        }

        // 3. Exchange short-lived token for long-lived token (60 days)
        TokenExchangeResponse longLivedResponse;
        try {
            longLivedResponse = graphApiClient.extendToken(shortLivedResponse.accessToken());
        } catch (Exception e) {
            throw new OAuthException(OAuthException.OAuthErrorCode.TOKEN_EXTENSION_FAILED, "Failed to obtain a long-lived token.", e);
        }

        // 4. Fetch the Instagram Business Account ID
        InstagramAccountResponse.InstagramBusinessAccount igAccount =
                graphApiClient.fetchInstagramBusinessAccount(longLivedResponse.accessToken());

        log.info("Successfully fetched Instagram Business Account ID: {} for tenant: {}", igAccount.id(), tenantId);

        // 5. Encrypt the token for storage
        TokenEncryptionService.EncryptedTokenResult encryptionResult =
                encryptionService.encrypt(longLivedResponse.accessToken());

        // Calculate expiry (Meta long-lived tokens return expiresIn seconds, typically ~60 days)
        Instant expiryTime = Instant.now().plusSeconds(longLivedResponse.expiresIn() != null ? longLivedResponse.expiresIn() : 5184000L); // fallback to 60 days if missing

        // 6. Persist or update the token
        // We use finding by tenantId + IG Account ID to prevent duplicates if someone re-authorizes.
        Optional<InstagramOAuthToken> existingToken = tokenRepository.findByTenantIdAndInstagramAccountId(tenantId, igAccount.id());

        InstagramOAuthToken tokenEntity;
        boolean isNew = false;

        if (existingToken.isPresent()) {
            tokenEntity = existingToken.get();
            tokenEntity.setAccessTokenEncrypted(encryptionResult.cipherText());
            tokenEntity.setAccessTokenIv(encryptionResult.iv());
            tokenEntity.setAccessTokenTag(encryptionResult.authTag());
            tokenEntity.setTokenExpiry(expiryTime);
            tokenEntity.setStatus(InstagramOAuthToken.TokenStatus.ACTIVE);
            tokenEntity.setUserId(userId); // update to the latest user who authorized
            tokenEntity.setInstagramUsername(igAccount.username());
            tokenEntity.setScope(metaProperties.scopesAsString());
        } else {
            isNew = true;
            tokenEntity = InstagramOAuthToken.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .instagramAccountId(igAccount.id())
                    .instagramUsername(igAccount.username())
                    .accessTokenEncrypted(encryptionResult.cipherText())
                    .accessTokenIv(encryptionResult.iv())
                    .accessTokenTag(encryptionResult.authTag())
                    .tokenExpiry(expiryTime)
                    .scope(metaProperties.scopesAsString())
                    .status(InstagramOAuthToken.TokenStatus.ACTIVE)
                    .build();
        }

        tokenEntity = tokenRepository.save(tokenEntity);

        return new OAuthConnectionResult(
                tokenEntity.getId(),
                tokenEntity.getTenantId(),
                tokenEntity.getUserId(),
                tokenEntity.getInstagramAccountId(),
                tokenEntity.getInstagramUsername(),
                tokenEntity.getTokenExpiry(),
                tokenEntity.getStatus().name(),
                isNew,
                statePayload.redirectAfterCallback()
        );
    }

    // ── Token refresh (inline, triggered by 401 during message send) ──────────

    /**
     * Refreshes the long-lived access token for the given Instagram account ID
     * and returns the new plaintext token.
     *
     * <p>Called by {@link InstagramMessageSenderService} when a message send fails with
     * a {@link com.axion.auth.exception.TokenExpiredException}. The flow is:
     * <ol>
     *   <li>Load the active {@link com.axion.auth.domain.entity.InstagramOAuthToken} by
     *       {@code instagramAccountId}.</li>
     *   <li>Decrypt the stored (currently invalid) token so Meta can validate identity.</li>
     *   <li>Call {@link MetaGraphApiClient#extendToken} to obtain a new 60-day token.</li>
     *   <li>Re-encrypt and persist the refreshed token with an updated expiry.</li>
     *   <li>Return the plaintext token for the immediate retry.</li>
     * </ol>
     *
     * @param instagramAccountId the IG Business Account whose token needs refreshing
     * @return the new plaintext long-lived access token
     * @throws OAuthException if no active token record is found for this account
     *                        or if the extension call fails
     */
    @Transactional
    public String refreshAccessToken(String instagramAccountId) {
        log.info("[oauth] Starting inline token refresh for igAccountId={}", instagramAccountId);

        InstagramOAuthToken tokenEntity = tokenRepository
                .findFirstByInstagramAccountIdAndStatus(
                        instagramAccountId, InstagramOAuthToken.TokenStatus.ACTIVE)
                .orElseThrow(() -> new OAuthException(
                        OAuthException.OAuthErrorCode.UNEXPECTED_ERROR,
                        "No active token found for Instagram account: " + instagramAccountId));

        // Decrypt the current (expired) token to supply to the extension endpoint.
        // Meta accepts an expired long-lived token for extension within a grace period.
        String currentPlaintextToken = encryptionService.decrypt(
                tokenEntity.getAccessTokenEncrypted(),
                tokenEntity.getAccessTokenIv(),
                tokenEntity.getAccessTokenTag());

        // Call Meta to extend the token (returns a brand-new 60-day token)
        TokenExchangeResponse refreshed;
        try {
            refreshed = graphApiClient.extendToken(currentPlaintextToken);
        } catch (Exception e) {
            log.error("[oauth] Token extension call failed for igAccountId={}: {}",
                    instagramAccountId, e.getMessage());
            throw new OAuthException(OAuthException.OAuthErrorCode.TOKEN_EXTENSION_FAILED,
                    "Failed to refresh access token for account: " + instagramAccountId, e);
        }

        // Re-encrypt and persist
        TokenEncryptionService.EncryptedTokenResult encrypted =
                encryptionService.encrypt(refreshed.accessToken());

        long expiresIn = refreshed.expiresIn() != null ? refreshed.expiresIn() : 5_184_000L;
        tokenEntity.setAccessTokenEncrypted(encrypted.cipherText());
        tokenEntity.setAccessTokenIv(encrypted.iv());
        tokenEntity.setAccessTokenTag(encrypted.authTag());
        tokenEntity.setTokenExpiry(Instant.now().plusSeconds(expiresIn));
        tokenRepository.save(tokenEntity);

        log.info("[oauth] Token refreshed and persisted for igAccountId={}, newExpiry={}",
                instagramAccountId, tokenEntity.getTokenExpiry());

        return refreshed.accessToken();
    }
}
