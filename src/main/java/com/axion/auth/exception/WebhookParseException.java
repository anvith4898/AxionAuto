package com.axion.auth.exception;

/**
 * Thrown when an inbound webhook payload is structurally invalid or unsupported
 * and cannot be normalized into a {@link com.axion.auth.domain.model.MessageDTO}.
 *
 * <p>Callers should catch this exception and either:
 * <ul>
 *   <li>Log and discard the event (idempotency already handled upstream), or</li>
 *   <li>Route the raw payload to a Dead Letter Queue (DLQ) for manual inspection.</li>
 * </ul>
 *
 * <p>This is intentionally a {@link RuntimeException} to allow it to propagate through
 * functional pipelines (e.g. stream processing, Spring event listeners) without forcing
 * blanket checked-exception handling on callers.
 */
public class WebhookParseException extends RuntimeException {

    /** Identifies the event that failed parsing, for correlation in logs. */
    private final String rawEventId;

    public WebhookParseException(String message, String rawEventId) {
        super(message);
        this.rawEventId = rawEventId;
    }

    public WebhookParseException(String message, String rawEventId, Throwable cause) {
        super(message, cause);
        this.rawEventId = rawEventId;
    }

    public String getRawEventId() {
        return rawEventId;
    }
}
