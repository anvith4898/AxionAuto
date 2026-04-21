# Backend — Spring Boot Application

## Entry Point

**`AxionAuthApplication.java`**

```java
@SpringBootApplication
@EnableScheduling   // TokenRefreshScheduler, DlqReplayService.refreshDlqDepth()
public class AxionAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AxionAuthApplication.class, args);
    }
}
```

`@EnableScheduling` is required for the proactive token refresh cron job and the DLQ depth gauge refresh (every 30 seconds).

Java 21 virtual threads are enabled globally via `spring.threads.virtual.enabled=true`, meaning every incoming request and background task runs on a lightweight virtual thread instead of a platform thread from the Tomcat pool.

---

## Configuration Beans

### `MetaOAuthProperties`

```java
@ConfigurationProperties(prefix = "axion.meta")
public record MetaOAuthProperties(
    String appId,
    String appSecret,
    String redirectUri,
    String webhookVerifyToken,
    String graphApiBaseUrl,
    String graphApiVersion,
    List<String> scopes
) {
    public String scopesAsString() { return String.join(",", scopes); }
    public String dialogBaseUrl()  { return "https://www.facebook.com/dialog/oauth"; }
}
```

All Meta credentials are injected via environment variables:

| Env Var | Purpose |
|---|---|
| `META_APP_ID` | Facebook App ID |
| `META_APP_SECRET` | Facebook App Secret (HMAC key for webhook validation) |
| `META_OAUTH_REDIRECT_URI` | OAuth callback URL registered in the Meta App dashboard |
| `META_WEBHOOK_VERIFY_TOKEN` | Token used to verify Meta's hub challenge |
| `META_GRAPH_API_BASE_URL` | Defaults to `https://graph.facebook.com` |
| `META_GRAPH_API_VERSION` | Defaults to `v20.0` |

### `TokenEncryptionProperties`

```java
@ConfigurationProperties(prefix = "axion.token")
public record TokenEncryptionProperties(String encryptionKey) {}
```

`TOKEN_ENCRYPTION_KEY` is a Base64-encoded 32-byte AES key. Must be kept in a secret manager (AWS Secrets Manager, Vault, etc.) — never committed to source control.

### `OAuthInfrastructureConfig`

Declares three beans:

1. **`RestClient metaGraphApiRestClient`** — configured with the base URL and default headers. Used by `MetaGraphApiClient` to call the Graph API.

2. **`StringRedisTemplate`** — the default template used for idempotency keys, rate-limit sorted sets, and stream operations.

3. **`RedisTemplate<String, String> oauthStateRedisTemplate`** — a separate template specifically for OAuth state storage, ensuring serialization isolation from the main template.

### `SecurityConfig`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/webhooks/**").permitAll()
                .requestMatchers("/api/v1/oauth/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

Webhook and OAuth endpoints are public (Meta calls them unauthenticated). CSRF is disabled because the API is stateless JSON REST. Authentication for protected endpoints (future: rule CRUD) would be JWT-based.

---

## Key Services

### `MetaGraphApiClient`

Low-level HTTP wrapper around the Meta Graph API. Uses Spring's `RestClient`:

| Method | Graph API endpoint | Purpose |
|---|---|---|
| `exchangeCodeForToken(code)` | `POST /oauth/access_token` | Short-lived token exchange |
| `extendToken(token)` | `GET /oauth/access_token?grant_type=fb_exchange_token` | Short → long-lived token |
| `fetchInstagramBusinessAccount(token)` | `GET /me?fields=instagram_business_account` | Get IG Business Account ID |
| `sendMessage(igAccountId, token, body)` | `POST /{ig-account-id}/messages` | Send a reply DM |

### `TokenEncryptionService`

AES-256-GCM symmetric encryption. Each call to `encrypt()` generates a fresh 12-byte random IV:

```java
public EncryptedTokenResult encrypt(String plaintext) {
    byte[] iv = new byte[12];
    secureRandom.nextBytes(iv);          // unique per operation
    GCMParameterSpec spec = new GCMParameterSpec(128, iv);  // 128-bit auth tag
    // ... cipher.doFinal() produces ciphertext + auth tag appended
    return new EncryptedTokenResult(ciphertext, iv, authTag);
}

public String decrypt(byte[] ciphertext, byte[] iv, byte[] authTag) {
    // GCM decryption also verifies the auth tag — throws if tampered
}
```

**Security properties:**
- Key: 256-bit AES loaded from `TOKEN_ENCRYPTION_KEY` env var
- IV: 12 bytes, cryptographically random, unique per token store/update
- Tag: 16-byte GCM authentication tag stored separately → prevents silent decryption of tampered ciphertext
- `@ToString.Exclude` prevents tokens from appearing in any log output

### `WebhookIdempotencyService`

Two-layer guard against duplicate event processing:

```
Layer 1: Redis SETNX
  Key: idempotency:webhook:{eventId}   TTL: 24 hours
  Action: SET NX (only succeeds if key doesn't exist)
  → If key exists: duplicate detected in < 1ms, no DB hit

Layer 2: DB Unique Constraint
  Table: instagram_webhook_events.event_id (UNIQUE)
  Action: INSERT ... ON CONFLICT → detect race conditions
  → Handles case where two pods pass Redis simultaneously
```

```java
public boolean processAndCheckIdempotency(String eventId, String rawPayload) {
    String key = "idempotency:webhook:" + eventId;
    Boolean isNew = redisTemplate.opsForValue()
            .setIfAbsent(key, "1", Duration.ofHours(24));
    if (Boolean.FALSE.equals(isNew)) {
        return false; // duplicate
    }
    // Persist the event record (will throw DataIntegrityViolationException on race)
    try {
        webhookEventRepository.save(new WebhookEventEntity(eventId, rawPayload));
        return true;
    } catch (DataIntegrityViolationException e) {
        return false; // race condition — another pod won
    }
}
```

### `WebhookSignatureValidator`

Validates the `X-Hub-Signature-256` header using HMAC-SHA256:

```java
public boolean isValidSignature(String payload, String signatureHeader) {
    // Header format: "sha256={hex-encoded-hmac}"
    String actual   = signatureHeader.substring(7);
    String expected = hmacSha256(payload, properties.appSecret());
    // Timing-safe comparison — prevents timing oracle attacks
    return MessageDigest.isEqual(
        actual.getBytes(UTF_8),
        expected.getBytes(UTF_8)
    );
}
```

Note: `MessageDigest.isEqual()` takes constant time regardless of where strings differ — this prevents an attacker from deducing the correct HMAC byte-by-byte from response timing.

### `GraphApiRateLimiter`

Redis sorted-set sliding window implementation:

```java
// Key: "rate_limit:ig_account:{igAccountId}"
// Score and member: epoch milliseconds (unique per call)
// Window: configurable, defaults to 3600 seconds (1 hour)
// Limit: configurable, defaults to 200 requests/hour (Instagram's DM limit)

public void acquireOrThrow(String instagramAccountId) {
    long nowMs    = Instant.now().toEpochMilli();
    long cutoffMs = nowMs - (windowSeconds * 1000L);

    // 1. Evict stale entries
    redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoffMs);
    // 2. Add current request
    redisTemplate.opsForZSet().add(key, nowMs, nowMs);
    // 3. Set TTL for self-cleaning
    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 10));
    // 4. Count requests in window
    Long count = redisTemplate.opsForZSet().zCard(key);
    if (count > maxRequestsPerWindow) {
        redisTemplate.opsForZSet().remove(key, nowMs); // undo the add
        throw new RateLimitExceededException(...);
    }
}
```

### `TokenRefreshScheduler`

Runs daily at 02:00 AM server time. Finds all ACTIVE tokens expiring within 48 hours and proactively refreshes them:

```
1. Query: SELECT * FROM instagram_oauth_tokens WHERE status='ACTIVE' AND token_expiry < now()+48h
2. For each token:
   a. Decrypt current token
   b. Call GET /oauth/access_token?grant_type=fb_exchange_token (Meta extends it another 60 days)
   c. Encrypt new token
   d. Update DB: new ciphertext + IV + tag + expiry + lastRefreshedAt
   e. Reset refreshAttempts to 0
3. On failure:
   - Increment refreshAttempts
   - After 3 failures: set status = REFRESH_FAILED
4. Emit Micrometer counters: pipeline.token.refresh.success / pipeline.token.refresh.failed
```

---

## Exception Hierarchy

```
RuntimeException
├── OAuthException(OAuthErrorCode, message)
│     Codes: INVALID_STATE, CODE_EXCHANGE_FAILED, TOKEN_EXTENSION_FAILED,
│             UNEXPECTED_ERROR
├── RateLimitExceededException
├── TransientApiException     ← triggers Resilience4j retry
├── PermanentApiException     ← NOT retried (e.g. 403 from Meta)
└── WebhookParseException(message, rawEventId)
```

`TransientApiException` and `PermanentApiException` are referenced in `application.yml` under `resilience4j.retry.instances.meta-graph-api.retry-exceptions` / `ignore-exceptions`.
