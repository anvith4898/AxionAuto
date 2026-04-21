package com.axion.auth.normalization;

import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import com.axion.auth.exception.WebhookParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link MessageDTO} produced by {@link WebhookPayloadParser}.
 *
 * <h3>Validation rules (all evaluated — first failure stops evaluation per rule group):</h3>
 * <ol>
 *   <li><b>senderId</b> — non-blank; numeric IGSID of ≥ 6 digits
 *       (Meta IGSID format: all numeric, minimum 6 digits)</li>
 *   <li><b>igAccountId</b> — non-blank; required for automation routing</li>
 *   <li><b>timestamp</b> — not more than {@value #CLOCK_SKEW_TOLERANCE_SECONDS}s in the future;
 *       not older than {@value #MAX_AGE_SECONDS}s (replay protection)</li>
 *   <li><b>messageType</b> — not {@code UNKNOWN} (those are parked to DLQ)</li>
 *   <li><b>messageText</b> — if present, must be ≤ {@value #MAX_TEXT_LENGTH} chars after
 *       normalization (truncation in the parser prevents this for well-formed events)</li>
 * </ol>
 *
 * <h3>DLQ parking strategy:</h3>
 * <p>When {@link #validate} returns a {@link ValidationResult} with
 * {@link ValidationResult#valid()} == {@code false}, the caller should route the raw
 * event to the dead-letter stream (e.g. {@code instagram-webhooks-dlq} Redis stream)
 * for manual inspection. This class does <em>not</em> write to the DLQ directly to
 * keep it single-responsibility and easily testable.
 */
@Slf4j
@Component
public class MessageDTOValidator {

    static final int  MAX_TEXT_LENGTH              = 2048;
    static final int  MIN_SENDER_LENGTH            = 6;

    /**
     * Reject events older than this many seconds (24 hours).
     * Prevents replay attacks and stale automation triggers.
     */
    static final long MAX_AGE_SECONDS              = 86_400L;

    /**
     * Tolerate timestamps up to this many seconds in the future.
     * Accounts for clock skew between Meta's servers and ours.
     * 30 seconds is generous; Meta typically sends within milliseconds.
     */
    static final long CLOCK_SKEW_TOLERANCE_SECONDS = 30L;

    /**
     * Validates a {@link MessageDTO} against all business rules.
     *
     * <p>Returns the result of the <em>first failing rule</em>. Once a critical rule
     * fails (e.g. missing senderId), subsequent rules are not evaluated because they
     * may themselves throw on a partially-invalid DTO.
     *
     * @param dto the DTO to validate; must not be null
     * @return a {@link ValidationResult} describing success or the first violated rule
     * @throws IllegalArgumentException if {@code dto} is null
     */
    public ValidationResult validate(MessageDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Cannot validate null MessageDTO");
        }

        // ── Rule 1: senderId format ───────────────────────────────────────────
        if (dto.senderId().isBlank()) {
            return ValidationResult.invalid(dto, "MISSING_SENDER_ID",
                    "senderId is blank");
        }
        // Meta IGSIDs are purely numeric and at least 6 digits long.
        // Reject non-numeric or too-short values — they indicate a parsing error.
        if (!dto.senderId().matches("\\d+") || dto.senderId().length() < MIN_SENDER_LENGTH) {
            return ValidationResult.invalid(dto, "INVALID_SENDER_ID",
                    "senderId must be a numeric IGSID of at least " + MIN_SENDER_LENGTH
                    + " digits; got: '" + dto.senderId() + "'");
        }

        // ── Rule 2: igAccountId must be non-blank ─────────────────────────────
        // The automation engine uses igAccountId to look up which tenant's rules to apply.
        // An empty igAccountId would cause every account's rules to be evaluated.
        if (dto.igAccountId().isBlank()) {
            return ValidationResult.invalid(dto, "MISSING_IG_ACCOUNT_ID",
                    "igAccountId is blank — cannot route event to tenant");
        }

        // ── Rule 3: timestamp bounds ─────────────────────────────────────────
        Instant now = Instant.now();
        if (dto.timestamp().isAfter(now.plusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))) {
            return ValidationResult.invalid(dto, "TIMESTAMP_IN_FUTURE",
                    "Event timestamp is " + dto.timestamp() + " which is more than "
                    + CLOCK_SKEW_TOLERANCE_SECONDS + "s in the future (clock skew threshold)");
        }
        long ageSeconds = now.getEpochSecond() - dto.timestamp().getEpochSecond();
        if (ageSeconds > MAX_AGE_SECONDS) {
            return ValidationResult.invalid(dto, "TIMESTAMP_TOO_OLD",
                    "Event is " + ageSeconds + "s old (max allowed: " + MAX_AGE_SECONDS
                    + "s / 24 hours)");
        }

        // ── Rule 4: message type must be actionable ──────────────────────────
        if (MessageType.UNKNOWN == dto.messageType()) {
            return ValidationResult.invalid(dto, "UNKNOWN_MESSAGE_TYPE",
                    "MessageType is UNKNOWN — event cannot be routed; park to DLQ for inspection");
        }

        // ── Rule 5: text length after normalization ──────────────────────────
        // Parser truncates to MAX_TEXT_LENGTH already, so this rule only fires if
        // a DTO is constructed directly without going through the parser.
        if (dto.messageText().length() > MAX_TEXT_LENGTH) {
            return ValidationResult.invalid(dto, "TEXT_TOO_LONG",
                    "messageText length " + dto.messageText().length()
                    + " exceeds max " + MAX_TEXT_LENGTH
                    + " — ensure parser truncation is applied before validation");
        }

        // ── Rule 6: deleted messages with non-empty text ─────────────────────
        // Deleted messages should have their text cleared in the parser.
        // If we see a non-empty text on a deleted message, something is wrong upstream.
        if (dto.isDeleted() && dto.hasText()) {
            return ValidationResult.invalid(dto, "DELETED_MESSAGE_HAS_TEXT",
                    "Message is marked deleted but messageText is non-empty — parser did not clear text");
        }

        log.debug("[validator] MessageDTO valid [senderId={}, type={}, mid={}, igAccountId={}]",
                dto.senderId(), dto.messageType(), dto.messageId(), dto.igAccountId());

        return ValidationResult.valid(dto);
    }

    // ── Inner result type ────────────────────────────────────────────────────

    /**
     * Value object representing the outcome of {@link #validate(MessageDTO)}.
     *
     * <p>Callers pattern-match on {@link #valid()} to decide whether to proceed or DLQ:
     * <pre>{@code
     * ValidationResult result = validator.validate(dto);
     * if (!result.valid()) {
     *     dlqProducer.park(dto.rawEventId(), result.errorCode(), result.errorMessage());
     *     return;
     * }
     * // safe to process dto
     * }</pre>
     *
     * @param valid        {@code true} if the DTO passed all rules
     * @param dto          the validated DTO (always present)
     * @param errorCode    short machine-readable error token, or {@code null} when valid
     * @param errorMessage human-readable explanation, or {@code null} when valid
     */
    public record ValidationResult(
            boolean    valid,
            MessageDTO dto,
            String     errorCode,
            String     errorMessage
    ) {
        /**
         * Collects <em>all</em> violated rules for a given DTO (not just the first).
         *
         * <p>Used in diagnostics/admin tooling where you want to see the complete
         * set of problems rather than stopping at the first failure.
         *
         * @param validator the validator instance to re-use
         * @param dto       the DTO to inspect
         * @return list of all invalid results; empty list means DTO is fully valid
         */
        public static List<ValidationResult> allViolations(
                MessageDTOValidator validator, MessageDTO dto) {
            List<ValidationResult> violations = new ArrayList<>();

            // Run all rules independently — clone the validator's logic to enable
            // collecting all failures in a single pass.
            Instant now = Instant.now();

            if (dto.senderId().isBlank()) {
                violations.add(invalid(dto, "MISSING_SENDER_ID", "senderId is blank"));
            } else if (!dto.senderId().matches("\\d+")
                    || dto.senderId().length() < MessageDTOValidator.MIN_SENDER_LENGTH) {
                violations.add(invalid(dto, "INVALID_SENDER_ID",
                        "senderId must be numeric and ≥ " + MIN_SENDER_LENGTH + " digits"));
            }

            if (dto.igAccountId().isBlank()) {
                violations.add(invalid(dto, "MISSING_IG_ACCOUNT_ID",
                        "igAccountId is blank"));
            }

            if (dto.timestamp().isAfter(now.plusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS))) {
                violations.add(invalid(dto, "TIMESTAMP_IN_FUTURE",
                        "Timestamp is in the future: " + dto.timestamp()));
            } else {
                long age = now.getEpochSecond() - dto.timestamp().getEpochSecond();
                if (age > MAX_AGE_SECONDS) {
                    violations.add(invalid(dto, "TIMESTAMP_TOO_OLD",
                            "Event is " + age + "s old"));
                }
            }

            if (dto.messageType() == MessageType.UNKNOWN) {
                violations.add(invalid(dto, "UNKNOWN_MESSAGE_TYPE",
                        "MessageType is UNKNOWN"));
            }

            if (dto.messageText().length() > MAX_TEXT_LENGTH) {
                violations.add(invalid(dto, "TEXT_TOO_LONG",
                        "Text length " + dto.messageText().length() + " > " + MAX_TEXT_LENGTH));
            }

            if (dto.isDeleted() && dto.hasText()) {
                violations.add(invalid(dto, "DELETED_MESSAGE_HAS_TEXT",
                        "Deleted message has non-empty text"));
            }

            return violations;
        }

        static ValidationResult valid(MessageDTO dto) {
            return new ValidationResult(true, dto, null, null);
        }

        static ValidationResult invalid(MessageDTO dto, String errorCode, String errorMessage) {
            return new ValidationResult(false, dto, errorCode, errorMessage);
        }

        /**
         * Converts a failed result to a {@link WebhookParseException} for callers that
         * prefer exception-based error propagation.
         *
         * @throws IllegalStateException if called on a valid result
         */
        public WebhookParseException toException() {
            if (valid) {
                throw new IllegalStateException("Cannot convert a valid result to exception");
            }
            return new WebhookParseException(errorMessage, dto.rawEventId());
        }
    }
}
