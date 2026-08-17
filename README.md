# Webhook Delivery Service

A reliable, multi-tenant webhook delivery service built with Java 21, Spring Boot 3, and PostgreSQL.

## 🚀 The Core Philosophy

This service is fundamentally a **leased job queue with signed HTTP fan-out**.

Rather than relying on in-memory message brokers (which lose state on crash) or complex clustered queues, the delivery mechanism relies on PostgreSQL's `FOR UPDATE SKIP LOCKED`.
Everything downstream — schema, locking, decorrelated jitter backoff, circuit breaking — exists to make this job queue correct under concurrency and resilient to failure.

### Architecture Highlights

1. **Lease-based Claiming Strategy**: Deliveries are *leased*, not deleted. Workers run a continuous loop executing:
   ```sql
   UPDATE deliveries
   SET locked_by = ?, locked_until = now() + interval '30 seconds', status = 'IN_PROGRESS'
   WHERE id IN (
     SELECT d.id FROM deliveries d
     JOIN endpoints e ON d.endpoint_id = e.id
     WHERE d.status = 'PENDING'
       AND d.next_attempt_at <= now()
       AND (d.locked_until IS NULL OR d.locked_until < now())
       AND (e.circuit_state != 'OPEN' OR e.cooldown_until <= now())
     ORDER BY d.next_attempt_at
     FOR UPDATE SKIP LOCKED
     LIMIT 50
   )
   RETURNING *
   ```
   This ensures that multiple concurrent workers never process the same delivery. If a worker crashes before finishing the HTTP request, the `locked_until` expires, and another worker picks it up.

2. **Transactional Fan-out**: When a multi-tenant event is ingested (`POST /api/v1/events`), a single database transaction inserts the `Event` and multiple `Delivery` rows (one for each subscribed endpoint). This guarantees that we never lose a delivery if the application crashes immediately after saving the event. Idempotency is enforced at the database level using a unique constraint on `(tenant_id, event_id_external)`.

3. **Strict Multi-Tenancy**: Data isolation is enforced via a `TenantFilter` that extracts the `X-Tenant-ID` header and stores it in a `ThreadLocal` context. A base `TenantAwareRepository` automatically scopes all read and write queries to the current tenant.

4. **Decorrelated Jitter Backoff**: Naive exponential backoff creates "thundering herd" problems when an endpoint comes back online. This service uses AWS-style decorrelated jitter, bounding retries pseudo-randomly while increasing the window over 8 attempts, maxing out at a 4-hour delay.

5. **Circuit Breaker**: Endpoints that consistently fail (5+ consecutive failures) trip the circuit breaker (`circuit_state = 'OPEN'`). The claim query explicitly ignores deliveries for open endpoints until the `cooldown_until` period expires, preventing systemic lock exhaustion on broken downstream servers.

6. **SSRF Protection**: Internal network boundaries are protected. An active `UrlValidator` parses registered endpoint URLs and rejects loopback (`127.x.x.x`), site-local, link-local, and any internal IP resolution to prevent Server-Side Request Forgery.

## 🛠 How to Run

### Prerequisites
- Docker & Docker Compose
- Java 21+

### 1. Start PostgreSQL
Run the provided Docker Compose file to start the PostgreSQL 16 database:
```bash
docker-compose up -d
```
This spins up a container accessible at `localhost:5432` with the credentials defined in `application.properties`.

### 2. Run the Application
The application uses Flyway to automatically migrate the database schema on startup. 
Use the Maven wrapper to build and run the Spring Boot application:
```bash
./mvnw spring-boot:run
```
The server will start on `http://localhost:8080`.

## 🧪 Interacting with the API

### Set up an Endpoint
```bash
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-ID: tenant-a" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://httpbin.org/post",
    "subscribedEventTypes": ["invoice.paid", "user.created"]
  }'
```
*(Save the returned `secret` to verify signatures!)*

### Ingest an Event
```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-Tenant-ID: tenant-a" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-12345",
    "type": "invoice.paid",
    "payload": {
      "invoice_id": "inv_888",
      "amount": 5000,
      "status": "paid"
    }
  }'
```

### View Delivery Status
```bash
curl -X GET http://localhost:8080/api/v1/deliveries \
  -H "X-Tenant-ID: tenant-a"
```

## 📝 Design Decisions & Shortcuts

- **Virtual Threads**: The application is written using standard Java `ExecutorService` with `newCachedThreadPool()` in the delivery worker because local compilation was required on JDK 17, but the architecture is prepared for `Executors.newVirtualThreadPerTaskExecutor()` on JDK 21 in production.
- **Delivery Attempts Persistence**: Instead of deleting old attempts or storing them purely as JSON arrays, every dispatch creates a distinct `DeliveryAttempt` row. This allows for deep querying of latency percentiles and failure distributions per endpoint later.
- **Security Check**: SSRF validation blocks typical private spaces, but for production, strict egress filtering via a proxy (like Smokescreen) would be highly recommended alongside the JVM-level checks.
- **Testing**: WireMock and Testcontainers were used directly in the `test` directory to ensure full lifecycle validation of HTTP timeouts and concurrent database lock claims.
