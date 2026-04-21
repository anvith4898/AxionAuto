# API Contracts

## Base URL

```
http://localhost:8080   (development)
https://api.yourdomain.com (production)
```

All endpoints return `application/json` unless specified otherwise.

---

## OAuth Endpoints

### `GET /api/v1/oauth/instagram/authorize`

Generate an Instagram OAuth authorization URL.

**Request Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `tenantId` | UUID (query) | Yes | Tenant making the connection |
| `userId` | String (query) | Yes | Platform user ID initiating the flow |
| `redirectAfterCallback` | String (query) | No | Frontend URL to redirect after completion |

**Response:** `200 OK`
```json
{
  "authorizationUrl": "https://www.facebook.com/dialog/oauth?client_id=..."
}
```

---

### `GET /api/v1/oauth/instagram/callback`

Handle OAuth callback from Meta. Completes the token exchange and stores the encrypted token.

**Request Parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `code` | String (query) | Yes | Authorization code from Meta |
| `state` | String (query) | Yes | CSRF state token echoed by Meta |

**Response:** `200 OK`
```json
{
  "tokenId": "uuid",
  "tenantId": "uuid",
  "userId": "string",
  "instagramAccountId": "17841400000000001",
  "instagramUsername": "mybizhandle",
  "tokenExpiry": "2026-06-19T02:00:00Z",
  "status": "ACTIVE",
  "isNew": true
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|---|---|---|
| `400` | `INVALID_STATE` | State token expired or CSRF detected |
| `400` | `CODE_EXCHANGE_FAILED` | Meta rejected the authorization code |
| `400` | `TOKEN_EXTENSION_FAILED` | Failed to obtain long-lived token |

---

## Webhook Endpoints

### `GET /api/v1/webhooks/instagram`

Meta webhook verification (hub challenge).

**Request Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `hub.mode` | String | Must be `"subscribe"` |
| `hub.verify_token` | String | Must match `META_WEBHOOK_VERIFY_TOKEN` env var |
| `hub.challenge` | String | Random string to echo back |

**Response:** `200 OK` — body is the raw `hub.challenge` string.
**Response:** `403 Forbidden` — invalid verify token.

---

### `POST /api/v1/webhooks/instagram`

Receive Instagram webhook events from Meta.

**Request Headers:**

| Header | Description |
|---|---|
| `X-Hub-Signature-256` | `sha256={HMAC-SHA256 of raw body}` — **required** |
| `Content-Type` | `application/json` |

**Request Body:** Raw Instagram webhook JSON payload (see `05_webhook.md` for shape).

**Response Codes:**

| Code | Body | Meaning |
|---|---|---|
| `200` | `{ "status": "EVENT_RECEIVED" }` | Event accepted and queued |
| `200` | `{ "status": "DUPLICATE", "eventId": "..." }` | Duplicate event; already processed |
| `403` | `{ "status": "REJECTED" }` | Invalid HMAC signature |
| `500` | `{ "status": "QUEUE_ERROR" }` | Redis unavailable; Meta should retry |

---

### `GET /api/v1/webhooks/instagram/stats`

Pipeline metric counters.

**Response:** `200 OK`
```json
{
  "eventsIngested":    142,
  "eventsEnqueued":    139,
  "eventsRejected":      3,
  "eventsDuplicate":     4,
  "messagesProcessed": 118,
  "messagesFailed":      6,
  "inqueueFailed":       0,
  "startTime": "2026-04-19T00:00:00Z"
}
```

---

### `GET /api/v1/webhooks/instagram/dlq`

Inspect DLQ entries without consuming them.

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `limit` | int | 20 | Max entries to return (capped at 100) |

**Response:** `200 OK`
```json
[
  {
    "streamRecordId": "1713000000000-0",
    "eventId": "mid.pricing.001",
    "errorCode": "NO_TENANT_TOKEN",
    "errorMessage": "No active token for igAccountId=17841400"
  }
]
```

---

### `POST /api/v1/webhooks/instagram/dlq/replay`

Replay DLQ entries back to the main processing stream.

**Query Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `limit` | int | 10 | Max entries to replay (capped at 50) |

**Response:** `200 OK`
```json
{
  "replayed": 8,
  "failed": 2,
  "note": null
}
```

---

## (Internal) Pipeline EventPipelineIntegrationService

The following result types are returned internally between layers (not exposed via HTTP directly, but influence HTTP responses):

### `IngestResult` (enum)

| Value | HTTP Mapping | Meaning |
|---|---|---|
| `ACCEPTED` | 200 (`EVENT_RECEIVED`) | Event queued successfully |
| `DUPLICATE` | 200 (`DUPLICATE`) | Already processed |
| `REJECTED` | 403 | Invalid signature |
| `QUEUE_FAILED` | 500 | Redis write failed |
| `PARSE_ERROR` | 400 | Could not extract eventId from payload |

### `PipelineResult` (record)

Returned from `EventPipelineIntegrationService.process()` for already-ingested events:

```java
public record PipelineResult(
    boolean success,
    String  eventId,
    int     messagesProcessed,
    int     rulesFired,
    String  failureReason   // null if success
) {}
```

---

## Common Error Response Format

All error responses follow this shape:

```json
{
  "timestamp": "2026-04-19T05:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "The OAuth session is invalid or has expired.",
  "path": "/api/v1/oauth/instagram/callback"
}
```

---

## Headers Reference

| Header | Direction | Description |
|---|---|---|
| `X-Hub-Signature-256` | Inbound (Meta → AxionAuto) | HMAC-SHA256 webhook signature |
| `Content-Type: application/json` | Both | All REST endpoints |
| `Authorization: Bearer {token}` | Future (client → AxionAuto) | Protected API routes |

---

## Rate Limits (API-Level)

| Endpoint | Limit | Enforced By |
|---|---|---|
| `POST /webhooks/instagram` | Unlimited (Meta controls delivery rate) | Meta platform |
| Graph API send (`POST /{igAccountId}/messages`) | 200 calls/hour per access token | `GraphApiRateLimiter` (Redis sorted set) |

---

## Meta Graph API Calls Made by AxionAuto

| Call | Method + Path | Auth | Purpose |
|---|---|---|---|
| Token exchange | `POST /oauth/access_token` | App credentials | Short-lived token |
| Token extension | `GET /oauth/access_token?grant_type=fb_exchange_token` | App credentials | Long-lived token |
| Account fetch | `GET /me?fields=instagram_business_account` | User access token | Get IG Business Account ID |
| Token refresh | `GET /oauth/access_token?grant_type=fb_exchange_token` | App credentials + current token | 60-day refresh |
| Send DM | `POST /{ig_account_id}/messages` | User access token | Reply to inbound DM |

All Graph API calls use version `v20.0` (configurable via `META_GRAPH_API_VERSION`).
