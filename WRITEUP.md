# Design and Operations Write-up

## Atomic reservation decision

`ReservationServiceImpl.reserve` runs in one PostgreSQL transaction. It first takes a transaction-scoped advisory lock with `pg_advisory_xact_lock(hashtextextended(showId + ":" + userId, 0))`. That serializes requests for the same user and show across application instances, so idempotency lookup and the per-user seat-limit check cannot race each other. PostgreSQL releases the lock automatically at commit or rollback.

The service then locks every requested seat row with `SELECT ... FOR UPDATE`, ordered by seat label. Different users competing for a seat therefore serialize on the same row; after obtaining the lock, the loser sees the winner's committed reservation and receives `SEAT_UNAVAILABLE`. Multi-seat requests acquire row locks in the same deterministic order, avoiding cycles between requests that overlap on seats. The reservation row, reservation-seat links, and idempotency record are written in that transaction, so a failure rolls back the whole request. Cancellation takes the same user/show advisory lock and locks its reservation and linked seats before changing its status.

## Idempotency

The `idempotency_keys` table stores `(show_id, user_id, idempotency_key)`, a SHA-256 fingerprint of the normalized seat labels, and the reservation ID. The scope uses the authenticated user's token subject; clients cannot choose another user's ID. Labels are trimmed, sorted, joined canonically, and hashed, making seat order irrelevant.

Because same-user/same-show transactions take the PostgreSQL advisory lock before lookup and insert, concurrent requests with the same key cannot both create a reservation through this service. A matching fingerprint returns the original reservation; a different fingerprint returns `409 IDEMPOTENCY_KEY_REUSED`. The normal same-key replay is counted separately from declines. The schema currently has a lookup index rather than a unique constraint on the key scope, so this invariant relies on all writes going through the locking service method. Adding a matching database unique constraint is a hardening step. Idempotency records currently have no expiry or cleanup job.

## Holds and expiry

Temporary holds, payment processing, and hold expiry are not implemented in this MVP. A successful request confirms immediately. `held` remains in the response model but is always zero. Cancellation changes a confirmed reservation to `CANCELLED`; availability is derived from the persisted reservation status, so its seats can then be reserved again. If holds are added, I would persist an expiry instant and use a transactional expiry worker that follows the same lock order before releasing seats.

## Consistency and availability under a partition

PostgreSQL is the authoritative source for seats and reservations. The service does not confirm from a cache or queue. If an application instance cannot reach PostgreSQL, reservation writes cannot commit; the service fails closed and sacrifices write availability rather than risk selling the same seat twice. Readiness includes the database health indicator, while liveness is independent so a database outage does not cause healthy app processes to be restarted. The runtime behavior during an induced database outage remains to be verified.

## Observability

Prometheus metrics are exposed at [`/actuator/prometheus`](https://seat-reservation-lrb5.onrender.com/actuator/prometheus). They include committed confirmations, declines tagged by bounded reason, successful idempotent replays, cancellations, and a global available-seat gauge derived from PostgreSQL. Confirmation, replay, and cancellation counters increment after transaction commit. The 100-attempt Render smoke run produced one hot-seat confirmation and 99 seat conflicts, one successful idempotent replay, one per-user-race confirmation and four limit declines, reconciled both shows, and returned HTTP 200 metrics with no server or transport errors.

Each request gets a validated incoming `X-Request-Id` or a generated UUID. The same ID is returned in the response header, placed in MDC as `traceId`, and included in request completion and domain outcome logs. Log lines use key/value event fields; they are console text rather than JSON records. On Render, logs are available in the authenticated service dashboard under **Logs**; locally, use `docker compose logs -f app`. There is no public log-stream endpoint or attached screen recording in this deliverable.

For a 2 a.m. on-call rotation, I would page on sustained readiness failures, sustained 5xx/transport failures, and database connection-pool exhaustion or query latency that prevents reservation commits. I would alert on an inventory reconciliation mismatch immediately. A high seat-unavailable rate during an expected sale is a dashboard signal rather than a page by itself; a sudden rise in user-limit or idempotency-key-reuse declines would be investigated for client behavior. Alert rules and paging integration are not configured in this MVP.

## AI use

I used OpenAI Codex / Antigravity [Free tiers ] as a coding assistant throughout the implementation. I supplied the assignment requirements and directed the service layout, API contract consistency, Liquibase SQL schema approach, audit fields, constants/enums placement, and interface/implementation service pattern. Codex generated and revised substantial parts of the Spring implementation, migrations, tests, Docker/Render configuration, burst client, and documentation. I reviewed the changes iteratively and ran the application, tests, and burst commands; I reported runtime output and failures, including the Prometheus content-negotiation issue, and used those results to steer fixes. The advisory-lock design, metric choices and deployment wiring were AI-assisted implementation decisions that I evaluated against the requirements and runtime behavior; I did not write every line unaided.

In my current organization we use Github Copilot [ Sonnet 5 ] for daily coding

## What I would do next

1. Add a unique constraint on `(show_id, user_id, idempotency_key)` and a retention policy for idempotency records.
2. Verify cold start after idle/restart and test readiness-down/liveness-up with PostgreSQL unavailable.
3. Add JSON log encoding, deploy alert rules, and capture a short Render log view during a burst.
4. Run a larger, staged load test against a capacity appropriate for the Render plan; the current 100-attempt public run validates correctness, not the 20,000-attempt target.
5. Add expiring holds and payment integration only if product scope requires them, preserving the database transaction and lock ordering.
