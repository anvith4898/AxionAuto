package com.axion.auth.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response from Meta token exchange endpoint.
 * Maps both short-lived and long-lived token exchange responses.
 *
 * <p>Short-lived token response (code exchange):
 * <pre>{@code
 * {"access_token":"...", "token_type":"bearer"}
 * }</pre>
 *
 * <p>Long-lived token response (token extension):
 * <pre>{@code
 * {"access_token":"...", "token_type":"bearer", "expires_in":5183944}
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenExchangeResponse(

        @JsonAlias("access_token")
        String accessToken,

        @JsonAlias("token_type")
        String tokenType,

        /** Seconds until expiry. Present in long-lived token response (~60 days = ~5,183,944s). */
        @JsonAlias("expires_in")
        Long expiresIn,

        /** Present only on long-lived token responses. */
        @JsonAlias("expires_at")
        Long expiresAt

) {
    /** True if this appears to be a long-lived token (has expiry info). */
    public boolean isLongLived() {
        return expiresIn != null && expiresIn > 3600;
    }
}
