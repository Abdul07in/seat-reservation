# HTTP API Contract

This document defines the JSON contract for the MVP. IDs are UUID strings. All request and response property names use `snake_case`; prices are integer paise.

## Authentication

Send `Authorization: Bearer <JWT>`. Tokens must be signed with HS256 and contain a valid `iss`, `exp`, and non-empty `sub` (the user ID). Optional `roles` is an array of role names, for example `["ADMIN"]`. The service derives buyer identity only from `sub`; no body field can select a different user. The JWT secret is injected through `JWT_SECRET` and must be at least 32 UTF-8 bytes. The built-in local default is development-only and must be replaced outside local development.

| Method and path | Access | Success |
| --- | --- | --- |
| `POST /users` | Public registration | `201 Created`, `UserResponse` |
| `POST /auth/signin` | Public | `200 OK`, `TokenResponse` |
| `GET /users` | `ADMIN` role | `200 OK`, list of `UserResponse` |
| `GET /users/{id}` | Owner or `ADMIN` | `200 OK`, `UserResponse` |
| `PUT /users/{id}` | Owner or `ADMIN` | `200 OK`, `UserResponse` |
| `DELETE /users/{id}` | Owner or `ADMIN` | `204 No Content` (deactivates account) |
| `POST /shows` | `ADMIN` role | `201 Created`, `CreateShowResponse` |
| `GET /shows/{id}` | Public | `200 OK`, `ShowStateResponse` |
| `POST /shows/{id}/reserve` | Authenticated | `201 Created`, `ReservationResponse` |
| `POST /reservations/{id}/cancel` | Authenticated; owner checked by service | `200 OK`, `CancellationResponse` |
| `/actuator/**` | Public | Actuator response |

New users register with email and password; the service assigns `USER` and never accepts a role from the request. The local bootstrap admin signs in with identifier `admin` and password `admin123` by default. Override `ADMIN_IDENTIFIER` and `ADMIN_PASSWORD` outside local development. Passwords are stored as BCrypt hashes. Sign-in accepts an email for buyers or the configured admin identifier:

```json
{"identifier": "buyer@example.com", "password": "a-long-password"}
```

It returns a one-hour bearer token with `sub` set to the account UUID and `roles` set to the account role. The bootstrap admin is created on first startup only; changing its environment password does not reset an already-created account. Admin identifier/password defaults are for local development and must be overridden for a deployed environment.

`POST /users` registration uses:

```json
{"email": "buyer@example.com", "password": "a-long-password"}
```

Update uses the same email plus an optional password; omitting/blanking password retains the existing hash. Delete is a soft deactivation. User list access is admin-only; a signed-in user can read, update, or deactivate only their own UUID.

All other routes are denied. Authentication and authorization errors use the shared error body below. `X-Request-Id` is accepted when it contains 1–100 safe ASCII letters, digits, dots, underscores, or hyphens; otherwise the service generates a UUID and returns it in the same response header.

## Create show

`POST /shows`

```json
{
  "name": "friday-night",
  "seats": ["A1", "A2", "A3"],
  "price_paise": 25000,
  "per_user_limit": 4
}
```

`name` is required (up to 150 characters); `seats` must be non-empty, unique, non-blank labels (up to 20 characters each); `price_paise` is a positive integer. `per_user_limit` is optional and positive; omitted means four.

Response:

```json
{
  "id": "<show-uuid>",
  "name": "friday-night",
  "seats": [{"seat": "A1", "status": "available"}]
}
```

## Read show state

`GET /shows/{id}` returns `id`, `name`, `total_seats`, `counts` (`available`, `held`, `confirmed`), and every seat with its status. Holds are out of scope, so `held` currently remains zero. Counts must reconcile to `total_seats`.

## Reserve seats

`POST /shows/{id}/reserve` requires an authenticated JWT and the `Idempotency-Key` header. The key is required, non-blank, and limited to 255 characters. The body does not contain a user ID.

```json
{"seats": ["A12", "A13"]}
```

Seat labels must be non-empty and unique. The MVP uses all-or-nothing multi-seat reservation. Success is `201 Created`:

```json
{
  "reservation_id": "<reservation-uuid>",
  "show_id": "<show-uuid>",
  "user_id": "<user-uuid-from-token-sub>",
  "seats": ["A12", "A13"],
  "amount_paise": 50000,
  "status": "confirmed"
}
```

The idempotency scope is `(show_id, authenticated user_id, Idempotency-Key)`. Repeating the same seat set returns the original reservation (including its current status); using the key for a different seat set returns `409 IDEMPOTENCY_KEY_REUSED`. Unavailable seats and the per-user limit return `409 SEAT_UNAVAILABLE` and `409 USER_SEAT_LIMIT_EXCEEDED`. Seat order does not affect the request fingerprint.

## Cancel reservation

`POST /reservations/{id}/cancel` requires an authenticated JWT. The service permits only the owner to cancel. Cancellation is idempotent; repeating it leaves seats available and returns `200 OK` with `{"reservation_id":"<reservation-uuid>","status":"cancelled"}`.

## Errors

Every application error uses this shape (validation details map field names to messages):

```json
{
  "timestamp": "2026-10-04T10:00:00Z",
  "status": 401,
  "code": "UNAUTHORIZED",
  "message": "A valid bearer token is required",
  "path": "/shows/…/reserve",
  "request_id": "<request-id>",
  "details": {}
}
```

| Status | Meaning |
| --- | --- |
| `400` | Malformed JSON or bean validation failure (`INVALID_REQUEST` / `VALIDATION_ERROR`) |
| `401` | Missing or invalid bearer token (`UNAUTHORIZED`) |
| `403` | Valid identity without required permission (`FORBIDDEN`) |
| `404` | Requested show or reservation does not exist (`RESOURCE_NOT_FOUND`) |
| `409` | Expected domain conflict: `SEAT_UNAVAILABLE`, `USER_SEAT_LIMIT_EXCEEDED`, or `IDEMPOTENCY_KEY_REUSED` |
| `500` | Unexpected server failure (`INTERNAL_ERROR`) |

## Implementation boundary

Authentication, user-account, show, and reservation routes are implemented. Runtime concurrency and database integration verification is tracked in Phase 4.
