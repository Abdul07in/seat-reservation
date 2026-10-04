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

Run the standard-library Python burst client from the repository root. Before making requests, it prints the resolved plan. It creates temporary buyers and test shows, runs selected workload stages, prints response/latency counts and inventory reconciliation, then deactivates the temporary buyers. It leaves generated shows and reservations in the database.

```bash
python scripts/burst.py --base-url http://localhost:8080
```

The default run sends 20,000 hot-seat attempts using up to 200 workers and 200 buyer accounts. Preview a run without contacting the service using `--dry-run`. Workload options can be passed on the command line or saved in a JSON file; command-line options override file values. See [`scripts/burst-config.example.json`](../scripts/burst-config.example.json) for the available settings.

```bash
python scripts/burst.py --attempts 100 --workers 20 --clients 10
```

Choose a stage with `--scenario all|hot-seat|idempotency|user-limit`. Other controls include ramp-up duration, per-request timeout, number of seats in the user-limit race, the show's per-user cap and price, generated email prefix/domain, and whether temporary users should be kept. For example:

```bash
python scripts/burst.py --scenario hot-seat --attempts 500 --workers 50 --clients 25 --ramp-up-seconds 10 --timeout-seconds 30 --dry-run
python scripts/burst.py --config scripts/burst-config.example.json --scenario user-limit
```

On PowerShell, the same commands work with `py` if `python` is not on `PATH`:

```powershell
py scripts/burst.py --base-url http://localhost:8080 --attempts 100 --workers 20 --clients 10
```
