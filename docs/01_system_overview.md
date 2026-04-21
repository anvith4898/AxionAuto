# AxionAuto — System Overview

## What Is AxionAuto?

AxionAuto is a multi-tenant **Instagram Automation SaaS** backend that allows businesses to automatically respond to Instagram Direct Messages based on configurable rules. When a customer sends a DM to a connected Instagram Business Account, AxionAuto receives the message via Meta's Webhook API, normalizes it, evaluates automation rules (keyword triggers, welcome messages, fallback responses), and dispatches a reply — all within a few hundred milliseconds.

---

## Core Value Proposition

| Capability | Description |
|---|---|
| **Zero-code automation** | Tenants configure keyword-triggered reply rules through an API/UI — no code required |
| **Multi-tenant** | A single deployment serves many independent businesses. Data is isolated by `tenant_id` at every layer |
| **Reliable delivery** | Events flow through Redis Streams with consumer groups ensuring at-least-once processing |
| **Production-safe** | HMAC validation, idempotency guards, rate limiting, circuit breakers, and DLQ recovery |
| **Observable** | MDC-traced structured logging, Micrometer metrics, Spring Actuator health endpoints |

---

## High-Level Architecture

```
    ┌─────────────────────────────────────────────────────────────────┐
    │                         Meta Platform                           │
    │           Instagram Business Account (tenant's page)           │
    └───────────────────────────┬─────────────────────────────────────┘
                                │  Webhook POST / OAuth callback
                                ▼
    ┌─────────────────────────────────────────────────────────────────┐
    │                     AxionAuto Backend                           │
    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
    │  │  OAuth Flow  │  │   Webhook    │  │   REST API (future)  │  │
    │  │ (connect IG) │  │  Ingestion   │  │   rules/contacts     │  │
    │  └──────┬───────┘  └──────┬───────┘  └──────────────────────┘  │
    │         │                 │                                      │
    │         ▼                 ▼                                      │
    │  ┌──────────────┐  ┌────────────────────────────────────────┐   │
    │  │  PostgreSQL  │  │          Redis Streams                 │   │
    │  │  (tokens,    │  │  instagram-webhooks (main)             │   │
    │  │   rules,     │  │  instagram-webhooks-dlq (dead letter)  │   │
    │  │   contacts,  │  └──────────────────┬─────────────────────┘   │
    │  │   messages)  │                     │                          │
    │  └──────────────┘  ┌──────────────────▼─────────────────────┐   │
    │                    │       Event Processing Pipeline         │   │
    │                    │  Consume → Normalize → Orchestrate      │   │
    │                    │  → AutomationEngine → Dispatcher        │   │
    │                    └──────────────────┬─────────────────────┘   │
    │                                       │                          │
    │                                       ▼                          │
    │                              Meta Graph API                      │
    │                         (send reply DM to user)                  │
    └─────────────────────────────────────────────────────────────────┘
                                            │
    ┌───────────────────────────────────────▼─────────────────────────┐
    │                       React Frontend                            │
    │        Dashboard · Inbox · Rules Manager · Connect Page         │
    └─────────────────────────────────────────────────────────────────┘
```

---

## Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Backend Runtime** | Java 21, Spring Boot 3.x | Application server with virtual threads |
| **Web Framework** | Spring MVC | REST controllers, filter chain |
| **ORM** | Spring Data JPA / Hibernate 6 | PostgreSQL entity mapping |
| **Database** | PostgreSQL 15+ | Persistent store; Row-Level Security for tenancy |
| **Migrations** | Flyway | Versioned schema changes (V1–V4) |
| **Cache / Queue** | Redis 7+ (Lettuce client) | OAuth state, idempotency lock, event stream, rate limiting |
| **Event Streaming** | Redis Streams + Consumer Groups | At-least-once webhook event delivery |
| **Resilience** | Resilience4j | Retry (×4, exponential backoff) + circuit breaker |
| **Security** | Spring Security, AES-256-GCM | Auth, token encryption |
| **Observability** | Micrometer + Prometheus | Metrics per pipeline stage |
| **Scheduling** | Spring `@Scheduled` | Token refresh job, DLQ depth gauge |
| **Frontend** | React + Vite | SPA dashboard |
| **HTTP Client** | Spring `RestClient` | Meta Graph API calls |

---

## Key Design Principles

1. **Loose coupling via event-driven design** — the HTTP ingest path completes in < 50ms and releases the thread. All processing (normalize → decide → send) happens asynchronously through Redis Streams.

2. **Multi-tenancy at every layer** — `tenant_id` is present on every DB table and enforced by PostgreSQL Row-Level Security. Application code never queries across tenant boundaries.

3. **Idempotency by default** — duplicate webhook deliveries are detected by a two-layer guard (Redis SETNX + DB unique constraint on `event_id`) before any processing occurs.

4. **Fail safely** — unrecoverable failures are parked to the `instagram-webhooks-dlq` Dead Letter Queue stream. Operators can inspect, replay, or purge via REST endpoints.

5. **Security-first token management** — Meta access tokens are never stored in plaintext. AES-256-GCM encryption with per-value IV and authentication tag is applied before any DB write.

---

## Bounded Contexts / Modules

| Package | Role |
|---|---|
| `controller` | HTTP adapters — zero business logic |
| `service` | Core domain services (OAuth, encryption, rate limiting, token refresh) |
| `integration` | Event pipeline — stream consumer, orchestrator, DLQ, logging context |
| `normalization` | Parsing and validation of raw webhook payloads into `MessageDTO` |
| `automation` | Rule evaluation engine, in-memory rule cache, reply dispatcher |
| `domain` | JPA entities, DTOs, enums, repository interfaces |
| `config` | Spring configuration beans and `@ConfigurationProperties` |
| `repository` | Spring Data JPA interface extensions |
| `exception` | Typed exception hierarchy |
