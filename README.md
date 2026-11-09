# Reason Station

## Run locally with Docker Compose

With Docker Desktop running in Linux containers mode, run from the repository root:

```powershell
docker compose up --build -d
```

Compose builds the Java 25 API using the existing multi-stage Dockerfile and starts
PostgreSQL and SeaweedFS. No host Java or Maven installation is required.
The API waits for its dependencies to become healthy before starting.

| Service | Local address |
|---------|---------------|
| API and website | http://localhost:8081 |
| Swagger UI | http://localhost:8081/swagger-ui.html |
| API liveness | http://localhost:8081/actuator/health/liveness |
| PostgreSQL | localhost:5432 (database `mydb`, user/password `postgres`) |
| S3-compatible storage | http://localhost:8333 |

Port 8081 avoids conflicting with an API running directly on the host at port 8080.
Inside Docker, the API connects to `postgres:5432` and `seaweedfs:8333`;
generated download URLs use `localhost:8333` so browsers can access them.
Local storage credentials are defined in `docker\s3.json` and Compose. These are
development-only credentials, not production secrets.

Compose disables AWS Secrets Manager lookup, and the default Spring profile
disables reCAPTCHA. Email verification still requires
Amazon SES credentials, and social login requires provider configuration; Compose
does not mount host AWS credentials or configure these external services.
The existing localhost-only `admin` restriction is unchanged: Docker-forwarded
requests may not appear as loopback addresses and therefore cannot use that account.

View logs or stop the stack:

```powershell
docker compose logs -f api
docker compose down
```

Database and storage data persist in named volumes across restarts and `down`.
Do not use `docker compose down -v` unless you intend to delete that data.
The storage bucket is created on the first upload; readiness can report unavailable
before the bucket exists, while liveness confirms the API is running.
