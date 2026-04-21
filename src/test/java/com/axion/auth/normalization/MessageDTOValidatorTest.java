package com.axion.auth.normalization;

import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import com.axion.auth.normalization.MessageDTOValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link MessageDTOValidator}.
 * No Spring context required.
 */
@DisplayName("MessageDTOValidator")
class MessageDTOValidatorTest {

    private MessageDTOValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MessageDTOValidator();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Valid DTOs")
    class ValidDtos {

        @Test
        @DisplayName("valid DM DTO passes all rules")
        void validDmDto() {
            MessageDTO dto = validDm("9876543210", "hello world", Instant.now());
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isTrue();
            assertThat(result.errorCode()).isNull();
        }

        @Test
        @DisplayName("valid DTO with empty text (media-only DM) passes")
        void validDtoEmptyText() {
            MessageDTO dto = new MessageDTO(
                    "9876543210", "1234567890", "mid1",
                    "", MessageType.DM, Instant.now(), "mid1", "1234567890"
            );
            assertThat(validator.validate(dto).valid()).isTrue();
        }
    }

    // ── senderId rules ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("senderId validation")
    class SenderIdValidation {

        @Test
        @DisplayName("blank senderId fails MessageDTO compact constructor before reaching validator")
        void blankSenderIdFailsConstruction() {
            // MessageDTO's compact constructor enforces non-blank senderId eagerly.
            // The validator's MISSING_SENDER_ID rule is a belt-and-suspenders guard
            // for DTOs constructed outside the normal parser flow.
            assertThatThrownBy(() -> new MessageDTO(
                    "", "1234567890", "mid1",
                    "hello", MessageType.DM, Instant.now(), "mid1", "1234567890"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("senderId must not be null/blank");
        }

        @Test
        @DisplayName("rejects non-numeric senderId")
        void nonNumericSenderIdRejected() {
            MessageDTO dto = buildDto("abc_xyz", "hello", MessageType.DM, Instant.now());
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("INVALID_SENDER_ID");
        }

        @Test
        @DisplayName("rejects senderId shorter than 6 digits")
        void tooShortSenderIdRejected() {
            MessageDTO dto = buildDto("12345", "hello", MessageType.DM, Instant.now());
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("INVALID_SENDER_ID");
        }

        @Test
        @DisplayName("accepts senderId of exactly 6 digits")
        void exactlyMinLengthSenderAccepted() {
            MessageDTO dto = buildDto("123456", "hello", MessageType.DM, Instant.now());
            assertThat(validator.validate(dto).valid()).isTrue();
        }
    }

    // ── igAccountId rules ───────────────────────────────────────────────────

    @Nested
    @DisplayName("igAccountId validation")
    class IgAccountIdValidation {

        @Test
        @DisplayName("rejects DTO with blank igAccountId")
        void blankIgAccountIdRejected() {
            // Build DTO directly with blank igAccountId (bypass 8-arg constructor's defaults)
            MessageDTO dto = new MessageDTO(
                    "9876543210", "1234567890", "mid1",
                    "hello", MessageType.DM, Instant.now(), "mid1", "");
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("MISSING_IG_ACCOUNT_ID");
        }
    }

    // ── timestamp rules ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Timestamp validation")
    class TimestampValidation {

        @Test
        @DisplayName("rejects timestamp more than 30s in the future")
        void futureTimestampRejected() {
            Instant future = Instant.now().plusSeconds(120);
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.DM, future);
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TIMESTAMP_IN_FUTURE");
        }

        @Test
        @DisplayName("rejects timestamp older than 24 hours")
        void staleTimestampRejected() {
            Instant old = Instant.now().minus(25, ChronoUnit.HOURS);
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.DM, old);
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TIMESTAMP_TOO_OLD");
        }

        @Test
        @DisplayName("accepts timestamp at now minus 23 hours")
        void recentTimestampAccepted() {
            Instant recent = Instant.now().minus(23, ChronoUnit.HOURS);
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.DM, recent);
            assertThat(validator.validate(dto).valid()).isTrue();
        }
    }

    // ── message type rules ────────────────────────────────────────────────────

    @Nested
    @DisplayName("MessageType validation")
    class MessageTypeValidation {

        @Test
        @DisplayName("rejects UNKNOWN message type")
        void unknownTypeRejected() {
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.UNKNOWN, Instant.now());
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("UNKNOWN_MESSAGE_TYPE");
        }

        @Test
        @DisplayName("accepts COMMENT type")
        void commentTypeAccepted() {
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.COMMENT, Instant.now());
            assertThat(validator.validate(dto).valid()).isTrue();
        }
    }

    // ── text length rules ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Text length validation")
    class TextLengthValidation {

        @Test
        @DisplayName("rejects text longer than 2048 chars")
        void tooLongTextRejected() {
            String longText = "a".repeat(2049);
            MessageDTO dto = buildDto("9876543210", longText, MessageType.DM, Instant.now());
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TEXT_TOO_LONG");
        }

        @Test
        @DisplayName("accepts text of exactly 2048 chars")
        void maxLengthTextAccepted() {
            String maxText = "a".repeat(2048);
            MessageDTO dto = buildDto("9876543210", maxText, MessageType.DM, Instant.now());
            assertThat(validator.validate(dto).valid()).isTrue();
        }
    }

    // ── Deleted message consistency ────────────────────────────────────────────

    @Nested
    @DisplayName("Deleted message consistency")
    class DeletedMessageConsistency {

        @Test
        @DisplayName("rejects deleted message with non-empty text (parser did not clear it)")
        void deletedWithTextRejected() {
            MessageDTO dto = new MessageDTO(
                    "9876543210", "1234567890", "mid1",
                    "some text", MessageType.DM, Instant.now(), "mid1", "1234567890",
                    true, false);
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThat(result.errorCode()).isEqualTo("DELETED_MESSAGE_HAS_TEXT");
        }

        @Test
        @DisplayName("accepts deleted message with empty text (correctly parsed)")
        void deletedWithEmptyTextAccepted() {
            MessageDTO dto = new MessageDTO(
                    "9876543210", "1234567890", "mid1",
                    "", MessageType.DM, Instant.now(), "mid1", "1234567890",
                    true, false);
            assertThat(validator.validate(dto).valid()).isTrue();
        }
    }

    // ── allViolations ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("allViolations")
    class AllViolations {

        @Test
        @DisplayName("returns empty list for a fully valid DTO")
        void noViolationsForValidDto() {
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.DM, Instant.now());
            assertThat(ValidationResult.allViolations(validator, dto)).isEmpty();
        }

        @Test
        @DisplayName("detects both UNKNOWN type and blank igAccountId together")
        void collectsMultipleViolations() {
            MessageDTO dto = new MessageDTO(
                    "9876543210", "", "",
                    "hi", MessageType.UNKNOWN, Instant.now(), "ev1", "");

            var violations = ValidationResult.allViolations(validator, dto);

            assertThat(violations)
                    .extracting(ValidationResult::errorCode)
                    .containsExactlyInAnyOrder("MISSING_IG_ACCOUNT_ID", "UNKNOWN_MESSAGE_TYPE");
        }
    }

    // ── ValidationResult helpers ──────────────────────────────────────────────


    @Nested
    @DisplayName("ValidationResult behaviour")
    class ValidationResultBehaviour {

        @Test
        @DisplayName("toException() returns WebhookParseException for invalid result")
        void toExceptionOnInvalid() {
            MessageDTO dto = buildDto("short", "hi", MessageType.DM, Instant.now()); // invalid sender
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isFalse();
            assertThatCode(result::toException)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("toException() throws IllegalStateException on valid result")
        void toExceptionThrowsOnValid() {
            MessageDTO dto = buildDto("9876543210", "hello", MessageType.DM, Instant.now());
            ValidationResult result = validator.validate(dto);

            assertThat(result.valid()).isTrue();
            assertThatThrownBy(result::toException)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private MessageDTO validDm(String senderId, String text, Instant timestamp) {
        return buildDto(senderId, text, MessageType.DM, timestamp);
    }

    private MessageDTO buildDto(String senderId, String text, MessageType type, Instant timestamp) {
        return new MessageDTO(
                senderId,
                "1234567890",
                "mid-test",
                text,
                type,
                timestamp,
                "mid-test",
                "1234567890"
        );
    }
}
