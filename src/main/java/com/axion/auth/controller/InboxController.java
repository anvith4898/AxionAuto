package com.axion.auth.controller;

import com.axion.auth.domain.dto.inbox.InboxMessageResponse;
import com.axion.auth.domain.dto.inbox.ManualSendRequest;
import com.axion.auth.domain.dto.inbox.ThreadSummaryResponse;
import com.axion.auth.service.ConversationMessageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inbox/threads")
public class InboxController {

    private final ConversationMessageService conversationService;

    public InboxController(ConversationMessageService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ResponseEntity<List<ThreadSummaryResponse>> threads() {
        return ResponseEntity.ok(conversationService.listThreads());
    }

    @GetMapping("/{threadId}")
    public ResponseEntity<List<InboxMessageResponse>> messages(@PathVariable String threadId) {
        return ResponseEntity.ok(conversationService.getThreadMessages(threadId));
    }

    @PostMapping("/{threadId}/messages")
    public ResponseEntity<InboxMessageResponse> send(
            @PathVariable String threadId,
            @Valid @RequestBody ManualSendRequest request) {
        return ResponseEntity.ok(conversationService.sendManualMessage(threadId, request.text()));
    }
}
