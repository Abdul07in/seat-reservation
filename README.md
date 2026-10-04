# Seat Reservation at Scale

A Spring Boot and PostgreSQL API for creating shows and reserving seats under concurrent load. The public Render deployment is [https://seat-reservation-lrb5.onrender.com](https://seat-reservation-lrb5.onrender.com).

Source repository and incremental commit history: [github.com/Abdul07in/seat-reservation](https://github.com/Abdul07in/seat-reservation).

## Run locally with Docker

From the repository root:

```bash
docker compose up --build -d
```

The app starts on port `8080`; PostgreSQL, Prometheus, and Grafana start with it. Check readiness and follow logs:

```bash
curl http://localhost:8080/actuator/health/readiness
docker compose logs -f app
```

Local bootstrap admin credentials are `admin` / `admin123`. They are assignment defaults. Set `ADMIN_IDENTIFIER`, `ADMIN_PASSWORD`, `JWT_SECRET`, and database variables before using a non-local deployment. See [Docker instructions](docs/Docker.md) for stop, rebuild, and data-reset commands.

## Health, metrics, and logs

The Render service exposes:

- [Liveness](https://seat-reservation-lrb5.onrender.com/actuator/health/liveness): process health, independent of PostgreSQL.
- [Readiness](https://seat-reservation-lrb5.onrender.com/actuator/health/readiness): ready only when the database check is healthy.
- [Prometheus metrics](https://seat-reservation-lrb5.onrender.com/actuator/prometheus): counters for confirmed reservations, declines by reason, idempotent replays, cancellations, and the available-seat gauge.

The key Prometheus series are `seat_reservation_confirmed_total`, `seat_reservation_declined_total{reason=...}`, `seat_reservation_idempotent_replays_total`, `seat_reservation_cancellations_total`, and `seat_reservation_seats_available`.

On Render, open the service in the Render Dashboard and select **Logs** to view application output. Logs include the `trace_id` and `X-Request-Id`; the API also returns `X-Request-Id` in its response header. Render's log viewer requires dashboard access; the service does not provide a public log-stream endpoint. Locally, use `docker compose logs -f app`.

## Run the burst against Render

The Python burst client uses only the Python standard library. It verifies a hot-seat storm, idempotent retry, per-user limit race, metrics response, and final inventory reconciliation. The committed Render profile is intentionally limited to 100 hot-seat attempts:

```bash
python scripts/burst.py --config scripts/burst-render-config.example.json --allow-remote
```

It uses the assignment bootstrap admin credentials `admin` / `admin123` by default. Override these with `ADMIN_IDENTIFIER` / `ADMIN_PASSWORD` or the corresponding CLI options if they differ. The command prints its resolved workload before making requests; add `--dry-run` to preview it without sending requests.

The script removes temporary users, but it leaves its generated shows and reservations in the database. Remote targets require `--allow-remote`; remote runs above 1,000 hot-seat attempts also require `--allow-high-load`. Review [burst and Docker details](docs/Docker.md) before changing the workload size.

## API and design notes

- [API contract](docs/ApiContract.md)
- [Database tables](docs/DatabaseTables.md)
- [MVP delivery plan and verification results](docs/MVPDeliveryPlan.md)
- [Design and operational write-up](WRITEUP.md)
- [Render setup](docs/RenderDeployment.md)

## Commit history

The repository retains the incremental implementation history. Inspect it with:

```bash
git log --oneline --decorate
```
