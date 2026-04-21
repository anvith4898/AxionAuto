package com.axion.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Strongly-typed binding for all Meta / Instagram OAuth config.
 * All sensitive values come from environment variables — never hardcoded.
 *
 * <pre>
 * Env var mapping:
 *   META_APP_ID             → axion.meta.app-id
 *   META_APP_SECRET         → axion.meta.app-secret        (NEVER logged)
 *   META_OAUTH_REDIRECT_URI → axion.meta.redirect-uri
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "axion.meta")
public record MetaOAuthProperties(

        @NotBlank(message = "META_APP_ID is required")
        String appId,

        @NotBlank(message = "META_APP_SECRET is required")
        String appSecret,

        @NotBlank(message = "META_OAUTH_REDIRECT_URI is required")
        String redirectUri,

        @NotBlank(message = "META_WEBHOOK_VERIFY_TOKEN is required")
        String webhookVerifyToken,

        @NotBlank
        String graphApiBaseUrl,

        @NotBlank
        String graphApiVersion,

        @NotEmpty
        List<String> scopes

) {
    private static final String UNSET_SENTINEL = "__UNSET__";

    public boolean isConfigured() {
        return !UNSET_SENTINEL.equals(appId)
                && !UNSET_SENTINEL.equals(appSecret)
                && redirectUri != null
                && !redirectUri.isBlank();
    }

    /**
     * Returns the fully-qualified Graph API base URL (e.g. https://graph.facebook.com/v20.0).
     */
    public String versionedBaseUrl() {
        return graphApiBaseUrl + "/" + graphApiVersion;
    }

    /**
     * Returns the OAuth dialog URL base for authorization redirects.
     */
    public String dialogBaseUrl() {
        return "https://www.facebook.com/" + graphApiVersion + "/dialog/oauth";
    }

    /**
     * Returns scopes as a comma-separated string suitable for the OAuth request.
     */
    public String scopesAsString() {
        return String.join(",", scopes);
    }

    /**
     * Safe representation — deliberately OMITS appSecret.
     */
    @Override
    public String toString() {
        return "MetaOAuthProperties{appId='%s', redirectUri='%s', version='%s', scopes=%s}"
                .formatted(appId, redirectUri, graphApiVersion, scopes);
    }
}
