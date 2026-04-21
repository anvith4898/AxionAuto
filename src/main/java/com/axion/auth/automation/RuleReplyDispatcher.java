package com.axion.auth.automation;

import com.axion.auth.domain.dto.MessageSendResponse;
import com.axion.auth.domain.entity.AutomationRule;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.entity.InstagramOAuthToken.TokenStatus;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import com.axion.auth.exception.PermanentApiException;
import com.axion.auth.service.ConversationMessageService;
import com.axion.auth.service.InstagramMessageSenderService;
import com.axion.auth.service.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bridges the automation engine to the Instagram DM sender.
 *
 * <p>Extracted into its own component so that:
 * <ul>
 *   <li>The engine can be unit-tested with a mock dispatcher</li>
 *   <li>Future channels (WhatsApp, Gmail) can slot in as alternative implementations</li>
 * </ul>
 *
 * <h3>Token resolution</h3>
 * <p>The access token is resolved here by looking up the ACTIVE token for
 * {@code (tenantId, igAccountId)} and decrypting it with AES-256-GCM.
 * If no token is found or decryption fails, a {@link PermanentApiException} is thrown
 * which the engine logs as {@code FAILED} in the execution log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleReplyDispatcher {

    private final InstagramMessageSenderService senderService;
    private final InstagramOAuthTokenRepository tokenRepository;
    private final TokenEncryptionService        encryptionService;
    private final ConversationMessageService    conversationMessageService;

    /**
     * Sends the resolved reply text via the Instagram Graph API.
     *
     * @param tenantId      tenant context for per-account token lookup
     * @param inbound       the inbound message that triggered the rule
     * @param rule          the matched automation rule (for logging)
     * @param resolvedReply the final reply text after template substitution
     */
    public void dispatch(UUID tenantId, MessageDTO inbound, AutomationRule rule, String resolvedReply) {
        log.info("[dispatcher] Sending rule '{}' reply to sender={} [mid={}]",
                rule.getName(), inbound.senderId(), inbound.messageId());

        // Resolve and decrypt the access token
        String accessToken = resolveAccessToken(tenantId, inbound.igAccountId());

        // Derive an idempotent request ID from (ruleId + rawEventId) using UUID v3.
        // This guarantees: same input → same UUID → Meta deduplicates on re-delivery.
        UUID requestId = deriveRequestId(rule.getId(), inbound.rawEventId());

        MessageSendResponse response = senderService.sendMessage(
                inbound.igAccountId(),
                accessToken,
                inbound.senderId(),
                resolvedReply,
                requestId
        );

        conversationMessageService.recordOutbound(
                tenantId,
                inbound.igAccountId(),
                inbound.senderId(),
                resolvedReply,
                response,
                "AUTOMATION"
        );

        log.info("[dispatcher] Reply sent successfully for rule='{}' sender={} requestId={}",
                rule.getName(), inbound.senderId(), requestId);
    }

    // ── Token resolution ──────────────────────────────────────────────────────

    /**
     * Resolves and decrypts the ACTIVE access token for the given account.
     *
     * @throws PermanentApiException if no active token is found or decryption fails
     */
    private String resolveAccessToken(UUID tenantId, String igAccountId) {
        InstagramOAuthToken token = tokenRepository
                .findByTenantIdAndInstagramAccountId(tenantId, igAccountId)
                .orElseThrow(() -> {
                    log.error("[dispatcher] No OAuth token for tenantId={} igAccountId={}",
                            tenantId, igAccountId);
                    return new PermanentApiException(
                            "No OAuth token found for igAccountId=" + igAccountId);
                });

        if (token.getStatus() != TokenStatus.ACTIVE) {
            log.error("[dispatcher] Token for igAccountId={} has status={} — cannot send",
                    igAccountId, token.getStatus());
            throw new PermanentApiException(
                    "Token for igAccountId=" + igAccountId + " is not ACTIVE (status=" + token.getStatus() + ")");
        }

        if (token.isExpired()) {
            log.error("[dispatcher] Token for igAccountId={} is expired (expiry={})",
                    igAccountId, token.getTokenExpiry());
            throw new PermanentApiException(
                    "Token for igAccountId=" + igAccountId + " is expired");
        }

        try {
            return encryptionService.decrypt(
                    token.getAccessTokenEncrypted(),
                    token.getAccessTokenIv(),
                    token.getAccessTokenTag()
            );
        } catch (Exception e) {
            log.error("[dispatcher] Token decryption failed for igAccountId={}", igAccountId, e);
            throw new PermanentApiException("Token decryption failed for igAccountId=" + igAccountId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Deterministically derives a UUID request ID from the rule + inbound event ID.
     * Using UUID v3 (name-based) guarantees the same input always produces the same UUID —
     * perfect for idempotent replay protection across Meta API retries.
     */
    static UUID deriveRequestId(UUID ruleId, String rawEventId) {
        String combined = ruleId.toString() + ":" + rawEventId;
        return UUID.nameUUIDFromBytes(combined.getBytes(StandardCharsets.UTF_8));
    }
}
