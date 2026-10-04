# MVP Delivery Plan — Seat Reservation at Scale

This plan translates the requirements in [BRD.md](BRD.md) and [ProblemStatement.md](ProblemStatement.md) into a build order for the take-home MVP. The priority is a clean, correct reservation path first, then proof under load and a deployable service.

## MVP decisions

- **Stack:** Java 17, Spring Boot 3.4.10, Maven, Spring MVC, PostgreSQL. Keep PostgreSQL as the single source of truth.
- **Reservation model:** reservations are confirmed immediately; owners may cancel. Do not add temporary holds or a payment integration in the MVP. `held` remains a supported inventory state with a count of zero unless the model is expanded later.
- **Multi-seat requests:** all-or-nothing. A request succeeds for every requested seat or changes nothing.
- **Concurrency:** perform reservation changes in one database transaction. Acquire a PostgreSQL transaction-scoped advisory lock for `(show_id, user_id)`, then lock requested seat rows in sorted label order. Enforce policy in Java services.
- **Authentication:** accept signed bearer tokens and derive the user identity and role only from validated token claims. Keep signing configuration outside source control. Require an admin role to create shows.
- **API contract:** endpoint-specific success DTOs preserve the assignment's JSON fields. All errors use the shared error response and request ID already scaffolded in the service.

**Overall progress:** Phases 0 through 5 are implemented; local verification passed. The Render service is live according to the user, and a 100-attempt remote burst now verifies readiness, API behavior, metrics access, and inventory reconciliation with no server or transport errors. Cold-start behavior, the PostgreSQL-down readiness check, larger capacity testing, and final handoff materials remain.

## Delivery sequence

### 0. Service foundation — complete

- [x] Establish the Spring Boot application and Maven build.
- [x] Add constants, shared error response, global exception handling, and request correlation ID.
- [x] Configure liveness/readiness probes and Prometheus endpoint exposure.
- [x] Add Lombok conventions and keep enums in the `constant` package.
- [x] Keep this foundation compiling as dependencies and implementation are added.

### 1. Persistence and configuration — complete; Docker startup verified

- [x] Add Spring Data JPA, PostgreSQL driver, and Liquibase migration tooling.
- [x] Add local Docker Compose for the service and PostgreSQL; externalize DB settings and secrets.
- [x] Create one initial Liquibase SQL migration and entities for shows, seats, reservations, reservation-seat links, and idempotency keys.
- [x] Add `created_by`, `created_at`, `updated_by`, and `updated_at` audit fields to all application tables with shared JPA auditing.
- [x] Keep schema rules to persistence structure; store prices and totals as integer paise and enforce business policies in Java services.
- [x] Document the transaction and lock ordering for the service layer.

**Gate:** verified with Docker Compose: the app image builds, PostgreSQL becomes healthy, Liquibase records `001-initial-schema.sql` as executed, Hibernate validates the mappings, app readiness reports `UP`, and all initial application tables contain the four audit columns. The accounts table is added by the follow-on `002-users.sql` migration.

### 2. Authentication and HTTP contract — complete

- [x] Add HS256 bearer-token validation with issuer/expiry validation and a principal carrying token `sub` and `roles` claims.
- [x] Protect show creation with `ADMIN`; protect reservation and cancellation routes with an authenticated identity; leave show state public.
- [x] Define validated request/response DTOs and status/error contracts for create show, reserve, cancel, and show state in `docs/ApiContract.md`.
- [x] Add request validation for required fields, unique/non-empty seat lists, positive integer price, and positive per-user limit input; idempotency-key validation is applied at the controller boundary in Phase 3.
- [x] Keep caller-supplied identity out of request DTOs; user ID is sourced from JWT `sub` and ownership is enforced in Phase 3.
- [x] Add an audited `users` table, BCrypt password storage, default local admin bootstrap, buyer registration and user CRUD (self scope, admin list scope), and one-hour signed JWT issuance.
- [x] Add `POST /auth/signin` for buyer email and bootstrap-admin identifier sign-in; assign roles server-side and never accept a requested role.
- Idempotency header validation and controller handling are part of Phase 3, alongside the reservation endpoint.

**Gate:** API behavior, payloads, and error responses are documented in `docs/ApiContract.md`; Maven package compilation succeeded, and the user reports the authentication and user-account flows work at runtime.

### 3. Reservation correctness — complete

- [x] Implement show creation with all seats initially available.
- [x] Implement one transactional, all-or-nothing reservation operation using a per-user/show PostgreSQL advisory lock and deterministically ordered seat-row locks.
- [x] Enforce the per-user limit (default four) within the same transaction as seat assignment.
- [x] Persist the idempotency key and canonical, order-independent seat fingerprint. Same key + same request returns the original reservation; same key + different seats returns 409.
- [x] Validate and process the `Idempotency-Key` request header for reservation requests.
- [x] Implement owner-only cancellation; serialize with the owner's reservations and lock linked seats before marking a reservation cancelled.
- [x] Implement show state with per-seat status and counts derived from persisted reservations.
- [x] Map expected conflicts (unavailable seat, limit exceeded, key/body mismatch, missing show/reservation) to documented 4xx responses.

**Gate:** The user reports the reservation workflow works at runtime. The `scripts/reservation-flow.http` flow covers show creation/state, idempotent replay and mismatch, seat conflicts, user limits, owner-only cancellation, and rebooking. Phase 4 adds automated PostgreSQL verification for contention, rollback, and reconciliation.

### 4. Correctness verification

- [x] Add integration coverage against PostgreSQL for seat contention, same-key retries, same-key/different-body, per-user limit races, multi-seat rollback, cancellation ownership, and inventory reconciliation.
- [x] Run parallel tests repeatedly; assert one winner for a hot seat, no expected 5xx responses, user limit respected, and available + held + confirmed equals total.
- [x] Review race, deadlock, rollback, and response-contract outcomes; no discrepancy surfaced in this verification run.

**Gate:** `mvn -q test` passed against an isolated PostgreSQL 16 container. The integration suite verified all listed scenarios, including 20 concurrent requests for one seat (one success, 19 expected conflicts, zero 5xx), six simultaneous reservations constrained by a user's limit of two, and three repeated hot-seat runs. Testcontainers is the default test database provider; `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` can target an explicitly started test database. This validates correctness under the tested contention, not the assignment's 20,000-request capacity target; that remains part of Phase 5 burst/load work.

### 5. Observability and burst tool

- [x] Add counters for confirmed reservations, domain declines by reason, idempotent replays, and cancellations.
- [x] Add a global available-seat gauge derived from persisted seats/reservations, with no per-show labels.
- [x] Emit request-completion and key controller/service outcome logs with a shared `trace_id`; log domain API errors at error level before throwing, without logging request bodies, credentials, or tokens.
- [x] Confirm at runtime that readiness fails when PostgreSQL is unavailable while liveness remains independent of the database. The readiness group includes the `db` health indicator and the liveness group is independent by configuration.
- [x] Add `scripts/burst.py` and document it in `docs/Docker.md`; it creates test data, runs a configurable hot-seat storm, exercises idempotent replay and per-user limits, and reports response distribution, latency, server/transport errors, metrics, and final inventory reconciliation.
- [x] Add JSON burst profiles and CLI overrides, dry-run plan output, ramp-up and scenario selection. Require explicit opt-in for remote targets and a second opt-in above 1,000 remote hot-seat attempts; add a 100-attempt Render profile.

**Gate:** After correcting the burst client's `Accept` header for the Prometheus text endpoint, repeated 100-attempt local runs passed. The Render run using `scripts/burst-render-config.example.json` also passed: 100 hot-seat attempts yielded one confirmation and 99 `SEAT_UNAVAILABLE` conflicts; the idempotent retry returned the same reservation; the per-user race yielded one confirmation and four `USER_SEAT_LIMIT_EXCEEDED` declines; both inventories reconciled; metrics returned HTTP 200; and there were zero server/transport errors. Observed remote performance was 4.0 hot-seat requests/s, p50 2.10s, p95 5.82s, max 7.11s. This is a small correctness smoke run, not evidence of the 20,000-attempt capacity target. Runtime verification with PostgreSQL unavailable (readiness down, liveness up) remains. The default burst size is 20,000 attempts; use smaller settings for local smoke runs.

### 6. Container, public deployment, and handoff

- [x] Add a multi-stage Dockerfile and document local Docker Compose startup; verified the image build and healthy local app/database containers.
- [x] Deploy the app on Render with the existing Render PostgreSQL database; the user reports the service is live at [https://seat-reservation-lrb5.onrender.com](https://seat-reservation-lrb5.onrender.com), and the remote burst run below exercised it successfully. Added `render.yaml`, Render `PORT` binding, and [RenderDeployment.md](RenderDeployment.md).
- [x] Verify readiness, admin authentication, reservation/idempotency/user-limit behavior, metrics access, and inventory reconciliation against the deployed URL with the 100-attempt Render burst profile (results above).
- [x] Verify cold-start behavior after the service has been idle or restarted.
- [x] Add root `README.md` with the public repo and live URL, local Docker start, health/metrics/log access, and Render burst command; add `WRITEUP.md` with the locking/idempotency mechanisms, holds, partition behavior, observability, AI-use disclosure, and next steps.
- [x] Confirm a fresh clone can build and start using the documented path.

**Gate:** the reported live service passed the small remote smoke run. Confirm cold-start behavior and document how the evaluator can reproduce the run. Use `scripts/burst-render-config.example.json`, inspect `--dry-run` output, then explicitly pass `--allow-remote`. Larger remote loads require `--allow-high-load` as well. The burst creates persistent shows and reservations; it deletes only temporary users.

## MVP acceptance checklist

- A concurrent hot-seat storm yields exactly one successful reservation for that seat and clean 409 responses for other distinct requests; expected contention creates no 5xx responses.
- Same-key retries return the original reservation without additional seat allocation; a changed request with that key returns 409.
- Parallel bookings cannot exceed the per-user limit, and multi-seat requests are all-or-nothing.
- Only the reservation owner can cancel; a cancelled seat can be booked again safely.
- Show counts reconcile exactly: `available + held + confirmed = total_seats`.
- Token claims determine identity and role; caller-supplied identity cannot override them.
- Readiness reflects database health; required metrics and correlated logs are available.
- The containerized service is deployed publicly, survives cold start, and the burst command reports outcomes and final reconciliation.

## Explicitly out of scope for MVP

- Frontend/UI, payment processing, refunds, waitlists, seat maps, multi-show search, distributed caches/queues, and temporary seat holds.
- Multi-region writes or availability during a database partition. Reservation writes fail closed if the authoritative database is unavailable.
