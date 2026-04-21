# Auth & OAuth — Instagram OAuth 2.0 Integration

## Overview

AxionAuto uses Meta's **OAuth 2.0 authorization code flow** to connect a tenant's Instagram Business Account. The resulting long-lived access token is stored encrypted in PostgreSQL and used to send automated replies via the Graph API.

Meta requires the `instagram_manage_messages` permission for DM sends. Tokens are long-lived (60-day expiry) and proactively refreshed by `TokenRefreshScheduler`.

---

## Required OAuth Scopes

```yaml
axion:
  meta:
    scopes:
      - instagram_basic                # Read IG profile and media
      - instagram_manage_messages      # Read and reply to DMs
      - instagram_manage_insights      # (optional) Account metrics
      - pages_show_list                # See linked Facebook Pages
      - pages_read_engagement          # Read Page interactions
      - business_management            # Business account management
```

---

## OAuth Flow — Step by Step

### Step 1: Generate Authorization URL

**Endpoint:** `GET /api/v1/oauth/instagram/authorize?userId={userId}&tenantId={tenantId}`

The controller calls `InstagramOAuthService.generateAuthorizationUrl()`:

1. Generates a random `stateToken` (UUID).
2. Builds an `OAuthStatePayload` record: `{ tenantId, userId, stateToken, redirectAfterCallback }`.
3. Serializes the payload to JSON and stores it in Redis:
   ```
   Key: oauth:state:{stateToken}
   Value: {JSON payload}
   TTL: 10 minutes
   ```
4. Constructs the authorization URL:
   ```
   https://www.facebook.com/dialog/oauth
     ?client_id={META_APP_ID}
     &redirect_uri={META_OAUTH_REDIRECT_URI}
     &scope=instagram_basic,instagram_manage_messages,...
     &response_type=code
     &state={stateToken}
   ```
5. Returns the URL to the client. The client redirects the user to this URL.

**CSRF Protection:** The `state` parameter is a random opaque token tied to the server-side session. Meta echoes it back in the callback — we verify it hasn't been tampered with by looking it up in Redis.

---

### Step 2: User Authorizes on Meta

The user is redirected to `facebook.com`, shown an Instagram permission consent screen, and (if approved) redirected to:
```
{META_OAUTH_REDIRECT_URI}?code={authorization_code}&state={stateToken}
```

---

### Step 3: Callback — Code Exchange & Persistence

**Endpoint:** `GET /api/v1/oauth/instagram/callback?code={code}&state={stateToken}`

`InstagramOAuthService.handleCallback()` executes a 6-step process:

#### 3a. Verify State (CSRF Check)

```java
String stateJson = sessionRedisTemplate.opsForValue()
        .getAndDelete("oauth:state:" + stateToken);
// If null → state expired or CSRF attack → throw OAuthException(INVALID_STATE)
```

`getAndDelete` is atomic — ensures the state token can only be consumed once.

#### 3b. Deserialize State Payload

Extracts `tenantId` and `userId` from the stored JSON to associate the incoming token with the correct tenant.

#### 3c. Exchange Code for Short-Lived Token

```
POST https://graph.facebook.com/oauth/access_token
  client_id={META_APP_ID}
  client_secret={META_APP_SECRET}
  redirect_uri={META_OAUTH_REDIRECT_URI}
  code={authorization_code}

→ Response: { access_token, token_type, expires_in }
```

Short-lived tokens expire in about 1 hour.

#### 3d. Extend to Long-Lived Token (60 days)

```
GET https://graph.facebook.com/oauth/access_token
  grant_type=fb_exchange_token
  client_id={META_APP_ID}
  client_secret={META_APP_SECRET}
  fb_exchange_token={short_lived_token}

→ Response: { access_token, token_type, expires_in (≈5184000 seconds) }
```

#### 3e. Fetch Instagram Business Account

```
GET https://graph.facebook.com/me
  fields=instagram_business_account
  access_token={long_lived_token}

→ Response: { instagram_business_account: { id: "17841400...", username: "mybiz" } }
```

#### 3f. Encrypt and Persist

```java
// Encrypt the token
EncryptedTokenResult encrypted = encryptionService.encrypt(longLivedToken);

// Upsert (handles re-authorization case)
Optional<InstagramOAuthToken> existing =
    tokenRepository.findByTenantIdAndInstagramAccountId(tenantId, igAccountId);

if (existing.isPresent()) {
    // Update ciphertext, IV, tag, expiry, status → ACTIVE
} else {
    // Create new token entity
}
tokenRepository.save(tokenEntity);
```

Returns `OAuthConnectionResult` with:
```json
{
  "tokenId": "uuid",
  "tenantId": "uuid",
  "userId": "string",
  "instagramAccountId": "17841400...",
  "instagramUsername": "mybizhandle",
  "tokenExpiry": "2026-06-19T02:00:00Z",
  "status": "ACTIVE",
  "isNew": true
}
```

---

## Token Entity — `InstagramOAuthToken`

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `tenant_id` | UUID | Immutable tenant discriminator |
| `user_id` | VARCHAR | Platform user who authorized |
| `instagram_account_id` | VARCHAR | IG Business Account ID from Meta |
| `instagram_username` | VARCHAR | @handle |
| `access_token_encrypted` | BYTEA | AES-256-GCM ciphertext |
| `access_token_iv` | BYTEA | 12-byte GCM nonce (unique per encrypt) |
| `access_token_tag` | BYTEA | 16-byte GCM authentication tag |
| `token_expiry` | TIMESTAMPTZ | When token expires |
| `status` | ENUM | `ACTIVE`, `EXPIRED`, `REFRESH_FAILED`, `REVOKED` |
| `refresh_attempts` | INT | Count of consecutive refresh failures |
| `last_refreshed_at` | TIMESTAMPTZ | Last successful refresh timestamp |
| `connected_at` | TIMESTAMPTZ | When the account was first connected |

**Unique constraints:**
- `(tenant_id, instagram_account_id)` — one token per IG account per tenant
- `(tenant_id, user_id)` — one connected account per platform user per tenant

---

## Token Lifecycle State Machine

```
                     ┌─────────────────────┐
                     │                     │
   OAuth callback ──►│       ACTIVE        │◄── proactive refresh succeeds
                     │                     │
                     └──────────┬──────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
              ▼                 ▼                 ▼
         token_expiry     refresh fails      user revokes
          exceeded        3 times            access
              │                 │                 │
              ▼                 ▼                 ▼
          EXPIRED        REFRESH_FAILED        REVOKED
```

- `REFRESH_FAILED`: Automatic refresh failed 3 consecutive times. The pipeline detects this and skips automation for that account with status = `FAILED:NO_TOKEN`.
- `REVOKED`: Set manually when a tenant disconnects their account via the UI.
- Orchestrator always checks `token.isExpired()` and `token.getStatus() == ACTIVE` before processing.

---

## Token Refresh Scheduler

`TokenRefreshScheduler` runs at `0 0 2 * * ?` (daily 02:00 AM):

```sql
-- Query for tokens expiring in < 48 hours
SELECT * FROM instagram_oauth_tokens
WHERE status = 'ACTIVE' AND token_expiry < now() + INTERVAL '48 hours'
ORDER BY token_expiry ASC;
```

On failure:
- `refreshAttempts` is incremented
- After 3 failures → status set to `REFRESH_FAILED`
- Tokens in `REFRESH_FAILED` state are not retried by the scheduler

Metrics emitted:
- `pipeline.token.refresh.success` (counter)
- `pipeline.token.refresh.failed` (counter)

---

## InstagramOAuthController Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/oauth/instagram/authorize` | Generate and return the Meta OAuth authorization URL |
| `GET` | `/api/v1/oauth/instagram/callback` | Handle Meta callback, complete OAuth flow |

Both endpoints are open (`permitAll()` in SecurityConfig).
