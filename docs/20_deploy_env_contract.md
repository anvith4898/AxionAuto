# Deploy Environment Contract

Use this checklist when replacing local Docker services with real hosting, Redis, and PostgreSQL.

## Backend Runtime

Required:
- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `META_APP_ID`
- `META_APP_SECRET`
- `META_OAUTH_REDIRECT_URI`
- `META_WEBHOOK_VERIFY_TOKEN`
- `TOKEN_ENCRYPTION_KEY`
- `AUTH_TOKEN_SECRET`
- `CORS_ALLOWED_ORIGIN_1`

Optional:
- `REDIS_PASSWORD`
- `CORS_ALLOWED_ORIGIN_2`
- `AUTH_SESSION_TTL_SECONDS`
- `OAUTH_STATE_TTL_SECONDS`
- `META_GRAPH_API_BASE_URL`
- `META_GRAPH_API_VERSION`
- `AUTOMATION_COOLDOWN_SECONDS`
- `AUTOMATION_MAX_RULES`
- `AUTOMATION_EAGER_WARMUP`

## Required Secret Formats

`TOKEN_ENCRYPTION_KEY` must be 32 random bytes encoded as base64.

Generate with:

```bash
openssl rand -base64 32
```

`AUTH_TOKEN_SECRET` should be a long random string.

Generate with:

```bash
openssl rand -base64 48
```

## URLs To Configure

Backend public URL:

```text
https://api.your-domain.com
```

Frontend public URL:

```text
https://app.your-domain.com
```

Meta OAuth redirect URI:

```text
https://api.your-domain.com/api/v1/oauth/instagram/callback
```

CORS:

```text
CORS_ALLOWED_ORIGIN_1=https://app.your-domain.com
```

Frontend API base URL:

```text
VITE_API_BASE_URL=https://api.your-domain.com
```

## Deployment Checks

- PostgreSQL accepts connections from the backend host.
- Redis accepts connections from the backend host.
- Database migrations run successfully on boot.
- `GET /actuator/health` returns healthy.
- Meta app redirect URI exactly matches `META_OAUTH_REDIRECT_URI`.
- Frontend login can reach `POST /api/v1/auth/login`.
- Instagram OAuth popup returns to the frontend callback page.
- Webhook callback is reachable from Meta.
- `.env` and real secrets are not committed.
