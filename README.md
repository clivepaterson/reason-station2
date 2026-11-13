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

## Track media uploads

The track upload form accepts an optional image (`.jpg`, `.jpeg`, `.png`, `.bmp`,
or `.gif`, up to 30 MB) and optional MP3 audio (up to 80 MB), alongside the required
Reason file (up to 80 MB). If multiple Reason files are selected, the same image
and MP3 are uploaded separately for each track. The existing SoundCloud/Audiomack
link remains independent and optional.

`POST /api/tracks` accepts optional multipart parts named `image` and `audio`.
File extensions and contents are checked before uploading. Media uses the
configured S3 bucket under `track-images/` and `track-audio/`; migration V12 adds
nullable `tracks.image_uri` and `tracks.audio_uri` columns containing the resulting
`s3://` references. Existing tracks keep null media references. Deleting a track
also deletes its uploaded media, and failed uploads clean up completed objects.
Track cards use the uploaded image when present, otherwise the music-style icon.
Images are served through `/api/tracks/{trackid}/image`, which redirects to a
short-lived signed URL without making the bucket public.
Tracks with uploaded MP3 audio also show a "Download MP3" link, served through
`/api/tracks/{trackid}/audio` using a signed download URL. The optional external
audio link remains visible independently.

Keep the bucket private, enable encryption at rest, use HTTPS in production,
and scope the application's S3 permissions to the required bucket/prefixes.
Enable S3 access logging, CloudTrail data events, and CloudWatch metrics, with
encrypted log destinations. Additional media incurs storage, request, and
potential data-transfer charges; see [S3 pricing](https://aws.amazon.com/s3/pricing/).

## Production health alerts

Deploy monitoring independently, without rebuilding or restarting ECS or PostgreSQL:

```powershell
.\infrastructure\deploy-monitoring.ps1 -Region ap-southeast-1
```

Use the deployed region and `-StackPrefix` if different from `reasonstation`.
The script discovers the ECS service, database, ALB, and both blue/green target
groups from the existing CloudFormation stacks. Full blue/green deployments also
deploy monitoring automatically.

Alerts and recovery notifications go to `alerts@reasonstation2.net` by default
(override with `-AlertEmail`). **Confirm the SNS subscription email** before
notifications can arrive; an already-confirmed, unchanged subscription stays active.

| Alarm | Trigger |
|-------|---------|
| Service unavailable | Fewer than one healthy target across both blue/green groups in 2 of 3 one-minute periods; missing health metrics count as zero |
| Application errors | Target HTTP 5xx above 5% of routed requests in 2 of 3 one-minute periods |
| Load balancer errors | ALB HTTP 5xx above 5% of routed requests plus ALB errors in 2 of 3 one-minute periods |

Readiness checks include PostgreSQL and S3, so an application that loses database
connectivity can raise the service-health alarm even when its container is running
and database capacity looks normal. Detection includes the ALB health-check delay
(three failed 30-second checks), metric publication, and alarm evaluation; it is
not an immediate page. HTTP error alarms need traffic and do not alarm on an idle
site. The ALB error denominator includes ALB errors because requests rejected
before a target is chosen are absent from `RequestCount`.

The health alarm combines both groups to avoid paging for the intentionally empty
blue/green group. During deployment overlap, a healthy non-production revision can
temporarily mask an unhealthy production revision; HTTP error alarms complement
this but these are not end-to-end DNS/TLS or browser checks.

Existing ECS CPU, PostgreSQL capacity, and database event notifications remain in
place. Monitoring uses native metrics without enabling Container Insights and
does not automatically redeploy workloads. These alerts detect symptoms; they do
not establish or fix the cause of a lost database connection.
