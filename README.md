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
Tracks with uploaded MP3 audio show a "Play MP3" link. Clicking it opens the
bottom-docked player and starts playback; further clicks while playing add tracks
to its playlist without interrupting the current track. The player shows track
artwork, artist, title, and style, with icon buttons for play/pause, previous track,
and next track, plus volume and separate Reason-project and MP3 download buttons.
The project button uses a reduced Reason icon and downloads the original
`track_uri` through `/api/tracks/{trackid}/download`; the MP3 button uses a music-note
icon. Both download buttons follow the currently selected track.
Previous and next are disabled
at the beginning and end of the playlist respectively.
A timeline shows elapsed and total time once the audio metadata
loads; click, drag, or use the arrow keys to seek without changing playback state.
Select a playlist entry to play it directly. Tracks advance
automatically, and closing the player stops playback and clears the playlist.
Each playlist entry has a remove button. Removing another entry does not interrupt
playback. Removing the current entry plays the next track (or the previous track if
it was last); removing the final entry stops playback and closes the player.
Playback continues during in-page navigation, but not across full page loads.
Track and patch links within the website open a card dialog without changing the
URL or interrupting playback or uploads. Close it with the close button, Escape,
or by clicking outside it. Shared `/tracks/{trackid}` and `/patches/{patchid}` URLs
and links opened in a new tab still load their standalone pages.
MP3 playback and downloads use `/api/tracks/{trackid}/audio` with a signed download
URL. The optional external audio link remains visible independently.

### Embedded MP3 player

Tracks with uploaded MP3 audio have a `</>` **Copy embed code** button on their
cards and detail pages. It copies an iframe pointing to
`/embed.html?track={trackid}` on this site's origin. If clipboard access is
unavailable or denied, the dialog provides the HTML for manual copying.

The desktop embed's top row shows track artwork, track metadata, the centered
text-only Reason Station logo-title, play/pause, volume, and download links in that
order. At iframe widths of 840 pixels or less, it automatically switches to a
phone-friendly layout: track details and right-aligned branding share the top row,
with touch-friendly controls beneath and no horizontal scrolling. The waveform and seek slider appear below.
Copied iframes use a compact 220-pixel height for both layouts. The player does
not autoplay and has no skip buttons, close button, or playlist. Only
`/embed.html` allows cross-site framing; other pages retain frame protection.
The public `/api/discovery/tracks/{trackid}` endpoint supplies the player metadata.

### Audio waveforms

New and replacement MP3s are processed on the server after the track is saved.
A bounded background worker downloads the MP3, decodes it with FFmpeg, and reduces
the audio to at most 1,200 amplitude peaks. Only successful results are stored as
JSON in the configured S3 bucket, alongside the audio object with the suffix
`.waveform-v1.json`. No database migration or public bucket access is required.
Replacing audio or deleting its track also deletes its waveform.
Failures deleting an optional waveform are logged without failing an already-saved
track change.

The player retrieves `/api/tracks/{trackid}/audio/waveform`, displays the waveform
with played progress, and supports click-to-seek. The existing timeline remains
available for keyboard seeking and while the waveform is loading or unavailable.
For existing MP3s, the first waveform request schedules processing automatically;
there is no startup-wide backfill. The endpoint returns `202` with `Retry-After`
while processing, and `503` if processing fails. Failures are logged and retried
on a later request after a five-minute cooldown; playback and uploads are unaffected.
The in-memory work queue is not durable: after a restart, missing waveforms are
generated when requested again.

The Docker runtime includes FFmpeg. When running Java directly, install FFmpeg
on the host and put it on `PATH`, or set `WAVEFORM_FFMPEG` to its executable path.
`WAVEFORM_TIMEOUT` defaults to `PT90S`. Processing is limited to one job at a time
with 32 queued jobs, 80 MB of MP3 input, and less than 64 MiB of decoded mono PCM.
Timeouts, invalid audio, and audio exceeding that decoded-size limit produce no
waveform. Temporary processing files are removed after each job.

Waveforms reuse the audio prefix's S3 read/write/delete permissions. Keep these
objects private and encrypted, and use HTTPS in production. Processing adds
server CPU, temporary disk use, an audio read and waveform write, and small
waveform reads/storage charges; no browser-side MP3 decoding is needed.

Migration V13 adds nullable `tracks.audio_filesize` (bytes). MP3 uploads and
replacements save this size alongside `audio_uri`; track-card API responses include
it as `audioFilesize`, avoiding per-card metadata requests when the size is known.
For older MP3s with no stored size, `/api/tracks/{trackid}/audio/metadata` reads S3
metadata once and persists the size for subsequent requests. The update only applies
if the audio URI is unchanged, so it cannot overwrite a replacement MP3's size.

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
