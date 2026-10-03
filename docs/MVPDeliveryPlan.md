# MVP Delivery Plan — Seat Reservation at Scale

This plan translates the requirements in [BRD.md](BRD.md) and [ProblemStatement.md](ProblemStatement.md) into a build order for the take-home MVP. The priority is a clean, correct reservation path first, then proof under load and a deployable service.

## MVP decisions

- **Stack:** Java 17, Spring Boot 3.4.10, Maven, Spring MVC, PostgreSQL. Keep PostgreSQL as the single source of truth.
- **Reservation model:** reservations are confirmed immediately; owners may cancel. Do not add temporary holds or a payment integration in the MVP. `held` remains a supported inventory state with a count of zero unless the model is expanded later.
- **Multi-seat requests:** all-or-nothing. A request succeeds for every requested seat or changes nothing.
- **Concurrency:** perform reservation changes in one database transaction. Acquire a PostgreSQL transaction-scoped advisory lock for `(show_id, user_id)`, then lock requested seat rows in sorted label order. Enforce policy in Java services.
- **Authentication:** accept signed bearer tokens and derive the user identity and role only from validated token claims. Keep signing configuration outside source control. Require an admin role to create shows.
- **API contract:** endpoint-specific success DTOs preserve the assignment's JSON fields. All errors use the shared error response and request ID already scaffolded in the service.

**Overall progress:** Phases 0, 1, 2, and 3 are complete, including user-reported runtime smoke verification. Parallel correctness/load verification, observability enhancements, and public deployment remain.

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

**Gate:** The user reports the reservation workflow works at runtime. The `scripts/reservation-flow.http` flow covers show creation/state, idempotent replay and mismatch, seat conflicts, user limits, owner-only cancellation, and rebooking. Parallel contention storms and explicit transactional rollback verification remain in Phase 4.

### 4. Correctness verification

- [ ] Add integration coverage against PostgreSQL for seat contention, same-key retries, same-key/different-body, per-user limit races, multi-seat rollback, cancellation ownership, and inventory reconciliation.
- [ ] Run parallel tests repeatedly; assert one winner for a hot seat, no expected 5xx responses, user limit respected, and available + held + confirmed equals total.
- [ ] Fix any race, deadlock, or response-contract discrepancy before adding deployment work.

**Gate:** correctness scenarios pass against the same database engine used for deployment.

### 5. Observability and burst tool

- [ ] Add counters for confirmed reservations, domain declines by reason, idempotent replays, and cancellations.
- [ ] Add an available-seat gauge that can be reconciled with show state; define whether it is per-show or global and avoid unbounded metric labels.
- [ ] Ensure structured request logs include the correlation ID and outcome; keep credentials and tokens out of logs.
- [ ] Confirm readiness fails when PostgreSQL is unavailable while liveness remains independent of the database.
- [ ] Add a one-command burst client that creates/uses a show, starts a hot-seat storm, exercises retries and per-user limits, and reports response distribution, 5xx count, and final reconciliation.

**Gate:** metric totals and burst output agree with the persisted/API state after a run.

### 6. Container, public deployment, and handoff

- [x] Add a multi-stage Dockerfile and document local Docker Compose startup; verified the image build and healthy local app/database containers.
- [ ] Deploy the app and managed PostgreSQL to a public host; configure secrets, migrations, port binding, and health checks.
- [ ] Verify cold start, readiness, API behavior, metrics access, and burst script against the deployed URL.
- [ ] Complete README run/deploy/burst instructions and `WRITEUP.md` covering atomicity, idempotency, cancellation, partition trade-offs, alerting, AI use, and follow-up work.
- [ ] Confirm a fresh clone can build and start using the documented path.

**Gate:** public service is healthy and the evaluator can reproduce the burst from the repository instructions.

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
