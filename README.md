# Reason Station

## Anonymous browser load tests

Headless Playwright load tests support 10 and 100 concurrent anonymous users
against local or explicitly opted-in production targets. See
[load-tests/README.md](load-tests/README.md) for setup, commands, workload and reports.

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

## Production compute

`infrastructure\deploy-blue-green.ps1` defaults to one on-demand `FARGATE` task
with 0.25 vCPU and 1 GiB RAM, using ARM64.
This avoids Spot capacity reclamation, but one task is still not highly available.
Blue/green deployments temporarily run both revisions.

Production and local Docker Compose builds use the JVM-based Java 25 `Dockerfile`.

Deploy using the existing prerequisite AWS configuration and credentials:

```powershell
.\infrastructure\deploy-blue-green.ps1 -Region <aws-region>
```

The deployment script explicitly passes its capacity-provider selection to
CloudFormation, including when updating an existing Spot-based stack. Spot
remains available only through an explicit `-CapacityProvider FARGATE_SPOT`
override.

## Database connection and read-load controls

The production profile keeps at least one idle connection per application task,
with a maximum of ten connections. Override these using `DATABASE_MINIMUM_IDLE`
and `DATABASE_MAXIMUM_POOL_SIZE`. The maximum includes idle and active connections;
blue/green deployments temporarily run both revisions, so budget for the sum of
their pools, plus migrations and other database clients. Reducing the idle minimum
does not reduce the maximum connection demand under load.

Discovery feeds cache successful repository results per application instance:
random artists and tracks for 10 minutes (`app.discovery.random-cache-ttl`, default
`PT10M`), download and rating rankings for 1 hour (`app.discovery.ranked-cache-ttl`,
default `PT1H`), and newest tracks for 1 minute (`app.discovery.newest-cache-ttl`,
default `PT1M`). These replace the shared `app.discovery.cache-ttl` setting.
Concurrent requests for the
same feed share a single refresh. The browser also reuses successful discovery
responses for 30 seconds and shares requests already in flight. Feed changes can
therefore take up to the feed's server cache duration plus approximately 30 seconds
to appear in an already-open browser.
Signed image URLs are generated from the server's cached metadata when responding.

The sitemap caches completed XML for five minutes per application instance
(`app.sitemap.cache-ttl`, default `PT5M`) and reads only IDs and upload timestamps.
New or deleted entries can take up to five minutes to appear in the sitemap.
Cache refresh failures are surfaced to callers rather than cached as empty results.

Artist track and patch lists are independently paginated at 20 items per page.
`GET /api/discovery/artist-tracks?artistAlias=Artist&page=1` and
`GET /api/discovery/artist-patches?artistAlias=Artist&page=1` return objects with
`results`, `page`, `pageSize`, `totalElements`, and `totalPages`, rather than bare
arrays. Both default to page 1. The artist page provides separate Previous/Next
controls for each list.

Optional PostgreSQL query regression tests use `TEST_DATABASE_URL` pointing to a
test database. They create connection-local temporary tables and do not modify
application tables:

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://localhost:5432/testdb?user=testuser'
mvn "-Dtest=TrackQueryIntegrationTest,PatchQueryIntegrationTest" test
```

## Browser asset caching

Static images under `/images/`, CSS, and JavaScript are cached for one day
(`Cache-Control: public, max-age=86400`). Local CSS and JavaScript references in
static HTML and server-rendered track/patch pages automatically include a content
hash in the filename. Changing an asset changes its URL on the next deployment,
so browsers fetch the new version immediately rather than waiting for expiry.
HTML is not stored in the browser cache, ensuring it picks up the latest URLs.
Unversioned static images can take up to one day to reflect replacements.

Uploaded track and patch images request one-day private browser caching through
their signed storage URLs, including images uploaded before this policy was added.
Signed URL expiry is unchanged; image redirect responses are not cached, so an
expired URL is not reused by the redirect endpoint. Different signed URLs have
separate browser cache entries. Download and other API caching policies are unchanged.

## Track media uploads

The track upload form accepts an optional image (`.jpg`, `.jpeg`, `.png`, `.bmp`,
or `.gif`, up to 30 MB) and optional MP3 audio (up to 25 MB), alongside the required
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

## Endpoint activity logs

Requests produce one single-line JSON `http_request` event on stdout, collected
by the existing ECS CloudWatch log driver in production (7-day retention).
Events include `timestamp`, `requestId`, `method`, `path`, `clientIp`, `status`,
and `durationMs`. Routine liveness/readiness checks are excluded. The server
generates an `X-Request-ID` response header and includes the same ID in application
log lines emitted during the request.

Only GET search/catalog query parameters are recorded: `query` and `page` on
`/api/discovery/search`; `filter`, `page`, `pageSize`, `sort`, and `direction` on
the five catalog listing endpoints; and `artistAlias` on the two discovery artist
listing endpoints. Values are limited to 256 characters and paths to 512.
Queries over 8,192 characters are omitted with `parametersOmitted=true`;
invalid percent encoding sets `malformedParameters=true`. JSON encoding prevents
search text from injecting additional log lines. Request bodies, cookies,
authorization headers, arbitrary query parameters, and path session IDs are not
logged.

Local requests ignore forwarded IP headers. Production trusts connection peers
only in the ALB subnet CIDRs `10.0.0.0/24,10.0.1.0/24`, and records the **last**
`X-Forwarded-For` address appended by the ALB, not client-supplied earlier entries
or the `Forwarded` header. This assumes the existing security-group restriction
(only the ALB can reach ECS) and ALB's default XFF **append** mode with client-port
preservation disabled. Keep those settings; update `REQUEST_LOGGING_TRUSTED_PROXIES`
if ALB subnets change. Do not configure broad public ranges or trust loopback for
public traffic. Invalid forwarded IPs fall back to the connection peer with
`invalidForwardedFor=true`, without DNS lookup.
Set `REQUEST_LOGGING_ENABLED=false` to disable activity logs.

After deploying the application, select its ECS log group in **CloudWatch Logs
Insights**, choose a short time range, and use these queries:

Top client IPs:

```text
filter event = "http_request"
| stats count(*) as requests by clientIp
| sort requests desc
| limit 50
```

Search history, including rejected searches:

```text
filter event = "http_request" and path = "/api/discovery/search"
| fields @timestamp, clientIp, parameters.query, parameters.page, status, durationMs, requestId
| sort @timestamp desc
| limit 200
```

Request bursts per IP:

```text
filter event = "http_request"
| stats count(*) as requests by bin(1m), clientIp
| sort requests desc
| limit 100
```

Repeated authentication failures and missing endpoints:

```text
filter event = "http_request" and status in [401, 403, 404]
| stats count(*) as failures by clientIp, path, status
| sort failures desc
| limit 100
```

Endpoints and response counts per client IP, labelled with known crawlers:

Paste this query directly into CloudWatch Logs Insights (Logs Insights QL).
It returns one row per `clientIp`, crawler label, endpoint `path`, and response
`status`, with a request count. Rows are grouped by IP, with its busiest endpoints
first; up to 10,000 rows are returned for the selected time range.

The hardcoded snapshot was fetched on **2026-11-19** from the official
[Googlebot](https://developers.google.com/static/search/apis/ipranges/googlebot.json),
[Bingbot](https://www.bing.com/toolbox/bingbot.json), and
[DuckDuckBot](https://duckduckgo.com/duckduckbot.json) lists. It covers all their
published IPv4 ranges at that time (170 Googlebot ranges, 29 Bingbot ranges, and
486 DuckDuckBot addresses), matching the current IPv4-only public ALB.
Adjacent Googlebot ranges are combined without adding addresses; the DuckDuckBot
regex matches only the listed individual IPs, not their hosting subnets.

```text
filter event = "http_request"
| fields case(
    isIpInSubnet(clientIp, "34.22.85.0/27")
    or isIpInSubnet(clientIp, "34.64.82.64/28")
    or isIpInSubnet(clientIp, "34.65.242.112/28")
    or isIpInSubnet(clientIp, "34.80.50.80/28")
    or isIpInSubnet(clientIp, "34.88.194.0/28")
    or isIpInSubnet(clientIp, "34.89.10.80/28")
    or isIpInSubnet(clientIp, "34.89.198.80/28")
    or isIpInSubnet(clientIp, "34.96.162.48/28")
    or isIpInSubnet(clientIp, "34.100.182.96/28")
    or isIpInSubnet(clientIp, "34.101.50.144/28")
    or isIpInSubnet(clientIp, "34.118.66.0/28")
    or isIpInSubnet(clientIp, "34.118.254.0/28")
    or isIpInSubnet(clientIp, "34.126.178.96/28")
    or isIpInSubnet(clientIp, "34.146.150.144/28")
    or isIpInSubnet(clientIp, "34.147.110.144/28")
    or isIpInSubnet(clientIp, "34.151.74.144/28")
    or isIpInSubnet(clientIp, "34.152.50.64/28")
    or isIpInSubnet(clientIp, "34.154.114.144/28")
    or isIpInSubnet(clientIp, "34.155.98.32/28")
    or isIpInSubnet(clientIp, "34.165.18.176/28")
    or isIpInSubnet(clientIp, "34.175.160.64/28")
    or isIpInSubnet(clientIp, "34.176.130.16/28")
    or isIpInSubnet(clientIp, "35.247.243.240/28")
    or isIpInSubnet(clientIp, "66.249.64.0/23")
    or isIpInSubnet(clientIp, "66.249.66.0/24")
    or isIpInSubnet(clientIp, "66.249.67.0/26")
    or isIpInSubnet(clientIp, "66.249.67.64/27")
    or isIpInSubnet(clientIp, "66.249.68.0/22")
    or isIpInSubnet(clientIp, "66.249.72.0/22")
    or isIpInSubnet(clientIp, "66.249.76.0/23")
    or isIpInSubnet(clientIp, "66.249.78.0/24")
    or isIpInSubnet(clientIp, "66.249.79.0/26")
    or isIpInSubnet(clientIp, "66.249.79.64/27")
    or isIpInSubnet(clientIp, "66.249.79.128/25")
    or isIpInSubnet(clientIp, "192.178.4.0/24")
    or isIpInSubnet(clientIp, "192.178.5.0/27")
    or isIpInSubnet(clientIp, "192.178.6.0/23"), "Googlebot",
    isIpInSubnet(clientIp, "13.66.139.0/24")
    or isIpInSubnet(clientIp, "13.66.144.0/24")
    or isIpInSubnet(clientIp, "13.67.10.16/28")
    or isIpInSubnet(clientIp, "13.69.66.240/28")
    or isIpInSubnet(clientIp, "13.71.172.224/28")
    or isIpInSubnet(clientIp, "20.15.133.160/27")
    or isIpInSubnet(clientIp, "20.36.108.32/28")
    or isIpInSubnet(clientIp, "20.43.120.16/28")
    or isIpInSubnet(clientIp, "20.74.197.0/28")
    or isIpInSubnet(clientIp, "20.79.107.240/28")
    or isIpInSubnet(clientIp, "20.125.163.80/28")
    or isIpInSubnet(clientIp, "40.77.139.0/25")
    or isIpInSubnet(clientIp, "40.77.167.0/24")
    or isIpInSubnet(clientIp, "40.77.177.0/24")
    or isIpInSubnet(clientIp, "40.77.178.0/23")
    or isIpInSubnet(clientIp, "40.77.188.0/22")
    or isIpInSubnet(clientIp, "40.77.202.0/24")
    or isIpInSubnet(clientIp, "40.79.131.208/28")
    or isIpInSubnet(clientIp, "40.79.186.176/28")
    or isIpInSubnet(clientIp, "51.105.67.0/28")
    or isIpInSubnet(clientIp, "52.130.224.112/28")
    or isIpInSubnet(clientIp, "52.167.144.0/24")
    or isIpInSubnet(clientIp, "52.231.148.0/28")
    or isIpInSubnet(clientIp, "65.55.210.0/24")
    or isIpInSubnet(clientIp, "139.217.52.0/28")
    or isIpInSubnet(clientIp, "157.55.39.0/24")
    or isIpInSubnet(clientIp, "191.233.204.224/28")
    or isIpInSubnet(clientIp, "199.30.24.0/23")
    or isIpInSubnet(clientIp, "207.46.13.0/24"), "Bingbot",
    clientIp like /^(4\.(144\.(148\.23|182\.50|227\.142|251\.39)|149\.(142\.7|159\.(71|102)|216\.220)|150\.142\.218|154\.193\.167|156\.(30\.123|136\.79|154\.107)|157\.77\.76|172\.49\.103|177\.16\.196|182\.(10\.198|131\.108)|195\.133\.120|201\.(125\.59|141\.71|197\.203|206\.133|220\.8)|207\.220\.92|209\.224\.56|213\.46\.14|226\.40\.135|228\.76\.163|237\.(155\.54|244\.80)|242\.119\.13|246\.10\.252|248\.234\.26|249\.(216\.104|223\.51|238\.11)|254\.123\.56|255\.(35\.121|39\.80))|13\.(86\.35\.212|89\.106\.77|90\.178\.139)|20\.(3\.(1\.178|60\.212)|8\.252\.26|9\.114\.242|12\.(141\.99|249\.123|252\.21)|13\.44\.19|15\.142\.178|23\.88\.231|29\.(149\.44|154\.56)|40\.(133\.240|147\.172)|43\.(150\.(85|93)|152\.28|172\.120)|44\.222\.1|49\.(129\.236|136\.28)|50\.(48\.(159|192)|49\.(0|25|40|55|237)|50\.(46|118|121|123|130|134|145|146|163)|168\.91)|53\.(78\.(106|123|138|144|236)|91\.2|92\.211|134\.160)|54\.224\.39|56\.197\.(58|63)|59\.0\.65|61\.(34\.40|142\.192)|62\.224\.44|64\.185\.45|69\.(74\.250|129\.205|131\.45|138\.(149|189)|144\.137|146\.171|147\.21|171\.204)|71\.(12\.143|69\.210)|72\.(197\.110|203\.250|218\.29|221\.(33|45)|225\.13|242\.(93|209))|73\.(132\.240|202\.147)|75\.(144\.152|254\.61)|77\.146\.108|79\.(226\.26|238\.198|239\.66)|80\.(114\.203|120\.41|129\.80|135\.7|156\.88)|81\.115\.13|82\.24\.207|84\.(138\.61|219\.45|251\.223)|85\.(138\.75|167\.56|187\.146)|88\.168\.65|93\.(28\.24|49\.96)|99\.(154\.113|255\.235)|100\.(136\.36|140\.155)|101\.17\.173|102\.34\.205|109\.(129\.162|130\.190|241\.18)|112\.58\.44|113\.(3\.121|14\.159)|115\.181\.187|118\.11\.251|119\.127\.146|121\.75\.32|166\.171\.150|170\.75\.54|175\.232\.228|185\.79\.(15|47)|190\.21\.172|191\.(44\.(16|22|119|234)|45\.212)|193\.(12\.126|24\.(10|251)|25\.197|27\.215|45\.113)|195\.(58\.189|108\.47)|197\.209\.(11|27)|198\.(132\.126|152\.208)|201\.15\.208|204\.(228\.200|240\.172|241\.148|242\.(19|101)|243\.55|246\.(81|254))|207\.(72\.(11|21|110|113)|97\.190|99\.197|107\.181|117\.66)|212\.(90\.107|139\.34)|216\.200\.223|219\.(43\.246|45\.(67|190))|221\.(10\.115|84\.185)|226\.133\.105|231\.254\.179|232\.(51\.46|249\.95)|236\.(40\.93|195\.196|198\.192|227\.113|233\.23|247\.180)|237\.1\.51|241\.(135\.19|147\.151|153\.203|164\.5|241\.100)|242\.(133\.53|135\.165|211\.139|215\.53|237\.240)|246\.(132\.168|225\.34)|247\.235\.11|250\.51\.113|252\.(9\.6|16\.193|41\.18|63\.162|88\.10)|253\.(59\.76|96\.199|114\.51))|40\.(64\.(67\.220|78\.45|88\.145|105\.247|106\.(11|152))|65\.78\.86|76\.(162\.(17|191|208|247)|163\.(7|23)|173\.151)|81\.250\.205|82\.218\.203|88\.(21\.235|195\.62)|89\.243\.175|114\.(182\.(45|153|172)|183\.(88|196|251))|119\.232\.(50|146|215|218|251)|121\.121\.54|127\.154\.196)|48\.(192\.(16\.86|74\.178)|200\.33\.227|202\.(155\.200|171\.124)|204\.32\.87|206\.18\.152|217\.(23\.236|129\.210|212\.89)|223\.209\.110)|51\.(8\.(31\.28|71\.117|253\.152)|104\.(144\.31|146\.(225|235)|160\.(167|177)|161\.32|162\.149|163\.250|164\.(109|147|189|215)|166\.111|167\.(19|52|54|61|71|87|88|95|96|104|110)|180\.(26|47|53))|107\.40\.209|116\.131\.221|120\.48\.122|138\.90\.(161|206|233)|143\.114\.45|145\.185\.16)|52\.(142\.(24\.149|26\.175)|143\.(95\.(162|204)|241\.111|242\.6|243\.117|244\.81|247\.235)|146\.(57\.51|58\.236|59\.(12|154|156)|63\.80)|148\.(161\.87|165\.38)|149\.(25\.43|28\.(18|83)|30\.45|56\.151|58\.(27|69|139|173)|60\.38|61\.51)|154\.(60\.82|169\.(50|200)|170\.(26|28|88|96|113|117|122|209|229|243)|171\.(0|44|70|87|150|196|205|235|250)|172\.2|204\.93)|185\.93\.208|186\.37\.211|188\.89\.106|189\.70\.103|190\.37\.160|191\.(82\.242|222\.158)|224\.(16\.(221|229)|19\.152|20\.(174|181|186|190|193|203|204|223|227|249)|21\.(4|19|20|23|27|49|51|53|55|61)|147\.21|191\.245)|242\.224\.168|250\.46\.221)|57\.(151\.44\.140|152\.(17\.237|62\.107|72\.128)|155\.18\.44|165\.205\.172|167\.88\.38)|64\.236\.(15\.33|93\.100|118\.43)|68\.219\.152\.220|72\.144\.95\.185|74\.(163\.(169\.106|208\.206)|179\.(200\.126|232\.116)|242\.194\.48)|98\.70\.219\.37|104\.43\.(54\.127|55\.(116|117|166|167))|108\.141\.83\.74|128\.203\.(82\.154|132\.152|181\.79|214\.241|236\.110)|130\.107\.228\.224|131\.(145\.39\.118|189\.183\.241)|132\.(164\.209\.198|196\.106\.146|220\.1\.71)|134\.33\.207\.189|135\.(171\.251\.169|233\.63\.237|234\.221\.112|235\.183\.95)|172\.(168\.(12\.93|43\.43|51\.216|53\.53|81\.217|94\.160|115\.250|133\.76|137\.102|152\.88|173\.230|190\.186|227\.120|254\.119)|169\.(7\.33|17\.165|28\.184|36\.43|53\.208|60\.134|93\.148|164\.109|177\.131|181\.237)|171\.37\.221|179\.(105\.87|128\.118)|188\.250\.220|189\.40\.18|193\.(184\.42|197\.146|245\.229)|194\.141\.49|199\.55\.212|210\.111\.68)|191\.(233\.3\.(197|202)|234\.216\.(4|178)|235\.(201\.214|202\.(38|48))))$/, "DuckDuckBot",
    "Unclassified"
  ) as crawler
| stats count(*) as requests by clientIp, crawler, path, status
| sort clientIp asc, requests desc
| limit 10000
```

Refresh these hardcoded values from the linked feeds periodically; they do not
update automatically. IPv6 addresses and other crawlers remain **Unclassified**,
which does not imply human or malicious activity. Labels indicate a match to the
snapshot, not a guarantee of current behaviour or historical IP ownership.

IP addresses and search text can be personal data: restrict access to the log
group and retain them only as long as needed. More request logs incur CloudWatch
ingestion/storage/query charges. These logs provide visibility, not automatic
blocking or new alarms; they cover requests reaching the application, not requests
rejected by the ALB or direct requests to signed S3 URLs.

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
does not itself redeploy workloads. These alerts detect symptoms; they do
not establish or fix the cause of a lost database connection.

## Database password rotation

Production Fargate tasks receive `DATABASE_USERNAME` and `DATABASE_PASSWORD`
directly from the RDS-managed Secrets Manager secret. The task execution role,
not the application's runtime role, retrieves the current `username` and
`password` JSON keys when each task starts. The deployment script no longer reads
or copies these credentials into `/reasonstation/prod/application`; the JDBC URL
remains in that application secret. RDS password management and automatic
rotation remain enabled.

Full deployments with `infrastructure\deploy-blue-green.ps1` deploy rotation
automation after the ECS service and monitoring. To update only the automation
after those prerequisites have been deployed:

```powershell
.\infrastructure\deploy-database-rotation.ps1 -Region ap-southeast-1
```

Use `-StackPrefix` if different from `reasonstation`. The script refuses to enable
automation unless the ECS stack exposes the same database secret ARN as the
PostgreSQL stack. Running this script against the old ECS stack is not enough:
first deploy the updated task definition with direct secret injection. The first
full deployment also removes the copied database credentials from the current
application-secret version; historical secret versions are not purged.

Secrets Manager's native `Secret Label Updated` event for `AWSCURRENT`, restricted
to this database secret, triggers an ECS force-new-deployment through EventBridge,
a retry queue, and Lambda. This handles automatic rotation and manual changes of
the active version without a CloudTrail-dependent event rule or an image rebuild.
The automation never reads password values. New tasks resolve `AWSCURRENT` at
startup; existing tasks cannot refresh their injected environment variables.

Failed worker requests retry for approximately one day before moving to a
fourteen-day dead-letter queue. Conditional DynamoDB leases suppress completed
event retries while allowing later promotions of the same secret version. A
worker crash after ECS accepts the deployment but before completion is recorded
can still cause an extra deployment. No exactly-once guarantee is assumed.

Automation alarms use the existing monitoring email destination: a fifteen-minute
queue backlog, dead-letter messages, or exhausted EventBridge delivery attempts
raise alerts. The rotation stack exposes `DeadLetterQueueUrl` and
`WorkerFunctionName` for investigation; fix the underlying error before redriving
failed events to the work queue. Queues, their customer-managed encryption key,
and the deduplication table are retained on stack deletion and incur AWS charges.

If initial automation creation ends in `ROLLBACK_COMPLETE`, CloudFormation cannot
update that failed stack. After correcting its failure, delete only the failed
`<StackPrefix>-database-rotation` stack record in CloudFormation, wait for deletion
to finish, and rerun `deploy-database-rotation.ps1`. Do not delete the PostgreSQL
or ECS stacks. Retained queues, the encryption key, and the deduplication table
from the failed creation remain separate resources; recreating the stack does
not reclaim them, so account for their ongoing charges and review them separately
before cleanup.

Rotation recovery is asynchronous, not zero-downtime credential refresh: existing
tasks may fail new database connections until healthy replacements take over.
Retries and failure alerts cover automation errors; the service-health alarms
remain responsible for detecting an application that fails to recover. Confirm
the monitoring SNS email subscription so failure notifications can arrive.
