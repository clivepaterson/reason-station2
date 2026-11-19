# Anonymous browser load tests

Headless Playwright Chromium simulates **10 or 100 concurrent anonymous users**.
Each user gets a separate browser context, cookies and browser cache; users share
one Chromium process. No saved authentication state is loaded.

## Setup

Install Node.js 22+ and run from the repository root:

```powershell
Set-Location load-tests
npm.cmd ci
npm.cmd run install:browsers
```

On Linux CI hosts, install Chromium system dependencies with
`npx playwright install --with-deps chromium`.

## Local deployment

Start the application separately (`docker compose up --build -d` from the root).
The default target is Compose at `http://localhost:8080`:

```powershell
npm.cmd run load:10
npm.cmd run load:100
```

For Java running on the host, override the URL:

```powershell
npm.cmd run load:10 -- --base-url http://localhost:8080
```

A short one-user smoke run:

```powershell
npm.cmd run load -- --users 1 --duration 10 --ramp 0 --think-min 0 --think-max 0
```

## Production

Only run against a system you own or are authorized to load test. All non-loopback
targets require `--allow-remote`; no remote URL is selected automatically.
Replace the example origin if your canonical production hostname differs.
Schedule a quiet period, start with 10 users, monitor server/database health, and
stop if error rates or resource usage become unsafe. Do not run both profiles at
once unless you intend to combine their load.

```powershell
npm.cmd run load:10 -- --base-url https://reasonstation2.net --allow-remote --duration 120 --ramp 20
npm.cmd run load:100 -- --base-url https://reasonstation2.net --allow-remote --duration 120 --ramp 60
```

Production load is never started by installation or `npm.cmd test`.
Normal page image requests still incur bandwidth/storage costs. Browsing is
read-only from the user's perspective, but the application may populate caches,
write request logs, or lazily retrieve/persist legacy media metadata.

## Workload and timing

Each journey opens the homepage, waits for discovery feeds, clicks all five
catalogs in a rotated order, opens available track/patch detail dialogs, visits an
available artist, filters tracks, advances archive pagination when available,
searches, opens a tutorial, and returns home through in-page navigation.
Empty catalogs are valid; data-dependent skips are counted in the report.
For representative coverage, use a deployment with tracks, patches, artists and
enough archive entries for pagination. Nothing is seeded or changed by the harness.
There are no login, signup, rating, upload, media playback or download actions.
Normal scripts, styles and images load without request interception, preserving
browser caching behavior. Required third-party scripts (such as jQuery) need to
be reachable by the load generator.

Users enter evenly over `--ramp` seconds, then browse repeatedly for
`--duration` additional seconds. Each stays in its own context across journeys;
opening home starts a new document, while subsequent clicks use the real SPA.
The default randomized pause between steps is 1-3 seconds. Journeys already
started finish after the deadline, so elapsed time includes ramp-up and a drain
period. Reports aggregate all phases, not just steady state.
Ctrl+C stops new journeys and lets in-flight journeys finish before writing
an interrupted, failing report. Individual browser actions default to 30-second
timeouts. Run `npm.cmd run load -- --help` for all options.

## Results

Each run writes `results\<timestamp>-<users>users\summary.json` and `steps.csv`.
The console displays the completed/failed journey counts, peak users, and browser
step p50/p95/p99. JSON includes per-step latency, same-origin request timings and
status counts, skipped actions, and up to 100 error samples. Request paths omit
query strings so signed URLs are not recorded.

Browser-step timings include rendering and browser waits but exclude think time.
Request timings measure browser-observed request duration through response-body
completion, not server-only processing. Successful latency percentiles exclude
failed steps; failures are reported separately. Third-party/storage-origin request
timings and HTTP statuses are not included, although required resource failures
can still cause browser actions to fail.

Exit status is nonzero for journey/action failures, uncaught JavaScript errors,
same-origin HTTP 4xx/5xx or network errors, interrupted runs, no completed
journeys, or failure to reach requested peak concurrency. Only expected anonymous
`/api/me` 401/403 responses and intentionally aborted requests are exempt.
An optional latency budget also makes a run fail:

```powershell
npm.cmd run load:10 -- --duration 120 --max-p95 5000 --search ambient
```

`--max-p95` applies to aggregate successful browser steps, not HTTP requests.
No latency SLA is assumed by default.

100 real browser contexts can consume significant CPU and several GB of RAM.
Use a separate, adequately provisioned load generator for production, watch its
CPU/RAM/network, and do not interpret generator saturation as a server bottleneck.
Local runs compete with the application for resources. This measures browser
experience at the specified concurrency, not maximum HTTP requests per second.
