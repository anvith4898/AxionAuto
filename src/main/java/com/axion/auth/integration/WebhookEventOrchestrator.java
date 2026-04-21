package com.axion.auth.integration;

import com.axion.auth.automation.AutomationEngine;
import com.axion.auth.automation.RuleExecutionResult;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.entity.InstagramOAuthToken.TokenStatus;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import com.axion.auth.service.ConversationMessageService;
import com.axion.auth.service.TokenEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Central per-message coordinator in the event-driven pipeline.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>Resolve the decrypted access token for the Instagram account that received the event.</li>
 *   <li>Delegate to {@link AutomationEngine} for rule evaluation and reply dispatch.</li>
 *   <li>Emit structured pipeline logs at each decision point.</li>
 *   <li>Signal success/failure back to the caller ({@link WebhookStreamConsumer})
 *       by returning {@link PipelineResult}.</li>
 * </ol>
 *
 * <h3>Why a separate orchestrator?</h3>
 * <ul>
 *   <li>The {@link AutomationEngine} must stay focused on rule evaluation logic.</li>
 *   <li>Token resolution is an infrastructure concern that should not leak into domain logic.</li>
 *   <li>This layer is the natural place for cross-cutting concerns (MDC, status updates,
 *       metrics) without polluting the engine.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventOrchestrator {

    private final AutomationEngine              automationEngine;
    private final InstagramOAuthTokenRepository tokenRepository;
    private final TokenEncryptionService        encryptionService;
    private final WebhookEventStatusUpdater     statusUpdater;
    private final ConversationMessageService    conversationMessageService;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Processes a single validated {@link MessageDTO} through the automation pipeline.
     *
     * @param tenantId  tenant owning the account (extracted from the payload or token store)
     * @param message   validated, normalised MessageDTO from {@link com.axion.auth.normalization.WebhookNormalizationService}
     * @param eventId   idempotency event ID — used for status tracking
     * @return a {@link PipelineResult} describing the outcome
     */
    public PipelineResult process(UUID tenantId, MessageDTO message, String eventId) {
        try (var ctx = PipelineLoggingContext.of(
                "ORCHESTRATE", tenantId, message.igAccountId(), message.senderId(), message.messageId())) {

            log.info("[orchestrator] Processing message mid={} from sender={} on account={}",
                    message.messageId(), message.senderId(), message.igAccountId());

            // ── 1. Mark as in-progress ──────────────────────────────────────
            statusUpdater.markProcessing(eventId);

            // ── 2. Resolve access token ─────────────────────────────────────
            String accessToken = resolveAccessToken(tenantId, message.igAccountId());
            if (accessToken == null) {
                String reason = "No ACTIVE token found for igAccountId=" + message.igAccountId();
                log.warn("[orchestrator] {}", reason);
                statusUpdater.markFailed(eventId, "NO_TOKEN");
                return PipelineResult.skipped(message.messageId(), reason);
            }

            conversationMessageService.recordInbound(tenantId, message);

            // ── 3. Delegate to automation engine ────────────────────────────
            List<RuleExecutionResult> results = automationEngine.evaluate(tenantId, message);

            // ── 4. Log outcome ──────────────────────────────────────────────
            long sentCount    = results.stream().filter(r -> r.status().name().equals("SENT")).count();
            long skippedCount = results.stream().filter(r -> r.status().name().equals("SKIPPED")).count();
            long failedCount  = results.stream().filter(r -> r.status().name().equals("FAILED")).count();

            log.info("[orchestrator] Pipeline complete [mid={}, rules_evaluated={}, sent={}, skipped={}, failed={}]",
                    message.messageId(), results.size(), sentCount, skippedCount, failedCount);

            // ── 5. Update status ─────────────────────────────────────────────
            if (failedCount > 0 && sentCount == 0) {
                statusUpdater.markFailed(eventId, "ENGINE_FAILURE");
                return PipelineResult.failure(message.messageId(), "All rules failed — see execution log");
            }

            statusUpdater.markProcessed(eventId);
            return PipelineResult.success(message.messageId(), results);
        }
    }

    // ── Token resolution ───────────────────────────────────────────────────────

    /**
     * Looks up the ACTIVE OAuth token for the given account and decrypts it.
     *
     * <p>Returns {@code null} if no token exists or decryption fails — the caller will
     * skip this message and update the event status to FAILED.
     */
    private String resolveAccessToken(UUID tenantId, String igAccountId) {
        Optional<InstagramOAuthToken> tokenOpt =
                tokenRepository.findByTenantIdAndInstagramAccountId(tenantId, igAccountId);

        if (tokenOpt.isEmpty()) {
            log.warn("[orchestrator] No OAuth token on record for tenantId={} igAccountId={}",
                    tenantId, igAccountId);
            return null;
        }

        InstagramOAuthToken token = tokenOpt.get();

        if (token.getStatus() != TokenStatus.ACTIVE) {
            log.warn("[orchestrator] Token for igAccountId={} is in status {} — skipping",
                    igAccountId, token.getStatus());
            return null;
        }

        if (token.isExpired()) {
            log.warn("[orchestrator] Token for igAccountId={} is expired (expiry={}) — skipping",
                    igAccountId, token.getTokenExpiry());
            return null;
        }

        try {
            return encryptionService.decrypt(
                    token.getAccessTokenEncrypted(),
                    token.getAccessTokenIv(),
                    token.getAccessTokenTag()
            );
        } catch (Exception e) {
            log.error("[orchestrator] Token decryption failed for igAccountId={}", igAccountId, e);
            return null;
        }
    }

    // ── Result type ────────────────────────────────────────────────────────────

    /**
     * Value object describing the outcome of processing one {@link MessageDTO}
     * through the full pipeline.
     */
    public record PipelineResult(
            String                  messageId,
            Outcome                 outcome,
            String                  note,
            List<RuleExecutionResult> ruleResults
    ) {
        public enum Outcome { SUCCESS, SKIPPED, FAILURE }

        static PipelineResult success(String mid, List<RuleExecutionResult> results) {
            return new PipelineResult(mid, Outcome.SUCCESS, null, results);
        }

        static PipelineResult skipped(String mid, String reason) {
            return new PipelineResult(mid, Outcome.SKIPPED, reason, List.of());
        }

        static PipelineResult failure(String mid, String reason) {
            return new PipelineResult(mid, Outcome.FAILURE, reason, List.of());
        }

        public boolean isSuccess() { return outcome == Outcome.SUCCESS; }
        public boolean isSkipped() { return outcome == Outcome.SKIPPED; }
        public boolean isFailure() { return outcome == Outcome.FAILURE; }
    }
}
