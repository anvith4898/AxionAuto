package com.axion.auth.controller;

import com.axion.auth.domain.dto.rules.RuleRequest;
import com.axion.auth.domain.dto.rules.RuleResponse;
import com.axion.auth.domain.dto.rules.RuleToggleRequest;
import com.axion.auth.security.CurrentUserService;
import com.axion.auth.service.RuleManagementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rules")
public class RulesController {

    private final RuleManagementService ruleService;
    private final CurrentUserService currentUserService;

    public RulesController(RuleManagementService ruleService, CurrentUserService currentUserService) {
        this.ruleService = ruleService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<List<RuleResponse>> list() {
        return ResponseEntity.ok(ruleService.list(currentUserService.tenantId()));
    }

    @PostMapping
    public ResponseEntity<RuleResponse> create(@Valid @RequestBody RuleRequest request) {
        return ResponseEntity.ok(ruleService.create(currentUserService.tenantId(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RuleResponse> update(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return ResponseEntity.ok(ruleService.update(currentUserService.tenantId(), id, request));
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<RuleResponse> toggle(@PathVariable UUID id, @RequestBody RuleToggleRequest request) {
        return ResponseEntity.ok(ruleService.toggle(currentUserService.tenantId(), id, request.active()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ruleService.delete(currentUserService.tenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
