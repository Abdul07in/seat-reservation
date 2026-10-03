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
