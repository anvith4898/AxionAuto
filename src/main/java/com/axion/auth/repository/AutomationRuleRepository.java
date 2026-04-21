package com.axion.auth.repository;

import com.axion.auth.domain.entity.AutomationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AutomationRuleRepository extends JpaRepository<AutomationRule, UUID> {

    /**
     * Loads all active rules for a given account ordered by priority ascending.
     * Keywords are JOIN-fetched to avoid N+1 queries.
     */
    @Query("""
        SELECT DISTINCT r FROM AutomationRule r
        LEFT JOIN FETCH r.keywords
        WHERE r.tenantId      = :tenantId
          AND r.igAccountId   = :igAccountId
          AND r.active        = true
        ORDER BY r.priority ASC
        """)
    List<AutomationRule> findActiveRulesWithKeywords(
            @Param("tenantId")    UUID   tenantId,
            @Param("igAccountId") String igAccountId);

    @Query("""
        SELECT DISTINCT r FROM AutomationRule r
        LEFT JOIN FETCH r.keywords
        WHERE r.tenantId      = :tenantId
          AND r.igAccountId   = :igAccountId
        ORDER BY r.priority ASC
        """)
    List<AutomationRule> findAllWithKeywords(
            @Param("tenantId") UUID tenantId,
            @Param("igAccountId") String igAccountId);
}
