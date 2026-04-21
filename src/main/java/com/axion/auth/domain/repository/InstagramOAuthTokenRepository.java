package com.axion.auth.domain.repository;

import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.entity.InstagramOAuthToken.TokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Instagram OAuth tokens.
 *
 * <p>All queries are tenant-scoped. The PostgreSQL RLS policy on this table provides
 * a second layer of enforcement at the DB session level.
 *
 * <p>IMPORTANT: Callers must ensure {@code TenantContext} is set before any query,
 * so the RLS policy receives the correct tenant variable via the interceptor.
 */
@Repository
public interface InstagramOAuthTokenRepository extends JpaRepository<InstagramOAuthToken, UUID> {

    /**
     * Find the active token for a specific tenant + user combination.
     * Used during API calls to load the decrypted token for Graph API requests.
     */
    Optional<InstagramOAuthToken> findByTenantIdAndUserIdAndStatus(
            UUID tenantId, String userId, TokenStatus status);

    /**
     * Find token by tenant + Instagram account ID.
     * Used for deduplication during OAuth callback to prevent duplicate accounts.
     */
    Optional<InstagramOAuthToken> findByTenantIdAndInstagramAccountId(
            UUID tenantId, String instagramAccountId);

    /**
     * Find all ACTIVE tokens expiring within a given window.
     * Used by {@link com.axion.auth.service.TokenRefreshScheduler} for proactive refresh.
     *
     * @param before tokens expiring before this instant will be returned
     */
    @Query("""
            SELECT t FROM InstagramOAuthToken t
            WHERE t.status = 'ACTIVE'
              AND t.tokenExpiry < :before
            ORDER BY t.tokenExpiry ASC
            """)
    List<InstagramOAuthToken> findExpiringTokens(@Param("before") Instant before);

    /**
     * Find all tokens for a tenant (used for admin/audit purposes).
     */
    List<InstagramOAuthToken> findAllByTenantId(UUID tenantId);

    /**
     * Resolve the owning tenant for an Instagram account by account ID + status.
     *
     * <p>Used by {@link com.axion.auth.integration.WebhookStreamConsumer} to map an
     * inbound webhook's {@code igAccountId} to its tenant — replacing the previous
     * {@code findAll()} full-table scan.
     *
     * <p>Requires a composite index on {@code (instagram_account_id, status)} for O(1) lookup.
     *
     * @param igAccountId Instagram Business Account ID from the webhook payload
     * @param status      typically {@link TokenStatus#ACTIVE}
     * @return the first matching token, or empty if no connected account found
     */
    Optional<InstagramOAuthToken> findFirstByInstagramAccountIdAndStatus(
            String igAccountId, TokenStatus status);

    /**
     * Bulk-update status for a tenant's tokens.
     * Used when a tenant is suspended or an account is disconnected.
     */
    @Modifying
    @Query("""
            UPDATE InstagramOAuthToken t
            SET t.status = :newStatus, t.updatedAt = :now
            WHERE t.tenantId = :tenantId AND t.status = :currentStatus
            """)
    int updateStatusForTenant(
            @Param("tenantId") UUID tenantId,
            @Param("currentStatus") TokenStatus currentStatus,
            @Param("newStatus") TokenStatus newStatus,
            @Param("now") Instant now);

    /**
     * Check whether a tenant already has a connected Instagram account.
     */
    boolean existsByTenantIdAndStatus(UUID tenantId, TokenStatus status);

    /**
     * Find token for refresh: returns tokens where status=ACTIVE and expiry is approaching.
     * Ordered by expiry ASC so most-urgent tokens are refreshed first.
     */
    @Query("""
            SELECT t FROM InstagramOAuthToken t
            WHERE t.tenantId = :tenantId
              AND t.status = 'ACTIVE'
              AND t.tokenExpiry < :refreshBoundary
            """)
    Optional<InstagramOAuthToken> findTokenNeedingRefresh(
            @Param("tenantId") UUID tenantId,
            @Param("refreshBoundary") Instant refreshBoundary);
}
