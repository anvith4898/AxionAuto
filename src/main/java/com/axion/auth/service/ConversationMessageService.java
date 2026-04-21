package com.axion.auth.service;

import com.axion.auth.domain.dto.MessageSendResponse;
import com.axion.auth.domain.dto.inbox.InboxMessageResponse;
import com.axion.auth.domain.dto.inbox.ThreadSummaryResponse;
import com.axion.auth.domain.entity.Contact;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.entity.MessageEntity;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.repository.ContactRepository;
import com.axion.auth.repository.MessageRepository;
import com.axion.auth.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationMessageService {

    private final MessageRepository messageRepository;
    private final ContactRepository contactRepository;
    private final InstagramConnectionService connectionService;
    private final InstagramMessageSenderService senderService;
    private final TokenEncryptionService encryptionService;
    private final CurrentUserService currentUserService;

    public ConversationMessageService(
            MessageRepository messageRepository,
            ContactRepository contactRepository,
            InstagramConnectionService connectionService,
            InstagramMessageSenderService senderService,
            TokenEncryptionService encryptionService,
            CurrentUserService currentUserService) {
        this.messageRepository = messageRepository;
        this.contactRepository = contactRepository;
        this.connectionService = connectionService;
        this.senderService = senderService;
        this.encryptionService = encryptionService;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public void recordInbound(UUID tenantId, MessageDTO message) {
        if (message.messageId().isBlank()) {
            return;
        }
        if (messageRepository.findByTenantIdAndIgAccountIdAndMetaMessageId(
                tenantId, message.igAccountId(), message.messageId()).isPresent()) {
            return;
        }

        messageRepository.save(MessageEntity.builder()
                .tenantId(tenantId)
                .igAccountId(message.igAccountId())
                .direction(MessageEntity.Direction.INBOUND)
                .senderId(message.senderId())
                .recipientId(message.recipientId())
                .messageType(message.messageType().name())
                .messageText(message.messageText())
                .metaMessageId(message.messageId())
                .sentAt(message.timestamp())
                .rawPayload(Map.of(
                        "rawEventId", message.rawEventId(),
                        "deleted", message.isDeleted(),
                        "hasAttachment", message.hasAttachment()
                ))
                .build());
    }

    @Transactional
    public MessageEntity recordOutbound(
            UUID tenantId,
            String igAccountId,
            String recipientId,
            String text,
            MessageSendResponse response,
            String source) {

        return messageRepository.save(MessageEntity.builder()
                .tenantId(tenantId)
                .igAccountId(igAccountId)
                .direction(MessageEntity.Direction.OUTBOUND)
                .senderId(recipientId)
                .recipientId(igAccountId)
                .messageType("TEXT")
                .messageText(text)
                .metaMessageId(response.messageId())
                .sentAt(Instant.now())
                .rawPayload(Map.of(
                        "recipientId", response.recipientId(),
                        "source", source
                ))
                .build());
    }

    @Transactional(readOnly = true)
    public List<ThreadSummaryResponse> listThreads() {
        UUID tenantId = currentUserService.tenantId();
        InstagramOAuthToken token = connectionService.findActiveToken(tenantId).orElse(null);
        if (token == null) {
            return List.of();
        }
        return messageRepository.findLatestMessagesPerSender(tenantId, token.getInstagramAccountId()).stream()
                .map(message -> {
                    Contact contact = contactRepository.findByTenantIdAndIgAccountIdAndSenderId(
                                    tenantId, token.getInstagramAccountId(), message.getSenderId())
                            .orElse(null);
                    boolean unread = contact == null
                            || contact.getLastReadAt() == null
                            || message.getSentAt().isAfter(contact.getLastReadAt());
                    String senderName = fallbackSenderName(message.getSenderId());
                    return new ThreadSummaryResponse(
                            message.getSenderId(),
                            senderName,
                            message.getSenderId(),
                            preview(message.getMessageText()),
                            unread && message.getDirection() == MessageEntity.Direction.INBOUND,
                            message.getSentAt().toString()
                    );
                })
                .toList();
    }

    @Transactional
    public List<InboxMessageResponse> getThreadMessages(String threadId) {
        UUID tenantId = currentUserService.tenantId();
        InstagramOAuthToken token = connectionService.findActiveToken(tenantId).orElse(null);
        if (token == null) {
            return List.of();
        }
        markThreadRead(tenantId, token.getInstagramAccountId(), threadId);
        return messageRepository.findByTenantIdAndIgAccountIdAndSenderIdOrderBySentAtAsc(
                        tenantId, token.getInstagramAccountId(), threadId).stream()
                .map(message -> new InboxMessageResponse(
                        message.getMetaMessageId(),
                        message.getMessageText(),
                        message.getDirection().name().toLowerCase(),
                        message.getSentAt().toString()
                ))
                .toList();
    }

    @Transactional
    public InboxMessageResponse sendManualMessage(String threadId, String text) {
        UUID tenantId = currentUserService.tenantId();
        InstagramOAuthToken token = requireActiveToken(tenantId);
        String accessToken = encryptionService.decrypt(
                token.getAccessTokenEncrypted(),
                token.getAccessTokenIv(),
                token.getAccessTokenTag()
        );
        MessageSendResponse response = senderService.sendMessage(
                token.getInstagramAccountId(),
                accessToken,
                threadId,
                text
        );
        MessageEntity message = recordOutbound(
                tenantId,
                token.getInstagramAccountId(),
                threadId,
                text,
                response,
                "MANUAL"
        );
        markThreadRead(tenantId, token.getInstagramAccountId(), threadId);
        return new InboxMessageResponse(
                message.getMetaMessageId(),
                message.getMessageText(),
                "outbound",
                message.getSentAt().toString()
        );
    }

    private void markThreadRead(UUID tenantId, String igAccountId, String senderId) {
        contactRepository.findByTenantIdAndIgAccountIdAndSenderId(tenantId, igAccountId, senderId)
                .ifPresent(contact -> {
                    contact.setLastReadAt(Instant.now());
                    contactRepository.save(contact);
                });
    }

    private InstagramOAuthToken requireActiveToken(UUID tenantId) {
        return connectionService.findActiveToken(tenantId)
                .orElseThrow(() -> new IllegalStateException("Connect an Instagram account first"));
    }

    private String fallbackSenderName(String senderId) {
        return "Contact " + (senderId.length() > 4 ? senderId.substring(senderId.length() - 4) : senderId);
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "Attachment";
        }
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
