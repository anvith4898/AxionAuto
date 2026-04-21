# Deployment

## Overview

AxionAuto is a Spring Boot 3.x application with a React frontend. The following documents the infrastructure requirements, environment variables, and deployment approach.

---

## Infrastructure Requirements

| Component | Minimum | Recommended | Notes |
|---|---|---|---|
| **Java Runtime** | Java 21 | Java 21 LTS | Virtual threads require Java 21 |
| **PostgreSQL** | 14+ | 15+ | For `gen_random_uuid()`, `JSONB`, RLS support |
| **Redis** | 6.x | 7.x | Streams + Consumer Groups |
| **Memory (backend)** | 512 MB | 1–2 GB | RuleCache grows with account count |
| **CPU** | 1 vCPU | 2 vCPU | Virtual threads handle concurrency efficiently |
| **HTTPS** | Required | Required | TLS termination at load balancer |

---

## Environment Variables

All configuration is externalized via environment variables. The application will **fail to start** if required variables are missing.

### Required (no defaults)

| Variable | Description |
|---|---|
| `META_APP_ID` | Meta (Facebook) App ID from the Meta Developer Portal |
| `META_APP_SECRET` | Meta App Secret — used as HMAC-SHA256 key for webhook validation |
| `META_OAUTH_REDIRECT_URI` | OAuth callback URL registered in the Meta App dashboard (e.g., `https://yourdomain.com/api/v1/oauth/instagram/callback`) |
| `TOKEN_ENCRYPTION_KEY` | Base64-encoded 32-byte AES key for access token encryption at rest |
| `DB_USERNAME` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |

### Optional (have defaults)

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `axion_db` | Database name |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password (blank = no auth) |
| `META_WEBHOOK_VERIFY_TOKEN` | `axion_default_verify_token_123` | Must be set in Meta App dashboard to match |
| `META_GRAPH_API_BASE_URL` | `https://graph.facebook.com` | Override for testing |
| `META_GRAPH_API_VERSION` | `v20.0` | Graph API version |
| `OAUTH_STATE_TTL_SECONDS` | `600` | OAuth state expiry (10 minutes) |
| `AUTOMATION_COOLDOWN_SECONDS` | `3600` | Default rule cooldown |
| `AUTOMATION_MAX_RULES` | `10` | Max rules per message in RUN_ALL mode |
| `AUTOMATION_EAGER_WARMUP` | `false` | Set `true` in production for predictable p99 latency |

### Generating `TOKEN_ENCRYPTION_KEY`

```bash
# Generate a 32-byte random key and Base64 encode it:
openssl rand -base64 32
# Example output: K3mP9xvQnR2tY5wLhA1jE7cB4dF6gH0i=
```

---

## Database Setup

### 1. Create Database and User

```sql
CREATE USER axion_user WITH PASSWORD 'axion_password';
CREATE DATABASE axion_db OWNER axion_user;
GRANT ALL PRIVILEGES ON DATABASE axion_db TO axion_user;
```

### 2. Enable pgcrypto Extension

```sql
\c axion_db
CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- for gen_random_uuid()
```

### 3. Run Flyway Migrations

Flyway runs automatically on application startup:
```
src/main/resources/db/migration/
├── V1__create_instagram_oauth_tokens.sql     ← Token table + RLS + indexes
├── V2__create_webhook_events_table.sql       ← Webhook events table
├── V3__create_automation_engine_tables.sql   ← Rules, keywords, contacts, execution log
└── V4__production_schema.sql                 ← Users, messages, accounts tables + retrofit
```

**Before V4 deploys to production**, run the tenant backfill manually:
```sql
-- V4 requires existing rows to have tenant_id; the migration uses a sentinel UUID
-- Review and assign real tenant UUIDs before enabling RLS on webhook_events
UPDATE instagram_webhook_events
    SET tenant_id = '00000000-0000-0000-0000-000000000000'
    WHERE tenant_id IS NULL;
```

### 4. Recommended Production Index (not in migration)

```sql
-- Needed for O(1) tenant resolution in WebhookStreamConsumer
CREATE INDEX IF NOT EXISTS idx_ig_tokens_ig_account_status
    ON instagram_oauth_tokens (instagram_account_id, status);
```

---

## Redis Setup

### Consumer Group Initialization

The `RedisStreamConfig` bean creates the consumer group on startup if it doesn't exist:

```
XGROUP CREATE instagram-webhooks axion-processors $ MKSTREAM
```

`MKSTREAM` ensures the stream is created if it doesn't exist. `$` means start consuming from new events (not from the beginning of the stream).

### Redis Connection (No Auth)

```yaml
spring.data.redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:6379}
  password: ${REDIS_PASSWORD:}
```

For production, use Redis with `requirepass` and set `REDIS_PASSWORD`. Consider Redis Sentinel or Cluster for high availability.

---

## Building

### Backend

```bash
# Build the JAR
./mvnw clean package -DskipTests

# Run locally
./mvnw spring-boot:run

# Or run the packaged JAR
java -jar target/axion-auth-*.jar
```

### Frontend

```bash
cd frontend

# Install dependencies
npm install

# Development server (Vite, HMR)
npm run dev

# Production build
npm run build

# Output: frontend/dist/
```

---

## Running Locally (Development)

```bash
# 1. Start dependencies
docker compose up -d postgres redis

# 2. Set environment (example — use .env file or shell exports)
export META_APP_ID=your_app_id
export META_APP_SECRET=your_app_secret
export META_OAUTH_REDIRECT_URI=http://localhost:8080/api/v1/oauth/instagram/callback
export TOKEN_ENCRYPTION_KEY=$(openssl rand -base64 32)
export META_WEBHOOK_VERIFY_TOKEN=my_local_verify_token

# 3. Run backend
./mvnw spring-boot:run

# 4. Run frontend (separate terminal)
cd frontend && npm run dev
```

### Docker Compose (Development)

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: axion_db
      POSTGRES_USER: axion_user
      POSTGRES_PASSWORD: axion_password
    ports:
      - "5432:5432"
    volumes:
      - pg_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  pg_data:
```

---

## Production Deployment Checklist

### Infrastructure
- [ ] Java 21 JRE on the application server
- [ ] PostgreSQL 15+ with pgcrypto extension enabled
- [ ] Redis 7+ (Sentinel or Cluster for HA)
- [ ] TLS certificate and HTTPS on public endpoints
- [ ] Load balancer configured to forward `X-Hub-Signature-256` headers (don't strip headers)

### Configuration
- [ ] All required environment variables set
- [ ] `TOKEN_ENCRYPTION_KEY` stored in Secrets Manager (not in `.env` file or source control)
- [ ] `META_WEBHOOK_VERIFY_TOKEN` matches what's registered in the Meta App dashboard
- [ ] `META_OAUTH_REDIRECT_URI` is the HTTPS production domain
- [ ] `AUTOMATION_EAGER_WARMUP=true` for predictable rule evaluation latency

### Database
- [ ] Flyway migrations applied and verified (`SELECT * FROM flyway_schema_history`)
- [ ] Tenant backfill run for V4 migration
- [ ] Production indexes created (see Database Setup section)
- [ ] Connection pool size tuned (`maximum-pool-size: 20` minimum)

### Monitoring
- [ ] Prometheus scraping `GET /actuator/prometheus`
- [ ] Grafana dashboards configured for pipeline metrics
- [ ] Alerts set for DLQ depth, token refresh failures, circuit breaker state
- [ ] Log aggregation (Loki, ELK, CloudWatch) receiving structured JSON logs

### Meta App Configuration
- [ ] Webhook subscription verified in Meta App dashboard
- [ ] `instagram_manage_messages` permission approved (requires Meta App Review for production)
- [ ] Webhook URL registered: `https://yourdomain.com/api/v1/webhooks/instagram`

---

## Horizontal Scaling

The backend is designed to scale horizontally:

1. **Stateless HTTP layer**: All state is in PostgreSQL or Redis. Any number of pods can handle webhook ingestion.

2. **Consumer group scaling**: Add more replicas, each with a unique consumer name:
   ```yaml
   # Each pod: axion-consumer-{pod-id}
   AXION_CONSUMER_NAME=axion-consumer-${POD_NAME}
   ```
   Redis distributes stream records across consumers automatically.

3. **RuleCache per-replica**: Each replica has its own in-memory `RuleCache`. On a rule update, all replicas must invalidate their cache. Currently this requires a rolling restart or a Redis pub/sub invalidation signal (future enhancement).

4. **Rate limiter shared via Redis**: `GraphApiRateLimiter` uses Redis sorted sets — shares state across replicas correctly.

---

## Meta App Review

Before going live with real business accounts, the Meta App must undergo review for the `instagram_manage_messages` permission:

1. Complete the Meta App Review process
2. Provide a screen recording of the OAuth flow and DM automation working
3. Agree to Meta's Platform Terms and Messaging Products Terms
4. Set the app from "Development" to "Live" mode

Until review is complete, only test users explicitly added to the Meta App can authorize and use the system.
