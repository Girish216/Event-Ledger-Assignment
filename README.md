# Event Ledger

An event ledger built as two independent Spring Boot microservices: a public-facing **Event Gateway**
and an internal **Account Service**. Upstream systems send financial transaction events that may
arrive out of order and may be delivered more than once; the system stays correct under both.

## Architecture

```
Browser / Client ──HTTP──▶ Event Gateway (8080) ──HTTP + circuit breaker──▶ Account Service (8081)
                                  │                                              │
                              H2 (gatewaydb)                                H2 (accountdb)
```

- **Event Gateway** (`event-gateway`) is the only service clients talk to. It validates incoming
  events, enforces idempotency, stores every event it receives (its own durable record of what was
  submitted), and calls the Account Service to apply the transaction. It never shares a database or
  in-process state with the Account Service.
- **Account Service** (`account-service`) owns account balances and transaction history. It is only
  ever called by the Gateway and is not exposed externally. It applies each transaction exactly once
  per `eventId` and keeps its own H2 database.
- The two services communicate over synchronous REST. Every request carries a trace ID (propagated
  as a W3C `traceparent` header by Spring's Brave-based tracing) so a single client call can be
  followed through both services' logs.

### Endpoints

**Event Gateway**

| Method | Endpoint | Notes |
|---|---|---|
| `POST` | `/events` | Submit a transaction event. Idempotent on `eventId`. |
| `GET` | `/events/{id}` | Fetch a single event. Works even if the Account Service is down. |
| `GET` | `/events?account={accountId}` | Events for an account, chronological by `eventTimestamp`. |
| `GET` | `/accounts/{accountId}/balance` | Proxies the Account Service's balance. |
| `GET` | `/health` | Health check (includes DB connectivity and circuit breaker state). |

> The endpoint table in the assignment brief doesn't list a Gateway balance endpoint, but the
> Graceful Degradation requirement explicitly expects the Gateway to return a clear error for balance
> queries when the Account Service is unreachable — and since the Account Service is internal-only,
> the client has no other way to reach it. `GET /accounts/{accountId}/balance` on the Gateway fills
> that gap; see `AccountProxyController`.

**Account Service** (internal)

| Method | Endpoint | Notes |
|---|---|---|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction. Idempotent on `eventId`. |
| `GET` | `/accounts/{accountId}/balance` | Current balance. |
| `GET` | `/accounts/{accountId}` | Account details plus recent transactions. |
| `GET` | `/health` | Health check. |

## Key design decisions

**Idempotency.** Both services dedupe on `eventId` via a unique DB constraint. On the happy path each
service checks for an existing record before doing any work; as a defense against a race between two
concurrent identical submissions, the unique constraint is also the backstop — a constraint violation
is caught and the caller gets back the record that actually won the race, rather than an error.

**Out-of-order tolerance.** Balance is `sum(CREDIT) - sum(DEBIT)`, which is commutative — the final
balance is correct regardless of the order transactions are *applied* in, so no special handling is
needed there. Where order matters is *display*: event and transaction listings are always sorted by
`eventTimestamp`, not by arrival/processing order.

**Retrying a failed event.** If the Account Service was unreachable when an event was first submitted,
the Gateway still durably stores the event (status `FAILED`) rather than dropping it — the ledger
shouldn't lose a record just because a downstream call failed. If the same `eventId` is submitted
again, the Gateway retries the Account Service call using the *originally stored* event data (not
whatever the retry payload happens to contain), so a retry can only ever resolve to the original
transaction. Once an event reaches status `PROCESSED`, further submissions of the same `eventId` are
pure idempotent replays and never call the Account Service again.

**Resiliency: circuit breaker + timeout.** The Gateway's call to the Account Service (`AccountServiceClient`)
is wrapped in a Resilience4j circuit breaker with a request timeout on the underlying HTTP client. If
the Account Service is failing repeatedly, the breaker opens and the Gateway fails fast with `503`
instead of continuing to pile up slow/failing calls against an already-struggling dependency — a
plain timeout+retry alone would keep hammering a service that needs to recover, and retries can
amplify load on a service that's already unhealthy. A 404 from the Account Service (unknown account
on a balance lookup) is explicitly excluded from the breaker's failure count, since that's a normal
business response, not an availability problem.

**Graceful degradation.** When the Account Service is down: `POST /events` returns `503` (not a hang,
not a `500`); `GET /events/{id}` and `GET /events?account=` keep working since they only touch the
Gateway's own database; balance queries return `503` with a clear message.

**Tracing.** Uses Spring Boot's built-in Micrometer Tracing + Brave integration rather than a full
OpenTelemetry SDK/collector — it auto-generates a trace ID per incoming request, auto-propagates it to
the Account Service over HTTP headers, and auto-populates it into each log line via MDC, all without
custom plumbing. That covers the assignment's tracing requirement with far less moving-parts risk than
standing up an OTel Collector + exporter in the time available.

**Structured logging.** Both services log JSON to stdout (via `logstash-logback-encoder`) with
timestamp, level, logger, service name, and `traceId`/`spanId` when a trace is active.

**Metrics.** Beyond Spring Boot Actuator's built-in `http.server.requests` timer, each service
records explicit custom counters: the Account Service tracks `account.transactions.applied` (tagged
by CREDIT/DEBIT) and `account.transactions.duplicate`; the Gateway tracks `events.received`,
`events.processed`, `events.duplicate`, and `events.failed`. All are visible at each service's
`/metrics` and `/prometheus` endpoints.

## Prerequisites

- Java 21+ (the project builds and runs fine on newer JDKs too)
- Docker + Docker Compose, **or** nothing else — a Maven wrapper is checked in, so you don't need
  Maven installed to build or run either service from source.

## Running with Docker Compose

```
docker compose up --build
```

This builds both services and starts them together, Gateway on `:8080`, Account Service on `:8081`,
with the Gateway waiting on the Account Service's health check before it starts.

Verified end-to-end against real containers: both come up `healthy`; idempotent submission,
out-of-order balance correctness, and chronological listing all behave correctly over the real
Docker network; stopping the `account-service` container makes the Gateway return `503` on
`POST /events` and on the balance proxy while `GET /events/{id}` keeps working — then everything
recovers cleanly once `account-service` is started again.

## Running manually

From the repository root:

```
./mvnw -pl account-service spring-boot:run
```

In a second terminal:

```
./mvnw -pl event-gateway spring-boot:run
```

(On Windows use `mvnw.cmd` instead of `./mvnw`.) The Account Service must be reachable at the URL in
`event-gateway/src/main/resources/application.yml` (`account-service.base-url`, defaults to
`http://localhost:8081`) or via the `ACCOUNT_SERVICE_BASE_URL` environment variable.

Or build both jars once and run them directly:

```
./mvnw -q package -DskipTests
java -jar account-service/target/account-service-*-exec.jar
java -jar event-gateway/target/event-gateway-*.jar
```

## Running the tests

```
./mvnw test
```

Runs both modules' test suites (21 tests total):

- **Account Service** — idempotency, out-of-order balance correctness, validation, 404s.
- **Event Gateway** — idempotency, validation, chronological listing, a resiliency suite that drives
  the circuit breaker through CLOSED → OPEN → HALF_OPEN → CLOSED against a simulated Account Service
  outage, a trace-propagation test, and a full integration test that runs a real Gateway against a
  real (in-process) Account Service with no mocking on either side.

## Example requests and responses

Submit a new event:

```
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
  }'
```

```
HTTP/1.1 201 Created

{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" },
  "status": "PROCESSED",
  "receivedAt": "2026-07-17T00:50:49.522101Z",
  "processedAt": "2026-07-17T00:50:50.339128Z",
  "failureReason": null,
  "duplicate": false
}
```

Submit the exact same `eventId` again — no duplicate event, no balance change, `200` instead of `201`:

```
HTTP/1.1 200 OK

{ "...": "same event as above", "duplicate": true }
```

Check the balance (this is `sum(CREDIT) - sum(DEBIT)`, order-independent):

```
curl http://localhost:8080/accounts/acct-123/balance
```
```json
{ "accountId": "acct-123", "balance": 150.00, "currency": "USD", "asOf": "2026-07-17T00:51:16.366Z" }
```

List events for an account — always chronological by `eventTimestamp`, regardless of the order they
were submitted in:

```
curl "http://localhost:8080/events?account=acct-123"
```

If the Account Service is stopped and you retry the `POST /events` call above:

```
HTTP/1.1 503 Service Unavailable

{
  "timestamp": "2026-07-17T00:56:29.079981300Z",
  "status": 503,
  "error": "Service Unavailable",
  "message": "Account Service is unreachable",
  "path": "/events"
}
```

...while `GET /events/{id}` and `GET /events?account=` on the same Gateway keep returning `200`,
since they only read the Gateway's own database.

## Example test run

```
./mvnw test
...
[INFO] Results:
[INFO]
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0   -- account-service
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0  -- event-gateway
[INFO]
[INFO] BUILD SUCCESS
```
