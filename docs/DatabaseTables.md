# Database Tables

The database stores service data. Reservation policies and validation are enforced by Java services.

Every application table has `created_by`, `created_at`, `updated_by`, and `updated_at`. JPA auditing fills these fields through the shared `AuditableEntity` superclass. Once authentication is added, it must set the trusted `actorId` request context from the validated token; never populate it from request data. The provider falls back to `SYSTEM` for background or unauthenticated work.

| Table | Purpose | Main data |
|---|---|---|
| `shows` | Stores each event/show and its booking configuration. | `id`, `name`, `price_paise`, `per_user_limit`, `created_by`, `created_at`, `updated_by`, `updated_at` |
| `seats` | Stores the seat labels available for a show. A seat's availability is derived from its active reservation. | `id`, `show_id`, `seat_label`, `created_by`, `created_at`, `updated_by`, `updated_at` |
| `reservations` | Stores a buyer's reservation and its lifecycle. `user_id` is the identity from the validated token. | `id`, `show_id`, `user_id`, `status`, `amount_paise`, `cancelled_at`, `created_by`, `created_at`, `updated_by`, `updated_at` |
| `reservation_seats` | Connects reservations to their seats and preserves the seat list after cancellation. | `id`, `reservation_id`, `seat_id`, `created_by`, `created_at`, `updated_by`, `updated_at` |
| `idempotency_keys` | Stores each request key, its request fingerprint, and the reservation to return on a retry. | `id`, `show_id`, `user_id`, `idempotency_key`, `request_fingerprint`, `reservation_id`, `created_by`, `created_at`, `updated_by`, `updated_at` |
| `users` | Stores registered buyer accounts and the bootstrapped admin account. Passwords are stored only as BCrypt hashes; roles and active state support authentication and account lifecycle. | `id`, `email`, `password_hash`, `role`, `active`, `created_by`, `created_at`, `updated_by`, `updated_at` |

## Service-owned rules

- The service creates shows with unique seat labels and applies the default per-user limit of four.
- Reservation status and seat availability are decided in the service. The service calculates show counts from seats and active reservations; no duplicate seat-status counter is stored.
- The service checks per-user limits, all-or-nothing seat availability, cancellation ownership, and idempotent replay behavior within transactions. User-scoped advisory locks and sorted seat-row locks serialize concurrent requests.
- Prices and totals are integer paise. `request_fingerprint` contains the service-computed fingerprint used to detect a changed request with the same key.

The schema and indexes are defined in the Liquibase migrations under `src/main/resources/db/changelog/changes/`. The users table was added in `002-users.sql` so databases that already applied the initial schema can upgrade safely.
