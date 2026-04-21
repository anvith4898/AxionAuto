package com.axion.auth.service;

import com.axion.auth.domain.dto.MessageSendRequest;
import com.axion.auth.domain.dto.MessageSendResponse;
import com.axion.auth.exception.PermanentApiException;
import com.axion.auth.exception.RateLimitExceededException;
import com.axion.auth.exception.TokenExpiredException;
import com.axion.auth.exception.TransientApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates Instagram Direct Message delivery end-to-end.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Acquire a per-account rate-limit slot (sliding window, Redis-backed).</li>
 *   <li>Build the {@link MessageSendRequest} with a caller-supplied idempotency key.</li>
 *   <li>Delegate to {@link MetaGraphApiClient#sendMessage}, which carries
 *       {@code @Retry} and {@code @CircuitBreaker} annotations.</li>
 *   <li>On {@link TokenExpiredException}: attempt a one-shot inline token refresh
 *       via {@link InstagramOAuthService}, then retry exactly once.</li>
 * </ol>
 *
 * <h3>Idempotency</h3>
 * <p>The caller-generated {@code requestId} UUID is embedded in the Meta request body.
 * Meta deduplicates sends with the same {@code request_id} within its dedup window,
 * so Resilience4j retries are guaranteed not to produce duplicate DMs.
 *
 * <h3>Observability</h3>
 * <p>Every send attempt records a Micrometer counter ({@code dm.send.attempts}) and
 * a timer ({@code dm.send.duration}), tagged by {@code outcome} (success / rate_limited /
 * token_expired / permanent_error / transient_error) and {@code ig_account_id}.
 * The {@code requestId} and {@code recipientId} are pushed into MDC for log correlation.
 */
@Slf4j
@Service
public class InstagramMessageSenderService {

    // ── MDC keys ──────────────────────────────────────────────────────────────
    private static final String MDC_REQUEST_ID  = "dmRequestId";
    private static final String MDC_RECIPIENT   = "dmRecipientId";
    private static final String MDC_IG_ACCOUNT  = "igAccountId";

    // ── Micrometer metric names ───────────────────────────────────────────────
    private static final String METRIC_ATTEMPTS = "dm.send.attempts";
    private static final String METRIC_DURATION = "dm.send.duration";
    private static final String TAG_IG_ACCOUNT  = "ig_account_id";
    private static final String TAG_OUTCOME     = "outcome";

    // ── Outcome tag values ────────────────────────────────────────────────────
    private static final String OUTCOME_SUCCESS           = "success";
    private static final String OUTCOME_RATE_LIMITED      = "rate_limited";
    private static final String OUTCOME_TOKEN_EXPIRED     = "token_expired";
    private static final String OUTCOME_PERMANENT_ERROR   = "permanent_error";
    private static final String OUTCOME_TRANSIENT_ERROR   = "transient_error";

    private final MetaGraphApiClient    graphApiClient;
    private final GraphApiRateLimiter   rateLimiter;
    private final InstagramOAuthService oAuthService;
    private final MeterRegistry         meterRegistry;

    public InstagramMessageSenderService(
            MetaGraphApiClient    graphApiClient,
            GraphApiRateLimiter   rateLimiter,
            InstagramOAuthService oAuthService,
            MeterRegistry         meterRegistry) {
        this.graphApiClient = graphApiClient;
        this.rateLimiter    = rateLimiter;
        this.oAuthService   = oAuthService;
        this.meterRegistry  = meterRegistry;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sends a Direct Message from the given Instagram Business Account to a recipient.
     *
     * <p>A fresh {@link UUID} is generated as the idempotency key when {@code requestId} is
     * {@code null}. Callers that perform their own retry loop should supply a stable UUID.
     *
     * @param instagramAccountId the IG Business Account sending the message
     * @param accessToken        the decrypted long-lived Meta access token
     * @param recipientId        the recipient's Instagram-Scoped ID (IGSID)
     * @param messageText        the DM body text (max 1 000 characters per Meta spec)
     * @param requestId          caller-generated idempotency UUID; auto-generated if null
     * @return Meta's send response containing {@code recipient_id} and {@code message_id}
     * @throws RateLimitExceededException if the per-account quota is exhausted
     * @throws PermanentApiException      if Meta rejects the request with a 4xx (non-401)
     * @throws TransientApiException      if Meta returns a 5xx (after all retries fail)
     * @throws TokenExpiredException      if the token is expired AND refresh also fails
     */
    public MessageSendResponse sendMessage(
            String instagramAccountId,
            String accessToken,
            String recipientId,
            String messageText,
            UUID   requestId) {

        // Auto-generate idempotency key when caller doesn't supply one
        UUID effectiveRequestId = (requestId != null) ? requestId : UUID.randomUUID();

        // Push tracing context into MDC so every log line in this call carries it
        try (var mdc = pushMdc(instagramAccountId, recipientId, effectiveRequestId)) {

            log.debug("[dm-sender] Starting DM send: igAccountId={} recipientId={} requestId={}",
                    instagramAccountId, recipientId, effectiveRequestId);

            Timer.Sample timerSample = Timer.start(meterRegistry);
            String outcome = OUTCOME_SUCCESS;

            try {
                // ── 1. Rate limiting ──────────────────────────────────────────
                rateLimiter.acquireOrThrow(instagramAccountId);

                // ── 2. Build idempotent request payload ───────────────────────
                MessageSendRequest payload = MessageSendRequest.of(
                        recipientId,
                        messageText,
                        effectiveRequestId.toString()
                );

                // ── 3. Delegate to graph client (carries @Retry + @CircuitBreaker)
                MessageSendResponse response = executeWithTokenRefresh(
                        instagramAccountId, accessToken, payload);

                log.info("[dm-sender] DM sent successfully: igAccountId={} recipientId={} messageId={} requestId={}",
                        instagramAccountId, recipientId, response.messageId(), effectiveRequestId);

                return response;

            } catch (RateLimitExceededException e) {
                outcome = OUTCOME_RATE_LIMITED;
                log.warn("[dm-sender] Rate limit exceeded for igAccountId={} requestId={}",
                        instagramAccountId, effectiveRequestId);
                throw e;

            } catch (TokenExpiredException e) {
                outcome = OUTCOME_TOKEN_EXPIRED;
                log.warn("[dm-sender] Token expired for igAccountId={} — refresh failed, giving up. requestId={}",
                        instagramAccountId, effectiveRequestId);
                throw e;

            } catch (PermanentApiException e) {
                outcome = OUTCOME_PERMANENT_ERROR;
                log.error("[dm-sender] Permanent error sending DM: igAccountId={} requestId={} error={}",
                        instagramAccountId, effectiveRequestId, e.getMessage());
                throw e;

            } catch (TransientApiException e) {
                outcome = OUTCOME_TRANSIENT_ERROR;
                log.warn("[dm-sender] Transient error after all retries: igAccountId={} requestId={} error={}",
                        instagramAccountId, effectiveRequestId, e.getMessage());
                throw e;

            } finally {
                // Record attempt counter
                Counter.builder(METRIC_ATTEMPTS)
                        .tag(TAG_IG_ACCOUNT, instagramAccountId)
                        .tag(TAG_OUTCOME, outcome)
                        .register(meterRegistry)
                        .increment();

                // Record send duration
                timerSample.stop(Timer.builder(METRIC_DURATION)
                        .tag(TAG_IG_ACCOUNT, instagramAccountId)
                        .tag(TAG_OUTCOME, outcome)
                        .register(meterRegistry));
            }
        }
    }

    // ── Convenience overload (auto-generates requestId) ───────────────────────

    /**
     * Convenience overload — generates an idempotency UUID automatically.
     * Suitable for one-shot fire-and-forget callers.
     */
    public MessageSendResponse sendMessage(
            String instagramAccountId,
            String accessToken,
            String recipientId,
            String messageText) {
        return sendMessage(instagramAccountId, accessToken, recipientId, messageText, null);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Executes the Graph API send. On {@link TokenExpiredException}, triggers a one-shot
     * token refresh and retries exactly once. If the refresh itself fails, the original
     * {@link TokenExpiredException} is re-thrown.
     *
     * <p>This one-shot retry is intentionally <em>outside</em> Resilience4j so that
     * a legitimate 401 (expired token) does not burn retry slots meant for transient
     * 5xx errors.
     */
    private MessageSendResponse executeWithTokenRefresh(
            String             instagramAccountId,
            String             accessToken,
            MessageSendRequest payload) {

        try {
            return graphApiClient.sendMessage(instagramAccountId, accessToken, payload);

        } catch (TokenExpiredException firstAttemptEx) {

            log.info("[dm-sender] Token expired — attempting inline refresh for igAccountId={}",
                    instagramAccountId);

            try {
                String refreshedToken = oAuthService.refreshAccessToken(instagramAccountId);

                log.info("[dm-sender] Token refreshed successfully for igAccountId={} — retrying send.",
                        instagramAccountId);

                // Retry once with refreshed token; propagate any new exception directly
                return graphApiClient.sendMessage(instagramAccountId, refreshedToken, payload);

            } catch (Exception refreshEx) {
                log.error("[dm-sender] Token refresh failed for igAccountId={}: {}",
                        instagramAccountId, refreshEx.getMessage());
                // Re-throw the original expiry exception — caller decides what to do
                throw firstAttemptEx;
            }
        }
    }

    private interface MdcCloseable extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Pushes per-request MDC fields and returns an {@link MdcCloseable} that removes
     * them when the try-with-resources block exits. This is safe across virtual threads
     * because MDC is thread-local and Resilience4j propagates the same thread context.
     */
    private MdcCloseable pushMdc(String igAccountId, String recipientId, UUID requestId) {
        MDC.put(MDC_IG_ACCOUNT, igAccountId);
        MDC.put(MDC_RECIPIENT,  recipientId);
        MDC.put(MDC_REQUEST_ID, requestId.toString());
        return () -> {
            MDC.remove(MDC_IG_ACCOUNT);
            MDC.remove(MDC_RECIPIENT);
            MDC.remove(MDC_REQUEST_ID);
        };
    }
}
