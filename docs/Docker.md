# Docker: Build and Run

Run these commands from the repository root. Docker Desktop (or Docker Engine with the Compose plugin) must be running.

## Build and start

Build the service image and start the app with PostgreSQL in the background:

```bash
docker compose up --build -d
```

Check container status and app health:

```bash
docker compose ps
curl http://localhost:8080/actuator/health/readiness
```

View app logs:

```bash
docker compose logs -f app
```

## Stop and rebuild

Stop containers while preserving the PostgreSQL data volume:

```bash
docker compose down
```

Rebuild and restart after code changes:

```bash
docker compose up --build -d
```

To remove the containers and database volume and start with an empty database:

```bash
docker compose down -v
docker compose up --build -d
```

The last command permanently deletes the local PostgreSQL data volume. Compose defaults are intended for local development. Set `POSTGRES_PASSWORD`, `JWT_SECRET`, `ADMIN_IDENTIFIER`, and `ADMIN_PASSWORD` through the environment before using a non-local deployment.

## Metrics and local burst run

Prometheus metrics are available at `http://localhost:8080/actuator/prometheus`. The service exposes reservation confirmations, declines by bounded reason, idempotent replays, cancellations, and a global available-seat gauge derived from PostgreSQL. Readiness includes the database check; liveness is independent of it.

Run the standard-library Python burst client from the repository root. It creates temporary buyers and two shows, runs a hot-seat storm, checks idempotency and the per-user limit, prints response/latency counts and inventory reconciliation, then deactivates the temporary buyers. It leaves the generated shows/reservations in the database.

```bash
python scripts/burst.py --base-url http://localhost:8080
```

The default run sends 20,000 hot-seat attempts using up to 200 workers and 200 buyer accounts. Use smaller settings for a quick local smoke run:

```bash
python scripts/burst.py --attempts 100 --workers 20 --clients 10
```

On PowerShell, the same commands work with `py` if `python` is not on `PATH`:

```powershell
py scripts/burst.py --base-url http://localhost:8080 --attempts 100 --workers 20 --clients 10
```
