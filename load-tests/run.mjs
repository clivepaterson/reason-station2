import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { parseConfig, help } from './config.mjs';
import { Metrics, expectedAnonymousResponse } from './metrics.mjs';
import { journey } from './journey.mjs';

async function main() {
  const config = parseConfig(process.argv.slice(2));
  if (config.help) return console.log(help);
  const directory = resolve(config.output, new Date().toISOString().replace(/[:.]/g, '-') + `-${config.users}users`);
  await mkdir(directory, { recursive: true });
  console.log(`Target: ${config.baseUrl}; anonymous users: ${config.users}; ramp: ${config.ramp}s; hold: ${config.duration}s`);
  console.log(`Reports: ${directory}`);
  const metrics = new Metrics();
  const browser = await chromium.launch({ headless: true });
  const startedAt = new Date();
  const start = performance.now();
  const deadline = start + (config.ramp + config.duration) * 1000;
  let stopping = false;
  const stop = () => {
    stopping = true;
    console.log('Stopping after in-flight journeys finish...');
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);

  async function virtualUser(user) {
    const delay = config.users === 1 ? 0 : config.ramp * 1000 * user / (config.users - 1);
    await sleep(Math.max(0, start + delay - performance.now()));
    if (stopping || performance.now() >= deadline) {
      metrics.error('user-start', 'User did not start before the measurement deadline', user + 1);
      return;
    }
    let context;
    let closing = false;
    try {
      context = await browser.newContext({
        viewport: { width: 1440, height: 900 },
        acceptDownloads: false
      });
      context.setDefaultTimeout(config.timeout);
      context.setDefaultNavigationTimeout(config.timeout);
      const page = await context.newPage();
      const statuses = new WeakMap();
      page.on('pageerror', error => metrics.error('javascript', error.message, user + 1));
      page.on('response', response => {
        statuses.set(response.request(), response.status());
        const url = new URL(response.url());
        if (url.origin === config.baseUrl && response.status() >= 400
            && !expectedAnonymousResponse(url.pathname, response.status())) {
          metrics.error('http', `${response.status()} ${url.pathname}`, user + 1);
        }
      });
      page.on('requestfinished', request => {
        const url = new URL(request.url());
        if (url.origin !== config.baseUrl) return;
        const status = statuses.get(request);
        if (status !== undefined) {
          metrics.request(request.method(), url.pathname, status, request.timing().responseEnd);
        }
      });
      page.on('requestfailed', request => {
        if (closing || request.failure()?.errorText === 'net::ERR_ABORTED') return;
        if (new URL(request.url()).origin === config.baseUrl) {
          metrics.error('network', `${new URL(request.url()).pathname}: ${request.failure()?.errorText}`, user + 1);
        }
      });
      metrics.activeUsers++;
      metrics.peakUsers = Math.max(metrics.peakUsers, metrics.activeUsers);
      try {
        let iteration = 0;
        while (!stopping && performance.now() < deadline) {
          try {
            await journey(page, config, metrics, user, iteration++);
            metrics.completedJourneys++;
          } catch (error) {
            metrics.failedJourneys++;
            metrics.error('journey', error.message, user + 1);
            // A failing server must not turn the test into an unpaced retry storm.
            await sleep(1000);
          }
        }
        if (iteration === 0) {
          metrics.error('user-start', 'User had no time to start a journey; increase duration or ramp', user + 1);
        }
      } finally {
        metrics.activeUsers--;
      }
    } finally {
      closing = true;
      if (context) await context.close();
    }
  }

  try {
    const results = await Promise.allSettled(Array.from({ length: config.users }, (_, user) => virtualUser(user)));
    results.forEach((result, user) => {
      if (result.status === 'rejected') metrics.error('user', result.reason.message, user + 1);
    });
  } finally {
    await browser.close();
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
  }
  const elapsedSeconds = (performance.now() - start) / 1000;
  const report = metrics.report();
  const reasons = [];
  if (report.failures) reasons.push(`${report.failures} error(s)`);
  if (!report.completedJourneys) reasons.push('No journeys completed');
  if (report.peakUsers < config.users) reasons.push('Requested concurrency was not reached');
  if (stopping) reasons.push('Run interrupted');
  if (config.maxP95 !== null && report.browserStepMs.p95 > config.maxP95) {
    reasons.push(`Browser-step p95 exceeded ${config.maxP95} ms`);
  }
  const summary = {
    passed: reasons.length === 0,
    reasons,
    config,
    startedAt: startedAt.toISOString(),
    finishedAt: new Date().toISOString(),
    elapsedSeconds,
    journeysPerSecond: report.completedJourneys / elapsedSeconds,
    ...report
  };
  await writeFile(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
  const csv = ['step,successes,failures,min_ms,mean_ms,p50_ms,p95_ms,p99_ms,max_ms',
    ...Object.entries(report.steps).map(([name, value]) =>
      [name, value.count, value.failures, value.min, value.mean, value.p50, value.p95, value.p99, value.max].join(','))];
  await writeFile(join(directory, 'steps.csv'), csv.join('\n') + '\n');
  console.log(`${summary.passed ? 'PASS' : 'FAIL'}: ${report.completedJourneys} completed / ${report.failedJourneys} failed journeys; peak ${report.peakUsers} users`);
  console.log(`Browser steps: p50=${report.browserStepMs.p50}ms p95=${report.browserStepMs.p95}ms p99=${report.browserStepMs.p99}ms`);
  for (const reason of reasons) console.error(reason);
  for (const error of report.errorSamples.slice(0, 5)) console.error(`${error.kind}: ${error.message}`);
  process.exitCode = summary.passed ? 0 : 1;
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
