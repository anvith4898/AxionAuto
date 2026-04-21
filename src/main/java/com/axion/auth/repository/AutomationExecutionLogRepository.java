package com.axion.auth.repository;

import com.axion.auth.domain.entity.AutomationExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AutomationExecutionLogRepository extends JpaRepository<AutomationExecutionLog, UUID> {

    /**
     * Returns {@code true} if this (sender, rule) combination has a SENT execution
     * within the last {@code cooldownSeconds}.
     *
     * <p>Uses {@code Instant} arithmetic in JPQL to stay DB-agnostic.
     */
    @Query("""
        SELECT COUNT(l) > 0 FROM AutomationExecutionLog l
        WHERE l.tenantId    = :tenantId
          AND l.igAccountId = :igAccountId
          AND l.senderId    = :senderId
          AND l.ruleId      = :ruleId
          AND l.status      = com.axion.auth.domain.entity.AutomationExecutionLog.ExecutionStatus.SENT
          AND l.executedAt  >= :since
        """)
    boolean existsWithinCooldown(
            @Param("tenantId")    UUID    tenantId,
            @Param("igAccountId") String  igAccountId,
            @Param("senderId")    String  senderId,
            @Param("ruleId")      UUID    ruleId,
            @Param("since")       Instant since);
}
