# Deploy on Render

The repository includes a Render Blueprint at [`render.yaml`](../render.yaml). It creates a Docker web service in Singapore and checks `/actuator/health/readiness`. The app listens on Render's `PORT` environment variable (8080 locally).

## Before you deploy

The PostgreSQL password was pasted into chat. Rotate it in Render before using the database from the deployed service. Do not put database credentials, admin credentials, or JWT secrets in this repository.

## Setup

1. Commit and push the deployment configuration to the `main` branch.
2. In Render, choose **New + → Blueprint**, select this repository, and apply the Blueprint.
3. For `DATABASE_URL`, enter the JDBC URL using the database's **internal** hostname from Render's Connect panel:

   ```text
   jdbc:postgresql://<internal-host>:5432/paytmdb_0o5y
   ```

   Keep the web service and database in Singapore. Render services in the same region should use the internal database URL rather than the external `psql` host. The Blueprint separately prompts for `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `ADMIN_IDENTIFIER`, and `ADMIN_PASSWORD`; enter the database user and newly rotated password, then choose a strong admin password.

4. Render generates `JWT_SECRET` automatically. Wait for the deploy to become healthy.
5. Verify `https://<service-name>.onrender.com/actuator/health/readiness`, then test sign-in and the API using the admin identifier and password you supplied.

Liquibase applies the schema during application startup. The Render health check requires database readiness, so incorrect credentials or a database connection issue will keep the deployment unhealthy.
