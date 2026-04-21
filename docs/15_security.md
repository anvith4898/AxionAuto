# Security

## Overview

AxionAuto implements security at five distinct levels:

1. **Transport Security** — HTTPS in production (TLS termination at load balancer or reverse proxy)
2. **Webhook Authentication** — HMAC-SHA256 signature validation on every incoming Meta webhook
3. **OAuth CSRF Protection** — State token pattern using Redis for storage
4. **Token Storage Security** — AES-256-GCM encryption for all Meta access tokens
5. **Database Security** — PostgreSQL Row-Level Security for multi-tenant data isolation

---

## 1. Webhook HMAC-SHA256 Validation

Every webhook POST from Meta includes an `X-Hub-Signature-256` header:
```
X-Hub-Signature-256: sha256={hex_encoded_hmac}
```

The HMAC is computed by Meta as:
```
HMAC-SHA256(raw_request_body, META_APP_SECRET)
```

`WebhookSignatureValidator` recomputes this and compares:

```java
// Timing-safe comparison using MessageDigest.isEqual()
// This takes constant time regardless of where strings differ
// Prevents timing oracle attacks where an attacker could deduce
// the correct HMAC byte-by-byte from differences in response time
return MessageDigest.isEqual(
    actualSignature.getBytes(UTF_8),
    expectedSignature.getBytes(UTF_8)
);
```

**Why timing-safe comparison is critical:** A standard string `equals()` short-circuits on the first mismatched byte. An attacker sending thousands of forged requests could measure response times and determine the correct signature byte-by-byte (timing oracle attack). `MessageDigest.isEqual()` always compares all bytes, taking constant time.

**Consequences of failed validation:** Request is rejected immediately with HTTP 403. The event is never stored, queued, or processed. The rejection is counted in the `pipeline.events.rejected` metric.

---

## 2. OAuth CSRF Protection — State Token Pattern

During the Instagram OAuth flow, a forged callback request could trick the application into associating a malicious account with a legitimate user (login CSRF / OAuth CSRF attack).

**Protection mechanism:**

```
Step 1: Application generates random stateToken = UUID.randomUUID()
        Stores in Redis:
          SET oauth:state:{stateToken} {tenantId, userId, ...} EX 600
        Includes stateToken in the authorization URL as &state={stateToken}

Step 2: User authenticates on Meta → Meta echoes state in callback URL

Step 3: Application calls:
          GETDEL oauth:state:{stateToken}  ← atomic get + delete
        If nil → state expired or never issued → reject (OAuthException.INVALID_STATE)
        If found → deserialize, extract tenantId/userId, continue flow
```

**Properties:**
- **Random**: `UUID.randomUUID()` is cryptographically random — unpredictable
- **Time-limited**: 10-minute TTL ensures the state can't be reused after the flow window closes
- **Single-use**: `GETDEL` atomically consumes the state — a replayed callback URL will find the key already deleted
- **Tenant-bound**: The `OAuthStatePayload` binds the state to a specific `tenantId` and `userId` — the attacker can't substitute their own

---

## 3. Access Token Encryption — AES-256-GCM

Meta access tokens are sensitive credentials with full permission to read DMs and send messages on behalf of a business. Storing them in plaintext would be catastrophic if the database were compromised.

### Encryption Algorithm

**Algorithm:** AES-256-GCM (Advanced Encryption Standard, Galois/Counter Mode)
- **Key length:** 256 bits (32 bytes) — loaded from `TOKEN_ENCRYPTION_KEY` environment variable
- **IV (Initialization Vector):** 12 bytes, generated fresh per encryption operation using `SecureRandom`
- **Authentication tag:** 16 bytes — provides tamper detection (GCM authenticated encryption)

```java
public EncryptedTokenResult encrypt(String plaintext) {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);  // unique per token, per encrypt operation

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,
        new SecretKeySpec(keyBytes, "AES"),
        new GCMParameterSpec(128, iv));  // 128-bit auth tag

    byte[] encrypted = cipher.doFinal(plaintext.getBytes(UTF_8));
    // encrypted contains: ciphertext + 16-byte auth tag appended
    // Split tag out for separate storage

    return new EncryptedTokenResult(ciphertext, iv, authTag);
}
```

### Storage Layout

Three PostgreSQL columns per token:

| Column | Size | Content |
|---|---|---|
| `access_token_encrypted` | BYTEA | AES-256-GCM ciphertext |
| `access_token_iv` | BYTEA (12 bytes) | Unique GCM nonce (Initialization Vector) |
| `access_token_tag` | BYTEA (16 bytes) | GCM authentication tag |

**Why separate IV storage?**
- Reusing an IV with the same key in GCM mode is catastrophic — it breaks both confidentiality and authentication
- Storing the IV separately (one per DB row) ensures every token uses a unique IV
- The IV is not secret — it only needs to be unique

**Why separate auth tag?**
- The GCM auth tag is verified during decryption — if the ciphertext has been tampered with, decryption throws `AEADBadTagException`
- Separating it allows explicit integrity checking before any processing

### `@ToString.Exclude` — Preventing Token Logging

```java
@ToString(exclude = {"accessTokenEncrypted", "accessTokenIv", "accessTokenTag"})
public class InstagramOAuthToken { ... }
```

This Lombok annotation excludes the encrypted token fields from any `toString()` output, preventing accidental logging of token bytes.

### Key Management Requirements

The `TOKEN_ENCRYPTION_KEY` must be:
- A Base64-encoded, cryptographically random 32-byte value
- Stored in a secrets manager (AWS Secrets Manager, HashiCorp Vault, GCP Secret Manager)
- **Never committed to source control or Docker images**
- Rotated periodically (requires re-encryption of all stored tokens — this is a future enhancement)

---

## 4. Spring Security Filter Chain

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())           // stateless REST API
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/webhooks/**").permitAll()  // Meta calls these
                .requestMatchers("/api/v1/oauth/**").permitAll()     // OAuth redirect flow
                .requestMatchers("/actuator/**").permitAll()         // monitoring
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

**Why CSRF is disabled:** CSRF (Cross-Site Request Forgery) protection is only relevant for cookie-based session authentication. This API is stateless JSON REST — no cookies, no browser session. Disabling CSRF on a pure API is correct.

**Why webhooks/oauth are public:** Meta's servers call these URLs without any AxionAuto-issued credentials. Security is enforced at the application level (HMAC validation for webhooks, state token for OAuth) rather than the HTTP layer.

---

## 5. PostgreSQL Row-Level Security (Multi-Tenant Isolation)

Every table has RLS enabled:

```sql
ALTER TABLE {table} ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_{table} ON {table}
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
```

Before any DB query, the application sets the session variable:
```sql
SET LOCAL app.current_tenant = '{tenantId}';
```

**What this protects against:**
- Application bug that forgets to filter by `tenant_id` — DB will still only return the current tenant's rows
- Direct DB access (e.g., DBA running a query) — still enforces tenant isolation unless the `app.current_tenant` setting is explicitly set
- SQL injection that tries to read other tenants' data — the policy blocks it even if the query omits the tenant filter

---

## 6. Environment Variable Security

Sensitive values are **never** hardcoded. They are injected at runtime via environment variables:

| Env Var | Sensitivity | Usage |
|---|---|---|
| `META_APP_SECRET` | Very High | HMAC-SHA256 webhook signing key |
| `TOKEN_ENCRYPTION_KEY` | Very High | AES-256-GCM key for token at-rest encryption |
| `META_APP_ID` | Medium | OAuth app identifier (public, but scoped) |
| `META_OAUTH_REDIRECT_URI` | Medium | Must match Meta dashboard configuration exactly |
| `META_WEBHOOK_VERIFY_TOKEN` | Medium | Webhook subscription verification |
| `DB_PASSWORD` | High | PostgreSQL authentication |
| `REDIS_PASSWORD` | High | Redis authentication |

---

## 7. Multi-Tenant Data Isolation Summary

| Layer | Mechanism | Scope |
|---|---|---|
| HTTP | JWT / session authentication (plugged in) | Identifies tenant |
| Application | All queries include `tenantId` parameter | Logical isolation |
| Database | Row-Level Security policy | Physical enforcement |
| Cache | `RuleCache` key = `tenantId:igAccountId` | Prevents cross-tenant cache hits |
| Redis | Per-account keyspace convention | No tenant cross-contamination |
| Token storage | `uq_tenant_ig_account` unique constraint | One token per IG account per tenant |

---

## 8. Security Checklist Status

| Control | Status | Notes |
|---|---|---|
| HMAC-SHA256 webhook validation (timing-safe) | ✅ Implemented | `MessageDigest.isEqual()` |
| OAuth CSRF state token (Redis, 10-min TTL, single-use) | ✅ Implemented | `GETDEL` atomic |
| AES-256-GCM token encryption at rest | ✅ Implemented | Per-value IV, auth tag |
| `@ToString.Exclude` on sensitive fields | ✅ Implemented | Prevents token logging |
| PostgreSQL RLS for tenant isolation | ✅ Implemented | V1–V4 migrations |
| CSRF disabled (correct for stateless API) | ✅ Configured | `SecurityConfig` |
| Environment-variable secrets (no hardcoding) | ✅ Enforced | `@ConfigurationProperties` |
| Resilience4j circuit breaker (DoS protection toward Meta API) | ✅ Configured | Prevents cascading failures |
| Rate limiting (200 calls/hour per IG account) | ✅ Implemented | Sliding window |
| Proactive token refresh (no surprise expiry) | ✅ Implemented | `TokenRefreshScheduler` |
| Replay-attack protection (24h timestamp check) | ✅ Implemented | `MessageDTOValidator` |
| Key rotation mechanism | ⚠️ Not yet | Requires re-encryption pipeline |
| mTLS / JWT for tenant authentication | ⚠️ Planned | Not yet implemented |
| Webhook IP allowlisting (Meta source IPs) | ⚠️ Not yet | Configure at load balancer |
