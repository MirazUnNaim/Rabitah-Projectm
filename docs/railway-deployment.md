# Deploy Rabitah on Railway

1. Create a Railway project from this repository and add a PostgreSQL service.
2. Add a service using the repository root. Railway reads `railway.toml` and builds the Spring API using `Rabitah-Backend/Dockerfile`.
3. Add these variables to the API service:

   - `DATABASE_URL` — a JDBC URL: `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}`
   - `DATABASE_USERNAME=${{Postgres.PGUSER}}` and `DATABASE_PASSWORD=${{Postgres.PGPASSWORD}}`
   - `RABITAH_JWT_SECRET` — a long random secret
   - `RABITAH_SYSTEM_ADMIN_PASSWORD` — a unique administrator password
   - `RABITAH_STORAGE_PROVIDER=s3`
   - `RABITAH_S3_BUCKET`, `RABITAH_S3_REGION`, `RABITAH_S3_ENDPOINT`, `RABITAH_S3_ACCESS_KEY`, and `RABITAH_S3_SECRET_KEY`

   Any S3-compatible object store works (Amazon S3, Cloudflare R2, Backblaze B2). For R2, use its S3 endpoint and set the region to `auto`.

4. Generate a public Railway domain. The JavaFX client should use that HTTPS API URL when launched remotely; do not rely on local-network discovery outside campus.

5. Keep `RABITAH_STORAGE_PROVIDER=local` only for local development. Railway containers are ephemeral, so production profile photos and post media must use S3-compatible storage.

The health endpoint is `/actuator/health`. Flyway creates and upgrades the database on first startup.
