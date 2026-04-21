package com.axion.auth.controller;

import com.axion.auth.domain.dto.OAuthConnectionResult;
import com.axion.auth.security.CurrentUserService;
import com.axion.auth.service.InstagramOAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Controller exposing endpoints for the Instagram Business OAuth 2.0 flow.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oauth/instagram")
@RequiredArgsConstructor
public class InstagramOAuthController {

    private final InstagramOAuthService oAuthService;
    private final CurrentUserService currentUserService;

    /**
     * Starts the OAuth flow.
     * Generates a Meta authorization URL containing the client ID, scopes, and a secure CSRF state token.
     * The client should redirect the user's browser to the returned URL.
     *
     * @param redirectUrl Optional client URL to redirect to after flow completion.
     * @return 200 OK with the authorization URL to redirect the user to.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthorizationUrlResponse> initiateLogin(
            @RequestParam(value = "redirect_url", required = false) String redirectUrl) {

        var currentUser = currentUserService.require();
        log.info("Initiating Instagram OAuth flow for tenantId={} userId={}",
                currentUser.tenantId(), currentUser.userId());
        String authorizationUrl = oAuthService.generateAuthorizationUrl(
                currentUser.tenantId(),
                currentUser.userId(),
                redirectUrl
        );
        return ResponseEntity.ok(new AuthorizationUrlResponse(authorizationUrl));
    }

    /**
     * Handles the callback from Meta after the user grants or denies authorization.
     *
     * @param code The authorization code returned by Meta (if granted).
     * @param state The secure CSRF state token we generated in /login.
     * @param error Error reason if the user denied access.
     * @param errorReason Specific error string from Meta.
     * @param errorDescription Human-readable error description from Meta.
     * @return 200 OK with connection details on success, or redirect to a client URL.
     */
    @GetMapping("/callback")
    public ResponseEntity<?> handleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "error_reason", required = false) String errorReason,
            @RequestParam(value = "error_description", required = false) String errorDescription) {

        String redirectAfterCallback = oAuthService.peekRedirectAfterCallback(state).orElse(null);

        if (error != null) {
            log.warn("OAuth authorization denied by user. error={}, reason={}, description={}",
                    error, errorReason, errorDescription);
            return failureResponse(
                    redirectAfterCallback,
                    HttpStatus.FORBIDDEN,
                    errorDescription != null ? errorDescription : "Authorization failed or was denied by the user."
            );
        }

        if (code == null || state == null) {
            log.error("Invalid callback request: Missing code or state parameter.");
            return failureResponse(redirectAfterCallback, HttpStatus.BAD_REQUEST, "Invalid callback parameters.");
        }

        try {
            OAuthConnectionResult result = oAuthService.handleCallback(code, state);
            log.info("Successfully completed Instagram OAuth flow for tenantId={}. New connection: {}",
                    result.tenantId(), result.isNewConnection());

            if (result.redirectAfterCallback() != null && !result.redirectAfterCallback().isBlank()) {
                URI redirectUri = UriComponentsBuilder
                        .fromUriString(result.redirectAfterCallback())
                        .queryParam("status", "success")
                        .queryParam("accountId", result.instagramAccountId())
                        .queryParam("username", result.instagramUsername())
                        .build()
                        .toUri();
                return ResponseEntity.status(HttpStatus.FOUND).location(redirectUri).build();
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error during handling of OAuth callback", e);
            return failureResponse(
                    redirectAfterCallback,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to complete OAuth connection: " + e.getMessage()
            );
        }
    }

    private ResponseEntity<?> failureResponse(String redirectAfterCallback, HttpStatus status, String message) {
        if (redirectAfterCallback != null && !redirectAfterCallback.isBlank()) {
            URI redirectUri = UriComponentsBuilder
                    .fromUriString(redirectAfterCallback)
                    .queryParam("status", "error")
                    .queryParam("message", message)
                    .build()
                    .toUri();
            return ResponseEntity.status(HttpStatus.FOUND).location(redirectUri).build();
        }

        return ResponseEntity.status(status).body(message);
    }

    public record AuthorizationUrlResponse(String authorizationUrl) {}
}
