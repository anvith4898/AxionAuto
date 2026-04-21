package com.axion.auth.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Represents a connected Instagram Business account returned from the Meta Graph API.
 *
 * <p>Graph API endpoint:
 * <pre>
 * GET /me/accounts?fields=instagram_business_account{id,username,name}&access_token={token}
 * </pre>
 *
 * <p>Response structure:
 * <pre>{@code
 * {
 *   "data": [
 *     {
 *       "instagram_business_account": {
 *         "id": "17841407341026169",
 *         "username": "mybusiness",
 *         "name": "My Business"
 *       },
 *       "id": "112358132134558"   // Facebook Page ID
 *     }
 *   ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstagramAccountResponse(

        List<PageEntry> data

) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageEntry(

            String id,   // Facebook Page ID

            @JsonAlias("instagram_business_account")
            InstagramBusinessAccount instagramBusinessAccount

    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstagramBusinessAccount(
            String id,
            String username,
            String name
    ) {}

    /**
     * Returns the first Instagram Business Account found, or empty if none.
     * Tenant must have at least one Facebook Page with a linked Instagram Business account.
     */
    public java.util.Optional<InstagramBusinessAccount> firstBusinessAccount() {
        if (data == null) return java.util.Optional.empty();
        return data.stream()
                .filter(p -> p.instagramBusinessAccount() != null)
                .map(PageEntry::instagramBusinessAccount)
                .findFirst();
    }
}
