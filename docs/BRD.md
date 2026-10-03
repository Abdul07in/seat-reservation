# Business Requirements Document: Seat Reservation at Scale

## Purpose

Build and operate a reliable JSON service for selling assigned seats to a show. The service must make correct reservation decisions during heavy, concurrent demand and provide enough operational visibility to verify its behavior.

## Business goals

- Prevent a seat from being held or sold to more than one user.
- Enforce the per-user seat limit for each show (default: four).
- Make retries safe so a repeated request cannot create another reservation or charge.
- Keep service outcomes and operational metrics understandable during an on-sale burst.

## Scope

The deliverable is a backend API; a user interface is not required. It includes show creation, authenticated seat reservations, reservation release or expiry, show inventory state, health checks, metrics, structured logs, containerization, deployment, and a burst-test script.

## Users and access

- **Administrator:** creates a show and defines its seats and price.
- **Buyer:** views show availability and reserves or cancels their own reservation.
- Buyer identity must be derived from the authentication token. A request body must not be able to impersonate another user.

## Functional requirements

1. **Create a show:** `POST /shows` accepts a name, unique seat list, and price in integer paise. It returns a show ID and seats initially marked available.
2. **Reserve seats:** `POST /shows/{id}/reserve` accepts the requested seats and an idempotency key. On success it returns a reservation ID, show and user IDs, seats, amount in paise, and confirmed status.
3. **Reservation rules:**
   - A seat can have at most one active holder or confirmed owner. Conflicts return HTTP 409, not a server error.
   - A user cannot exceed the show's per-user limit; over-limit attempts are cleanly declined.
   - Repeating the same key and request returns the original reservation. Reusing a key with different seats returns HTTP 409.
   - Multi-seat requests use a documented, concurrency-safe policy. The recommended policy is all-or-nothing.
4. **Release seats:** provide owner-authorized cancellation or time-boxed holds that expire. Released seats become available for a new reservation without overwriting a later reservation.
5. **Show state:** `GET /shows/{id}` reports every seat as available, held, or confirmed, plus counts. Counts must always sum to the total seat count.
6. **Operations endpoints:** provide liveness and readiness checks; readiness must check database availability and fail when the dependency is unavailable.

## Non-functional and operational requirements

- Support bursts of approximately 20,000 concurrent reservation attempts, including contention on a small set of popular seats.
- Make seat allocation and per-user-limit enforcement atomic under concurrency.
- Return domain declines as 4xx responses and avoid 5xx responses for expected contention.
- Use integer paise for all monetary values; do not use floating-point amounts.
- Provide Prometheus-style metrics: confirmed reservations, declines by reason, and available seats. Metrics must agree with API inventory state.
- Emit structured logs with a request/correlation ID; provide public log access where supported or a short recording of live logs under load.
- Provide a Docker-based clean-checkout run path and deploy to a public URL that recovers from cold start and becomes healthy.
- Include a one-command burst script that reports confirmed, declines by reason, 5xx responses, and final inventory reconciliation.

## Acceptance criteria

- A storm on one seat produces exactly one successful reservation; all other attempts receive a clean conflict.
- No seat is confirmed for multiple users, and expected contention produces no 5xx responses.
- Available + held + confirmed equals total seats throughout and after a burst.
- Same-key retries create only one reservation; same-key requests with different seats are rejected.
- Concurrent requests cannot let a user exceed the configured limit.
- Token identity governs reservation and cancellation permissions.
- Health, metrics, logs, deployment URL, container instructions, and burst script are usable from a clean checkout.

## Assumptions and decisions to document

- The service uses one authoritative datastore for reservation decisions.
- The implementation must document its atomic concurrency mechanism, multi-seat policy and deadlock strategy, idempotency storage, and release/expiry model.
- The write-up must also cover consistency versus availability during a partition, alerting priorities, honest AI usage, and next steps.

## Deliverables

Public Git repository with incremental history; deployed service URL; Docker setup; README instructions for the burst script; accessible metrics and logs (or a log recording); and `WRITEUP.md` describing the design and operational decisions.
