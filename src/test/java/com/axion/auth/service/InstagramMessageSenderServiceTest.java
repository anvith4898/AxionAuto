package com.axion.auth.service;

import com.axion.auth.domain.dto.MessageSendRequest;
import com.axion.auth.domain.dto.MessageSendResponse;
import com.axion.auth.exception.PermanentApiException;
import com.axion.auth.exception.RateLimitExceededException;
import com.axion.auth.exception.TokenExpiredException;
import com.axion.auth.exception.TransientApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InstagramMessageSenderService}.
 *
 * <p>Uses a real {@link SimpleMeterRegistry} so counter values can be asserted
 * without mocking Micrometer internals.
 */
@ExtendWith(MockitoExtension.class)
class InstagramMessageSenderServiceTest {

    // ── Test constants ────────────────────────────────────────────────────────
    private static final String IG_ACCOUNT_ID  = "ig-account-123";
    private static final String ACCESS_TOKEN   = "EAA__test_token";
    private static final String RECIPIENT_ID   = "igsid-987654321";
    private static final String MESSAGE_TEXT   = "Hello from Axion!";
    private static final String REFRESHED_TOKEN = "EAA__refreshed_token";
    private static final String MESSAGE_ID      = "mid.abc123";

    @Mock private MetaGraphApiClient    graphApiClient;
    @Mock private GraphApiRateLimiter   rateLimiter;
    @Mock private InstagramOAuthService oAuthService;

    private MeterRegistry meterRegistry;
    private InstagramMessageSenderService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new InstagramMessageSenderService(
                graphApiClient, rateLimiter, oAuthService, meterRegistry);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendMessage — success → returns response and increments success counter")
    void sendMessage_success() {
        MessageSendResponse expected = new MessageSendResponse(RECIPIENT_ID, MESSAGE_ID);
        when(graphApiClient.sendMessage(eq(IG_ACCOUNT_ID), eq(ACCESS_TOKEN), any(MessageSendRequest.class)))
                .thenReturn(expected);

        MessageSendResponse result = service.sendMessage(
                IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT, UUID.randomUUID());

        assertThat(result).isEqualTo(expected);
        verify(rateLimiter).acquireOrThrow(IG_ACCOUNT_ID);
        assertCounterValue("success", 1.0);
    }

    @Test
    @DisplayName("sendMessage — null requestId → auto-generates UUID, still succeeds")
    void sendMessage_autoGeneratesRequestId() {
        MessageSendResponse expected = new MessageSendResponse(RECIPIENT_ID, MESSAGE_ID);
        when(graphApiClient.sendMessage(eq(IG_ACCOUNT_ID), eq(ACCESS_TOKEN), any(MessageSendRequest.class)))
                .thenReturn(expected);

        // Should not throw; requestId = null triggers UUID.randomUUID() internally
        assertThatNoException().isThrownBy(() ->
                service.sendMessage(IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT, null));
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendMessage — rate limit exceeded → throws RateLimitExceededException, no graph call")
    void sendMessage_rateLimitExceeded() {
        doThrow(new RateLimitExceededException("quota exhausted"))
                .when(rateLimiter).acquireOrThrow(IG_ACCOUNT_ID);

        assertThatThrownBy(() ->
                service.sendMessage(IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(graphApiClient);
        assertCounterValue("rate_limited", 1.0);
    }

    // ── Token expiry + one-shot refresh ──────────────────────────────────────

    @Test
    @DisplayName("sendMessage — token expired → refreshes token and retries once successfully")
    void sendMessage_tokenExpired_refreshSucceeds() {
        MessageSendResponse expected = new MessageSendResponse(RECIPIENT_ID, MESSAGE_ID);

        // First call throws; second call (with refreshed token) succeeds
        when(graphApiClient.sendMessage(eq(IG_ACCOUNT_ID), eq(ACCESS_TOKEN), any()))
                .thenThrow(new TokenExpiredException("Token expired"));
        when(oAuthService.refreshAccessToken(IG_ACCOUNT_ID))
                .thenReturn(REFRESHED_TOKEN);
        when(graphApiClient.sendMessage(eq(IG_ACCOUNT_ID), eq(REFRESHED_TOKEN), any()))
                .thenReturn(expected);

        MessageSendResponse result = service.sendMessage(
                IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT);

        assertThat(result).isEqualTo(expected);
        // Graph client called twice: once with old token, once with refreshed token
        verify(graphApiClient, times(2)).sendMessage(eq(IG_ACCOUNT_ID), anyString(), any());
        verify(oAuthService).refreshAccessToken(IG_ACCOUNT_ID);
        assertCounterValue("success", 1.0);
    }

    @Test
    @DisplayName("sendMessage — token expired AND refresh fails → re-throws TokenExpiredException")
    void sendMessage_tokenExpired_refreshFails() {
        // First call expires; refresh blows up
        when(graphApiClient.sendMessage(eq(IG_ACCOUNT_ID), eq(ACCESS_TOKEN), any()))
                .thenThrow(new TokenExpiredException("Token expired"));
        when(oAuthService.refreshAccessToken(IG_ACCOUNT_ID))
                .thenThrow(new RuntimeException("OAuth refresh service unavailable"));

        assertThatThrownBy(() ->
                service.sendMessage(IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT))
                .isInstanceOf(TokenExpiredException.class)
                .hasMessageContaining("Token expired");

        assertCounterValue("token_expired", 1.0);
        // Never called a second time — we gave up after refresh failure
        verify(graphApiClient, times(1)).sendMessage(any(), any(), any());
    }

    // ── Error classification ──────────────────────────────────────────────────

    @Test
    @DisplayName("sendMessage — permanent 4xx error → PermanentApiException propagated, counter updated")
    void sendMessage_permanentError() {
        when(graphApiClient.sendMessage(any(), any(), any()))
                .thenThrow(new PermanentApiException("400 invalid recipient"));

        assertThatThrownBy(() ->
                service.sendMessage(IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT))
                .isInstanceOf(PermanentApiException.class);

        assertCounterValue("permanent_error", 1.0);
    }

    @Test
    @DisplayName("sendMessage — transient 5xx error → TransientApiException propagated, counter updated")
    void sendMessage_transientError() {
        when(graphApiClient.sendMessage(any(), any(), any()))
                .thenThrow(new TransientApiException("503 Meta server error"));

        assertThatThrownBy(() ->
                service.sendMessage(IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT))
                .isInstanceOf(TransientApiException.class);

        assertCounterValue("transient_error", 1.0);
    }

    // ── Idempotency key forwarding ────────────────────────────────────────────

    @Test
    @DisplayName("sendMessage — supplied requestId forwarded in MessageSendRequest body")
    void sendMessage_requestIdForwarded() {
        UUID requestId = UUID.randomUUID();
        MessageSendResponse expected = new MessageSendResponse(RECIPIENT_ID, MESSAGE_ID);

        when(graphApiClient.sendMessage(eq(IG_ACCOUNT_ID), eq(ACCESS_TOKEN), any(MessageSendRequest.class)))
                .thenAnswer(invocation -> {
                    MessageSendRequest req = invocation.getArgument(2);
                    // Assert that our request_id was embedded
                    assertThat(req.requestId()).isEqualTo(requestId.toString());
                    return expected;
                });

        service.sendMessage(IG_ACCOUNT_ID, ACCESS_TOKEN, RECIPIENT_ID, MESSAGE_TEXT, requestId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Asserts that the {@code dm.send.attempts} counter for the given outcome tag
     * has been incremented the expected number of times.
     */
    private void assertCounterValue(String outcome, double expected) {
        Counter counter = meterRegistry.find("dm.send.attempts")
                .tag("outcome", outcome)
                .counter();
        assertThat(counter).as("Counter dm.send.attempts[outcome=%s]", outcome).isNotNull();
        assertThat(counter.count()).as("Counter value").isEqualTo(expected);
    }
}
