package com.axion.auth.controller;

import com.axion.auth.domain.dto.instagram.InstagramStatusResponse;
import com.axion.auth.security.CurrentUserService;
import com.axion.auth.service.InstagramConnectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/oauth/instagram")
public class InstagramConnectionController {

    private final InstagramConnectionService connectionService;
    private final CurrentUserService currentUserService;

    public InstagramConnectionController(
            InstagramConnectionService connectionService,
            CurrentUserService currentUserService) {
        this.connectionService = connectionService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/status")
    public ResponseEntity<InstagramStatusResponse> status() {
        return ResponseEntity.ok(connectionService.status(currentUserService.tenantId()));
    }

    @DeleteMapping("/disconnect")
    public ResponseEntity<Void> disconnect() {
        connectionService.disconnect(currentUserService.tenantId());
        return ResponseEntity.noContent().build();
    }
}
