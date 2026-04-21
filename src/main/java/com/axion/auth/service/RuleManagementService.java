package com.axion.auth.service;

import com.axion.auth.automation.RuleCache;
import com.axion.auth.domain.dto.rules.RuleRequest;
import com.axion.auth.domain.dto.rules.RuleResponse;
import com.axion.auth.domain.entity.AutomationRule;
import com.axion.auth.domain.entity.RuleKeyword;
import com.axion.auth.repository.AutomationRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class RuleManagementService {

    private final AutomationRuleRepository ruleRepository;
    private final InstagramConnectionService connectionService;
    private final RuleCache ruleCache;

    public RuleManagementService(
            AutomationRuleRepository ruleRepository,
            InstagramConnectionService connectionService,
            RuleCache ruleCache) {
        this.ruleRepository = ruleRepository;
        this.connectionService = connectionService;
        this.ruleCache = ruleCache;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list(UUID tenantId) {
        String igAccountId = requireConnectedAccount(tenantId);
        return ruleRepository.findAllWithKeywords(tenantId, igAccountId).stream()
                .sorted(Comparator.comparingInt(AutomationRule::getPriority))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RuleResponse create(UUID tenantId, RuleRequest request) {
        String igAccountId = requireConnectedAccount(tenantId);
        AutomationRule rule = AutomationRule.builder()
                .tenantId(tenantId)
                .igAccountId(igAccountId)
                .name(request.name().trim())
                .triggerType(request.triggerType())
                .replyText(request.replyText().trim())
                .priority(request.priority() != null ? request.priority() : 100)
                .cooldownSeconds(request.cooldownSeconds() != null ? request.cooldownSeconds() : 3600L)
                .active(true)
                .build();
        applyKeywords(rule, request.keywords());
        AutomationRule saved = ruleRepository.save(rule);
        reloadCache(tenantId, igAccountId);
        return toResponse(saved);
    }

    @Transactional
    public RuleResponse update(UUID tenantId, UUID ruleId, RuleRequest request) {
        AutomationRule rule = loadOwnedRule(tenantId, ruleId);
        rule.setName(request.name().trim());
        rule.setTriggerType(request.triggerType());
        rule.setReplyText(request.replyText().trim());
        rule.setPriority(request.priority() != null ? request.priority() : rule.getPriority());
        rule.setCooldownSeconds(request.cooldownSeconds() != null ? request.cooldownSeconds() : rule.getCooldownSeconds());
        applyKeywords(rule, request.keywords());
        AutomationRule saved = ruleRepository.save(rule);
        reloadCache(tenantId, saved.getIgAccountId());
        return toResponse(saved);
    }

    @Transactional
    public RuleResponse toggle(UUID tenantId, UUID ruleId, boolean active) {
        AutomationRule rule = loadOwnedRule(tenantId, ruleId);
        rule.setActive(active);
        AutomationRule saved = ruleRepository.save(rule);
        reloadCache(tenantId, saved.getIgAccountId());
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID tenantId, UUID ruleId) {
        AutomationRule rule = loadOwnedRule(tenantId, ruleId);
        String igAccountId = rule.getIgAccountId();
        ruleRepository.delete(rule);
        reloadCache(tenantId, igAccountId);
    }

    private AutomationRule loadOwnedRule(UUID tenantId, UUID ruleId) {
        return ruleRepository.findById(ruleId)
                .filter(rule -> rule.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Rule not found"));
    }

    private void applyKeywords(AutomationRule rule, List<String> keywords) {
        rule.getKeywords().clear();
        if (rule.getTriggerType() != AutomationRule.TriggerType.KEYWORD || keywords == null) {
            return;
        }
        keywords.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .forEach(keyword -> rule.getKeywords().add(RuleKeyword.builder()
                        .rule(rule)
                        .keyword(keyword)
                        .build()));
    }

    private void reloadCache(UUID tenantId, String igAccountId) {
        ruleCache.invalidate(tenantId, igAccountId);
        ruleCache.load(tenantId, igAccountId);
    }

    private String requireConnectedAccount(UUID tenantId) {
        return connectionService.findActiveToken(tenantId)
                .map(token -> token.getInstagramAccountId())
                .orElseThrow(() -> new IllegalStateException("Connect an Instagram account before managing rules"));
    }

    private RuleResponse toResponse(AutomationRule rule) {
        return new RuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getTriggerType(),
                rule.getKeywords().stream().map(RuleKeyword::getKeyword).toList(),
                rule.getReplyText(),
                rule.isActive(),
                rule.getPriority(),
                rule.getCooldownSeconds(),
                rule.getCreatedAt()
        );
    }
}
