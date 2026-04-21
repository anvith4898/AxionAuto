package com.axion.auth.normalization;

import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload;
import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload.*;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebhookPayloadParser}.
 * No Spring context required.
 */
@DisplayName("WebhookPayloadParser")
class WebhookPayloadParserTest {

    private WebhookPayloadParser parser;

    @BeforeEach
    void setUp() {
        parser = new WebhookPayloadParser();
    }

    // ── Null / empty inputs ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("returns empty list for null payload")
        void nullPayload() {
            assertThat(parser.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when entry list is null")
        void nullEntries() {
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            payload.setObject("instagram");
            payload.setEntry(null);
            assertThat(parser.parse(payload)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when entry list is empty")
        void emptyEntries() {
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            payload.setEntry(List.of());
            assertThat(parser.parse(payload)).isEmpty();
        }
    }

    // ── Happy path DM extraction ─────────────────────────────────────────────

    @Nested
    @DisplayName("DM extraction")
    class DmExtraction {

        @Test
        @DisplayName("extracts senderId and recipientId correctly")
        void extractsSenderAndRecipient() {
            InstagramWebhookPayload payload = buildDmPayload(
                    "1234567890",   // igAccountId (entry.id)
                    "9876543210",   // sender
                    "1234567890",   // recipient
                    System.currentTimeMillis(),
                    "msg001",
                    "Hello World"
            );

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            MessageDTO dto = dtos.get(0);
            assertThat(dto.senderId()).isEqualTo("9876543210");
            assertThat(dto.recipientId()).isEqualTo("1234567890");
        }

        @Test
        @DisplayName("normalizes message text (trim + lowercase)")
        void normalizesText() {
            InstagramWebhookPayload payload = buildDmPayload(
                    "1000000001", "9000000001", "1000000001",
                    System.currentTimeMillis(), "mid1", "  HELLO WORLD  "
            );

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).messageText()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("sets MessageType.DM for messaging events")
        void setsTypeDm() {
            InstagramWebhookPayload payload = buildDmPayload(
                    "1000000002", "9000000002", "1000000002",
                    System.currentTimeMillis(), "mid2", "Hi"
            );

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).messageType()).isEqualTo(MessageType.DM);
        }

        @Test
        @DisplayName("converts epoch millis timestamp to Instant correctly")
        void convertsTimestamp() {
            long epochMillis = 1_700_000_000_000L; // deterministic timestamp
            InstagramWebhookPayload payload = buildDmPayload(
                    "1000000003", "9000000003", "1000000003",
                    epochMillis, "mid3", "Test"
            );

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).timestamp().toEpochMilli()).isEqualTo(epochMillis);
        }

        @Test
        @DisplayName("handles null message text → empty string")
        void nullTextBecomesEmpty() {
            InstagramWebhookPayload payload = buildDmPayload(
                    "1000000004", "9000000004", "1000000004",
                    System.currentTimeMillis(), "mid4", null
            );

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).messageText()).isEmpty();
            assertThat(dtos.get(0).hasText()).isFalse();
        }

        @Test
        @DisplayName("parses multiple messaging events from a single entry")
        void multipleMessagingEvents() {
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            payload.setObject("instagram");

            Entry entry = new Entry();
            entry.setId("1000000005");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(List.of(
                    buildMessaging("9000000005", "1000000005", System.currentTimeMillis(), "midA", "first"),
                    buildMessaging("8000000005", "1000000005", System.currentTimeMillis(), "midB", "second")
            ));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(2);
        }
    }

    // ── Deleted messages ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Deleted messages")
    class DeletedMessages {

        @Test
        @DisplayName("sets empty text for deleted messages")
        void deletedMessageHasEmptyText() {
            Messaging messaging = buildMessaging(
                    "9000000010", "1000000010",
                    System.currentTimeMillis(), "midDel", "Original text"
            );
            // Mark as deleted
            messaging.getMessage().setIsDeleted(true);

            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000010");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(List.of(messaging));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).messageText()).isEmpty();
        }
    }

    // ── Resilience: skips bad entries, continues others ──────────────────────

    @Nested
    @DisplayName("Resilience")
    class Resilience {

        @Test
        @DisplayName("skips entry with missing sender, still parses valid entry in batch")
        void skipsMissingSender() {
            // Bad event: sender is null
            Messaging badMessaging = new Messaging();
            badMessaging.setSender(null);
            badMessaging.setRecipient(buildRecipient("1000000020"));
            badMessaging.setTimestamp(System.currentTimeMillis());
            Message msg = new Message();
            msg.setText("test");
            badMessaging.setMessage(msg);

            // Good event
            Messaging goodMessaging = buildMessaging(
                    "9000000020", "1000000020",
                    System.currentTimeMillis(), "midGood", "Valid"
            );

            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000020");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(List.of(badMessaging, goodMessaging));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            // Only the good one should survive
            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).senderId()).isEqualTo("9000000020");
        }

        @Test
        @DisplayName("entry with no messaging events produces UNKNOWN DTO")
        void noMessagingProducesUnknown() {
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000030");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(null);
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).messageType()).isEqualTo(MessageType.UNKNOWN);
        }
    }

    // ── Echo filtering ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Echo filtering")
    class EchoFiltering {

        @Test
        @DisplayName("drops echo messages (is_echo=true) silently")
        void echoMessageDropped() {
            Messaging echoMsg = buildMessaging(
                    "9000000099", "1000000099",
                    System.currentTimeMillis(), "midEcho", "bot reply"
            );
            echoMsg.getMessage().setIsEcho(true);

            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000099");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(List.of(echoMsg));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            // Echo message must be dropped — list should be empty
            assertThat(dtos).isEmpty();
        }

        @Test
        @DisplayName("non-echo message alongside echo — only non-echo survives")
        void onlyNonEchoSurvives() {
            Messaging echoMsg = buildMessaging(
                    "9000000098", "1000000098",
                    System.currentTimeMillis(), "midEcho", "bot reply");
            echoMsg.getMessage().setIsEcho(true);

            Messaging realMsg = buildMessaging(
                    "9000000097", "1000000098",
                    System.currentTimeMillis(), "midReal", "real user message");

            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000098");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(List.of(echoMsg, realMsg));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).senderId()).isEqualTo("9000000097");
        }
    }

    // ── Comment events (entry.changes) ───────────────────────────────────────

    @Nested
    @DisplayName("Comment events")
    class CommentEvents {

        @Test
        @DisplayName("parses a comment change event into a COMMENT MessageDTO")
        void parsesCommentChange() {
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000040");
            entry.setTime(System.currentTimeMillis() / 1000);

            ChangeValue value = new ChangeValue();
            Sender from = new Sender();
            from.setId("9000000040");
            value.setFrom(from);
            value.setText("  Great PHOTO!  ");
            value.setId("comment-abc-123");
            value.setMediaId("media-xyz");

            Change change = new Change();
            change.setField("comments");
            change.setValue(value);
            entry.setChanges(List.of(change));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            MessageDTO dto = dtos.get(0);
            assertThat(dto.messageType()).isEqualTo(MessageType.COMMENT);
            assertThat(dto.senderId()).isEqualTo("9000000040");
            assertThat(dto.messageText()).isEqualTo("great photo!");   // normalized
            assertThat(dto.messageId()).isEqualTo("comment-abc-123");
            assertThat(dto.rawEventId()).isEqualTo("comment-abc-123"); // dedup key = comment ID
            assertThat(dto.igAccountId()).isEqualTo("1000000040");
        }

        @Test
        @DisplayName("non-comment change field is skipped")
        void nonCommentChangeSkipped() {
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000041");
            entry.setTime(System.currentTimeMillis() / 1000);

            Change change = new Change();
            change.setField("story_insights");  // not a comment
            entry.setChanges(List.of(change));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            // No comment events → empty list (not an UNKNOWN, because changes exist)
            assertThat(dtos).isEmpty();
        }
    }

    // ── Attachment detection ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Attachment detection")
    class AttachmentDetection {

        @Test
        @DisplayName("sets hasAttachment=true when message has attachments")
        void setsHasAttachment() {
            Messaging messaging = buildMessaging(
                    "9000000050", "1000000050",
                    System.currentTimeMillis(), "midAttach", null
            );

            // Add an attachment
            Attachment attachment = new Attachment();
            attachment.setType("image");
            AttachmentPayload ap = new AttachmentPayload();
            ap.setUrl("https://example.com/img.jpg");
            attachment.setPayload(ap);
            messaging.getMessage().setAttachments(List.of(attachment));

            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000050");
            entry.setTime(System.currentTimeMillis() / 1000);
            entry.setMessaging(List.of(messaging));
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);

            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).hasAttachment()).isTrue();
            assertThat(dtos.get(0).hasText()).isFalse();   // no text, only attachment
        }
    }

    // ── isActionable flag ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("isActionable helper")
    class IsActionable {

        @Test
        @DisplayName("non-deleted DM with text is actionable")
        void dmWithTextIsActionable() {
            List<MessageDTO> dtos = parser.parse(buildDmPayload(
                    "1000000060", "9000000060", "1000000060",
                    System.currentTimeMillis(), "midAct", "hello"));
            assertThat(dtos.get(0).isActionable()).isTrue();
        }

        @Test
        @DisplayName("UNKNOWN type is never actionable")
        void unknownTypeNotActionable() {
            // Entry with no messaging or changes → UNKNOWN DTO
            InstagramWebhookPayload payload = new InstagramWebhookPayload();
            Entry entry = new Entry();
            entry.setId("1000000061");
            entry.setTime(System.currentTimeMillis() / 1000);
            payload.setEntry(List.of(entry));

            List<MessageDTO> dtos = parser.parse(payload);
            assertThat(dtos).hasSize(1);
            assertThat(dtos.get(0).isActionable()).isFalse();
        }
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private InstagramWebhookPayload buildDmPayload(
            String igAccountId, String senderId, String recipientId,
            long timestampMillis, String mid, String text) {

        InstagramWebhookPayload payload = new InstagramWebhookPayload();
        payload.setObject("instagram");

        Entry entry = new Entry();
        entry.setId(igAccountId);
        entry.setTime(timestampMillis / 1000);
        entry.setMessaging(List.of(
                buildMessaging(senderId, recipientId, timestampMillis, mid, text)
        ));
        payload.setEntry(List.of(entry));
        return payload;
    }

    private Messaging buildMessaging(
            String senderId, String recipientId,
            long timestampMillis, String mid, String text) {

        Messaging m = new Messaging();
        m.setSender(buildSender(senderId));
        m.setRecipient(buildRecipient(recipientId));
        m.setTimestamp(timestampMillis);

        Message msg = new Message();
        msg.setMid(mid);
        msg.setText(text);
        msg.setIsDeleted(false);
        m.setMessage(msg);
        return m;
    }

    private Sender buildSender(String id) {
        Sender s = new Sender();
        s.setId(id);
        return s;
    }

    private Recipient buildRecipient(String id) {
        Recipient r = new Recipient();
        r.setId(id);
        return r;
    }
}

