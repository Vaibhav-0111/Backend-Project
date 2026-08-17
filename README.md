# Reliable Webhook Delivery Service

A production-grade, multi-tenant webhook delivery system built with Java 21, Spring Boot 4, and PostgreSQL 16.

---

## Quick Start (under 5 minutes from a clean clone)

```bash
git clone https://github.com/Vaibhav-0111/Backend-Project.git
cd Backend-Project

# Copy the env template (defaults work out of the box for local dev)
cp .env.example .env

# Start Postgres + the app together
docker compose up --build
```

The API is available at `http://localhost:8080`.
Flyway migrations run automatically at startup — no manual SQL steps needed.

> **Local dev without Docker**: Start Postgres separately, then `./mvnw spring-boot:run`. Requires JDK 21.

---

## Architecture

### The One-Sentence Thesis

This service is a **leased job queue with signed HTTP fan-out**, not "a loop that calls webhooks." Everything downstream — schema, locking, decorrelated jitter backoff, circuit breaker — exists to make that job queue correct under concurrency and crash.

### Ingestion Path

```
POST /api/v1/events
        │
        ▼
  [TenantFilter] — reads X-Tenant-ID header → sets ThreadLocal context
        │
        ▼
  [EventService] ─── @Transactional ───────────────────────────────────┐
        │                                                               │
        ├── Idempotency check: SELECT where (tenant_id, event_id_ext)  │
        │   If exists → return 202 with existing internal ID           │
        │                                                               │
        ├── INSERT INTO events                                          │
        │                                                               │
        └── For each ACTIVE endpoint subscribed to the event type:      │
              INSERT INTO deliveries (status=PENDING, next_attempt_at=now()) ◄─┘
        │
        ▼
  202 Accepted  ← returns immediately, delivery is async
```

**Why transactional fan-out?** If the app crashes after saving the event but before saving deliveries, nothing is lost. The unique constraint on `(tenant_id, event_id_external)` prevents double-fan-out if the producer retries.

### Delivery Path

```
[DeliveryWorker] — @Scheduled every 1s
        │
        ▼
  [DeliveryClaimer] — single UPDATE...RETURNING statement:
        UPDATE deliveries
        SET locked_by = ?, locked_until = now() + 30s, status = 'IN_PROGRESS'
        WHERE id IN (
          SELECT id FROM deliveries d
          JOIN endpoints e ON d.endpoint_id = e.id
          WHERE d.status = 'PENDING'
            AND d.next_attempt_at <= now()
            AND (d.locked_until IS NULL OR d.locked_until < now())
            AND (e.circuit_state != 'OPEN' OR e.cooldown_until <= now())
          ORDER BY d.next_attempt_at
          FOR UPDATE SKIP LOCKED
          LIMIT 50
        ) RETURNING *
        │
        ▼ (batch of up to 50 deliveries, submitted to thread pool)
  [DeliveryService.executeDelivery()]
        │
        ├── Load event payload + endpoint secret from DB
        │
        ├── [HttpDispatcher.dispatch()]
        │     ├── Generate HMAC-SHA256 signature: sha256=HMAC(timestamp + "." + body)
        │     ├── POST with X-Webhook-Signature + X-Webhook-Timestamp headers
        │     ├── connectTimeout = 5s, readTimeout = 10s
        │     └── Returns DispatchResult(statusCode, body, latencyMs, error)
        │
        ├── INSERT INTO delivery_attempts (response_code, latency_ms, error)
        │
        ├── On 2xx → status = 'DELIVERED', release lock, reset circuit breaker
        │
        └── On failure →
              If attempt_count >= 8 → status = 'DEAD_LETTERED'
              Else → decorrelated jitter backoff, status = 'PENDING'
                     + increment circuit breaker failure counter
```

---

## Locking / Claiming Strategy

**Why lease-based, not delete-based:**

The delivery worker issues a single `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING *`. This is atomic at the database level.

- `FOR UPDATE SKIP LOCKED` means concurrent workers get *disjoint* sets of rows — no double-delivery under any concurrency.
- The `locked_until` column is a crash-recovery lease. If a worker JVM is killed mid-dispatch, the 30-second lease expires and another worker claims the row automatically. No separate "recovery job" needed.
- Deliveries are never deleted from the queue — they transition through `PENDING → IN_PROGRESS → DELIVERED | DEAD_LETTERED | PENDING (retry)`.

**The claim query skips `OPEN` circuit breaker endpoints** — so a broken downstream system never wastes row-lock budget on deliveries that are going to fail anyway.

---

## Backoff Formula & Retry Limits

This service uses **decorrelated jitter** (AWS-style), not standard exponential backoff.

```
sleep = min(cap, random_between(base, prev_sleep × 3))
```

- `base` = 30 seconds
- `cap` = 4 hours (14 400 seconds)
- `max_attempts` = 8

**Why decorrelated jitter instead of `base × 2^attempt`?**

Standard exponential backoff synchronises retries. If 1 000 deliveries to the same endpoint all failed at `t=0`, they all retry at roughly the same time window, creating a thundering herd when the endpoint recovers. Decorrelated jitter spreads them pseudo-randomly across the retry window.

Approximate retry schedule (deterministic midpoint):

| Attempt | Approx delay |
|---------|-------------|
| 1 | ~45s |
| 2 | ~2m |
| 3 | ~8m |
| 4 | ~30m |
| 5 | ~1.5h |
| 6–8 | capped at 4h |

After 8 attempts, the delivery is marked `DEAD_LETTERED`. Manual redrive resets `attempt_count = 0` and `status = PENDING`.

---

## At-Least-Once Guarantee

**How we guarantee it:**

1. The `Event` + all `Delivery` rows are saved in one transaction. No partial fan-out possible.
2. The lease timeout (30s) ensures a delivery orphaned by a worker crash is re-claimed.
3. `PENDING` deliveries are never removed from the DB until they reach a terminal state.

**Where duplicates could theoretically still occur:**

- A worker dispatches successfully and gets a `200 OK` from the endpoint, but the JVM is killed before it can write `status = DELIVERED` to the database. The lease expires, another worker re-claims and re-dispatches. This is the fundamental at-least-once trade-off. The receiver should be idempotent using `X-Webhook-Timestamp` + the internal event ID.

---

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/endpoints` | Register a webhook endpoint (FR-1) |
| `GET` | `/api/v1/endpoints` | List all endpoints for tenant |
| `GET` | `/api/v1/endpoints/{id}` | Get endpoint details |
| `DELETE` | `/api/v1/endpoints/{id}` | Soft-disable endpoint |
| `POST` | `/api/v1/endpoints/{id}/test` | Send a synthetic test event (FR-6) |
| `POST` | `/api/v1/events` | Ingest an event (FR-2) |
| `GET` | `/api/v1/events/{id}/deliveries` | All deliveries for an event (FR-4) |
| `GET` | `/api/v1/endpoints/{id}/deliveries` | All deliveries for an endpoint (FR-4) |
| `GET` | `/api/v1/deliveries/{id}/attempts` | Attempt log for a delivery (FR-4) |
| `POST` | `/api/v1/deliveries/{id}/redrive` | Re-queue a dead-lettered delivery (FR-3) |
| `GET` | `/actuator/health` | Health check including DB (FR-8) |

**All requests require:** `X-Tenant-ID: <your-tenant-id>` header.

### Example Walkthrough

```bash
# 1. Register an endpoint
curl -s -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-ID: tenant-a" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://httpbin.org/post","subscribedEventTypes":["invoice.paid"]}'

# Returns: {"id":"...","secret":"..."}  ← save the secret

# 2. Send an event
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "X-Tenant-ID: tenant-a" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"inv-001","type":"invoice.paid","payload":{"amount":5000}}'

# Returns: {"internalEventId":"..."}

# 3. Check delivery status
curl -s http://localhost:8080/api/v1/events/<internalEventId>/deliveries \
  -H "X-Tenant-ID: tenant-a"

# 4. Test cross-tenant isolation (must return 404, not Tenant A's data)
curl -s http://localhost:8080/api/v1/endpoints/<endpoint-id-from-step-1>/deliveries \
  -H "X-Tenant-ID: tenant-b"
```

---

## Multi-Tenancy

Every request is scoped by `X-Tenant-ID`. A `TenantFilter` populates a `ThreadLocal` context that is cleared in a `finally` block to prevent leaks between requests.

Every repository method includes `tenant_id` in the `WHERE` clause — enforced by the `TenantAwareRepository` base interface. There is no "get all" query without a tenant scope. Tenant isolation is also tested by `TenantIsolationTest` using a real Postgres instance.

---

## Testing

```bash
# Unit tests only (fast, no Docker)
./mvnw test -Dtest="BackoffCalculatorTest,HttpDispatcherTest"

# Integration tests (requires Docker for Testcontainers)
./mvnw test -Dtest="DeliveryConcurrencyTest,TenantIsolationTest"

# Full suite
./mvnw test
```

**Test coverage:**

| Test | What it verifies |
|------|-----------------|
| `BackoffCalculatorTest` | Jitter bounds, monotonic growth, cap, max attempts boundary |
| `HttpDispatcherTest` | 200 success + header assertions, 500 handling, timeout enforcement |
| `DeliveryConcurrencyTest` | 5 concurrent workers vs 100 deliveries — zero double-claims |
| `TenantIsolationTest` | Cross-tenant access blocked, duplicate eventId doesn't create double delivery |

---

## Known Limitations

1. **No per-tenant retry policy yet** — max attempts and backoff are global constants (`BackoffCalculator`). Production fix: add a `retry_policy` column to the `tenants` table and inject it at claim time.
2. **`DeliveryWorker` uses `newCachedThreadPool()`** — in production on JDK 21 this should be `Executors.newVirtualThreadPerTaskExecutor()` to avoid platform thread exhaustion under high concurrency.
3. **Circuit breaker is endpoint-level only** — a more sophisticated implementation would also track per-status-code failure rates separately.
4. **No correlation IDs flowing end-to-end yet** — MDC-based correlation ID propagation via a request filter is the next step for structured log tracing.
5. **SSRF uses JVM DNS resolution** — in production, routing outbound webhooks through a proxy like Smokescreen provides a stronger network-level egress control.

---

## One Thing That Surprised Me

PostgreSQL's `FOR UPDATE SKIP LOCKED` makes the entire "distributed queue" problem almost trivial — the database engine handles the coordination that would otherwise require a separate broker (Redis, Kafka, SQS). The surprising part was that the lock is held only *during the UPDATE statement itself*, not for the duration of the HTTP call. That's why the lease timeout column (`locked_until`) is essential: it's the actual concurrency boundary, not the row lock.
