# AxionAuto

Phase 1 Instagram automation SaaS for:
- Instagram account connection via Meta OAuth
- Rule-based DM automation
- Inbox and thread messaging
- Tenant-scoped auth and session handling

## Stack

- Backend: Spring Boot 3, Java 21, PostgreSQL, Redis
- Frontend: React + Vite
- Infra: Docker Compose for local Postgres and Redis

## Prerequisites

- Java 21
- Docker Desktop
- PowerShell
- Node.js and npm

## Local Project Root

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2
```

## 1. Start Infrastructure

This project uses PostgreSQL and Redis locally.

```powershell
docker compose up -d
```

Check running containers:

```powershell
docker ps
```

You should see:
- `axionauto2-postgres`
- `axionauto2-redis`

## 2. Start Backend

The backend launcher loads `.env`, configures Java/Maven, and starts Spring Boot.

```powershell
.\start-backend.ps1
```

Backend URL:

```text
http://localhost:8080
```

Health check:

```text
http://localhost:8080/actuator/health
```

## 3. Start Frontend

Open a second terminal:

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2\frontend
npm install
npm run dev
```

Frontend URL:

```text
http://localhost:5173
```

## 4. Demo Login

Once backend and frontend are running, log in with:

```text
demo@axion.io
demo123
```

Admin login:

```text
admin@axion.io
admin123
```

## Environment Notes

Local backend config is loaded from `.env`.

Important keys:
- `META_APP_ID`
- `META_APP_SECRET`
- `META_OAUTH_REDIRECT_URI`
- `TOKEN_ENCRYPTION_KEY`
- `AUTH_TOKEN_SECRET`
- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`

The local default OAuth redirect URI should point to the backend callback:

```text
http://localhost:8080/api/v1/oauth/instagram/callback
```

## Common Issues

### PostgreSQL connection refused

If backend boot fails with:

```text
Connection to localhost:5432 refused
```

then Postgres is not running. Start Docker Desktop and run:

```powershell
docker compose up -d
```

### Docker container name conflict

If Docker reports an existing container name, start the existing container or recreate just that service:

```powershell
docker start axionauto2-postgres
docker start axionauto2-redis
```

If needed:

```powershell
docker rm -f axionauto2-postgres
docker rm -f axionauto2-redis
docker compose up -d
```

### Maven not found

If `mvn` is not recognized, use the backend startup helper instead:

```powershell
.\start-backend.ps1
```

### OAuth not working

Make sure:
- your Meta app credentials in `.env` are correct
- the redirect URI configured in Meta matches `META_OAUTH_REDIRECT_URI`
- the frontend is running on `http://localhost:5173`

## Verification

Frontend production build:

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2\frontend
npm run build
```

Backend tests:

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2
mvn -s .mvn-local-settings.xml test
```

Frontend dependency audit:

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2\frontend
npm audit --audit-level=moderate
```

## Docker Builds

Backend image:

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2
docker build -t axionauto-backend .
```

Frontend image:

```powershell
cd C:\Users\anvit\OneDrive\Desktop\axionauto2\frontend
docker build -t axionauto-frontend .
```

## Deployment

Before moving from local Docker to real hosting, fill in the environment contract:

```text
docs/20_deploy_env_contract.md
```

The backend requires real `TOKEN_ENCRYPTION_KEY` and `AUTH_TOKEN_SECRET` values. It will not use unsafe defaults for deploy.

## Phase 1 Scope

This repo is set up for a Phase 1 launch with:
- authentication and session tokens
- Instagram connect/disconnect
- rules CRUD
- inbox threads and messages
- outbound manual send
- inbound and outbound message persistence
- OAuth callback completion flow

## Security Reminder

Do not commit `.env`.

If any Meta app secret or access token was pasted into chat, logs, or screenshots, rotate it in the Meta developer dashboard and update your local `.env`.
