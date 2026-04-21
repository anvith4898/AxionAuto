package com.axion.auth.service;

import com.axion.auth.config.MetaOAuthProperties;
import com.axion.auth.domain.dto.InstagramAccountResponse;
import com.axion.auth.domain.dto.MessageSendRequest;
import com.axion.auth.domain.dto.MessageSendResponse;
import com.axion.auth.domain.dto.TokenExchangeResponse;
import com.axion.auth.exception.OAuthException;
import com.axion.auth.exception.PermanentApiException;
import com.axion.auth.exception.TokenExpiredException;
import com.axion.auth.exception.TransientApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Handles all communication with the Meta Graph API.
 *
 * <h3>Resilience</h3>
 * <ul>
 *   <li>All methods annotated with {@code @Retry} (4 attempts, exponential backoff 1s→2s→4s)</li>
 *   <li>All methods annotated with {@code @CircuitBreaker} (opens at 50% failure rate over 10 calls)</li>
 * </ul>
 *
 * <h3>Error classification</h3>
 * <ul>
 *   <li>4xx → {@link PermanentApiException} — not retried; signals a caller bug or revoked token</li>
 *   <li>401 / OAuthException in body → {@link TokenExpiredException} — triggers token refresh flow</li>
 *   <li>5xx → {@link TransientApiException} — retried by Resilience4j</li>
 * </ul>
 */
@Slf4j
@Service
public class MetaGraphApiClient {

    private static final String RESILIENCE_INSTANCE = "meta-graph-api";

    /** Sub-code returned by Meta for expired tokens inside the Graph API error envelope. */
    private static final int META_OAUTH_INVALID_TOKEN_SUBCODE = 460;
    private static final int META_OAUTH_INVALID_SESSION_SUBCODE = 467;

    // ── Micrometer metric names ───────────────────────────────────────────────
    private static final String METRIC_GRAPH_CALL   = "graph.api.calls";
    private static final String TAG_OPERATION        = "operation";
    private static final String TAG_OUTCOME          = "outcome";
    private static final String OUTCOME_SUCCESS      = "success";
    private static final String OUTCOME_TOKEN_EXPIRED = "token_expired";
    private static final String OUTCOME_PERMANENT    = "permanent_error";
    private static final String OUTCOME_TRANSIENT    = "transient_error";

    private final RestClient    restClient;
    private final MetaOAuthProperties properties;
    private final MeterRegistry meterRegistry;

    public MetaGraphApiClient(
            @Qualifier("metaGraphApiRestClient") RestClient restClient,
            MetaOAuthProperties properties,
            MeterRegistry meterRegistry) {
        this.restClient    = restClient;
        this.properties    = properties;
        this.meterRegistry = meterRegistry;
    }

    // ── Token management ─────────────────────────────────────────────────────

    /**
     * Exchanges an authorization code for a short-lived access token.
     *
     * <p><b>Must be a POST</b> as per Meta's OAuth spec — GET is rejected with 400.
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public TokenExchangeResponse exchangeCodeForToken(String code) {
        // Meta requires form-encoded body for the token endpoint
        MultiValueMap<String, String> formBody = new LinkedMultiValueMap<>();
        formBody.add("client_id",     properties.appId());
        formBody.add("client_secret", properties.appSecret());
        formBody.add("redirect_uri",  properties.redirectUri());
        formBody.add("code",          code);

        return restClient.post()
                .uri("/oauth/access_token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(formBody)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    String body = safeReadBody(res);
                    log.error("[graph-api] Code exchange failed (4xx). body={}", body);
                    throw new PermanentApiException(
                            "Failed to exchange authorization code — invalid code or redirect URI. Meta: " + body);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    log.warn("[graph-api] Code exchange Meta server error (5xx). Will retry.");
                    throw new TransientApiException("Meta API server error during code exchange.");
                })
                .body(TokenExchangeResponse.class);
    }

    /**
     * Exchanges a short-lived access token for a long-lived token (~60 days).
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public TokenExchangeResponse extendToken(String shortLivedToken) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/oauth/access_token")
                        .queryParam("grant_type",        "fb_exchange_token")
                        .queryParam("client_id",         properties.appId())
                        .queryParam("client_secret",     properties.appSecret())
                        .queryParam("fb_exchange_token", shortLivedToken)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    String body = safeReadBody(res);
                    log.error("[graph-api] Token extension failed (4xx). body={}", body);
                    if (isTokenExpiredBody(body)) {
                        throw new TokenExpiredException("Short-lived token has expired. body=" + body);
                    }
                    throw new PermanentApiException(
                            "Failed to extend token — it may be expired or invalid. Meta: " + body);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    log.warn("[graph-api] Token extension Meta server error (5xx). Will retry.");
                    throw new TransientApiException("Meta API server error during token extension.");
                })
                .body(TokenExchangeResponse.class);
    }

    /**
     * Fetches the connected Instagram Business Account for the token holder.
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public InstagramAccountResponse.InstagramBusinessAccount fetchInstagramBusinessAccount(
            String accessToken) {

        InstagramAccountResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/me/accounts")
                        .queryParam("fields",       "instagram_business_account{id,username,name}")
                        .queryParam("access_token", accessToken)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    String body = safeReadBody(res);
                    log.error("[graph-api] Fetch IG account failed (4xx). body={}", body);
                    if (res.getStatusCode().value() == 401 || isTokenExpiredBody(body)) {
                        throw new TokenExpiredException("Access token expired while fetching IG account.");
                    }
                    throw new PermanentApiException(
                            "Failed to fetch IG account — invalid token or missing permissions. Meta: " + body);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    log.warn("[graph-api] Fetch IG account Meta server error (5xx). Will retry.");
                    throw new TransientApiException("Meta API server error while fetching IG account.");
                })
                .body(InstagramAccountResponse.class);

        if (response == null || response.firstBusinessAccount().isEmpty()) {
            throw new OAuthException(OAuthException.OAuthErrorCode.NO_INSTAGRAM_BUSINESS_ACCOUNT,
                    "No linked Instagram Business account found for the provided Facebook user.");
        }

        return response.firstBusinessAccount().get();
    }

    // ── Message sending ──────────────────────────────────────────────────────

    /**
     * Sends an Instagram Direct Message via the Messenger Platform API.
     *
     * <h3>Idempotency</h3>
     * <p>The {@code request.requestId()} (caller-generated UUID) is included in the POST body.
     * Meta deduplicates sends with the same {@code request_id} within a short window, making
     * Resilience4j retries safe — the user will receive the DM exactly once.
     *
     * <h3>Token expiry detection</h3>
     * <p>A 401 or an {@code "OAuthException"} in the response body signals token expiry.
     * The caller ({@link InstagramMessageSenderService}) catches {@link TokenExpiredException}
     * to trigger an in-line token refresh and retry once.
     *
     * @param instagramAccountId the IG Business Account ID (used as the URL path segment)
     * @param accessToken        the decrypted long-lived Meta access token
     * @param request            the message payload including idempotency key
     * @return Meta's send response with {@code recipient_id} and {@code message_id}
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public MessageSendResponse sendMessage(
            String instagramAccountId,
            String accessToken,
            MessageSendRequest request) {

        log.debug("[graph-api] Sending DM: igAccountId={} recipientId={} requestId={}",
                instagramAccountId, request.recipient().id(), request.requestId());

        String outcome = OUTCOME_SUCCESS;
        try {
            MessageSendResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{ig_account_id}/messages")
                            .queryParam("access_token", accessToken)
                            .build(instagramAccountId))
                    .body(request)  // requestId + messagingType are in the body, not a query param
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        String body = safeReadBody(res);
                        int status  = res.getStatusCode().value();
                        log.error("[graph-api] DM send failed ({}). igAccountId={} requestId={} body={}",
                                status, instagramAccountId, request.requestId(), body);

                        // 401 or OAuthException / subcode 460,467 → token has expired/revoked
                        if (status == 401 || isTokenExpiredBody(body)) {
                            throw new TokenExpiredException(
                                    "Instagram access token is expired or revoked. status=" + status);
                        }
                        // 400 invalid_parameter, wrong IGSID, etc. — retrying won't help
                        throw new PermanentApiException(
                                "Failed to send Instagram DM (permanent). status=" + status
                                + " Meta: " + body);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        log.warn("[graph-api] DM send Meta server error ({}). igAccountId={} requestId={}. Resilience4j will retry.",
                                status, instagramAccountId, request.requestId());
                        throw new TransientApiException(
                                "Meta API server error during message send. status=" + status);
                    })
                    .body(MessageSendResponse.class);
            return response;

        } catch (TokenExpiredException e) {
            outcome = OUTCOME_TOKEN_EXPIRED;
            throw e;
        } catch (PermanentApiException e) {
            outcome = OUTCOME_PERMANENT;
            throw e;
        } catch (TransientApiException e) {
            outcome = OUTCOME_TRANSIENT;
            throw e;
        } finally {
            Counter.builder(METRIC_GRAPH_CALL)
                    .tag(TAG_OPERATION, "send_message")
                    .tag(TAG_OUTCOME, outcome)
                    .register(meterRegistry)
                    .increment();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Safely reads a response body to a String for logging.
     * Returns an empty string instead of throwing if the body stream is null or already closed.
     */
    private static String safeReadBody(
            org.springframework.http.client.ClientHttpResponse res) {
        try {
            if (res.getBody() == null) return "(empty body)";
            return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(body read failed: " + e.getMessage() + ")";
        }
    }

    /**
     * Heuristic check: does the response body indicate a token expiry / revocation?
     *
     * <p>Meta communicates this via:
     * <ul>
     *   <li>{@code "type":"OAuthException"} in the error envelope</li>
     *   <li>{@code "error_subcode": 460} — password changed, session invalidated</li>
     *   <li>{@code "error_subcode": 467} — invalid/expired session key</li>
     *   <li>Literal strings in the error message</li>
     * </ul>
     *
     * <p>String matching is intentionally broad to cope with slight formatting variations
     * across Graph API versions.  It is always safe to over-signal expiry — the worst
     * outcome is an unnecessary token refresh, which updates the stored token correctly.
     */
    private static boolean isTokenExpiredBody(String body) {
        if (body == null) return false;
        return body.contains("OAuthException")
                || body.contains("\"type\":\"OAuthException\"")
                || body.contains("Invalid OAuth access token")
                || body.contains("access token has expired")
                || body.contains("\"error_subcode\":"
                                  + META_OAUTH_INVALID_TOKEN_SUBCODE)    // 460
                || body.contains("\"error_subcode\":"
                                  + META_OAUTH_INVALID_SESSION_SUBCODE)  // 467
                || body.contains("Session has expired")
                || body.contains("Error validating access token");
    }
}
