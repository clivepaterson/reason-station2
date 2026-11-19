import test from 'node:test';
import assert from 'node:assert/strict';
import { parseConfig } from './config.mjs';
import { distribution, expectedAnonymousResponse, Metrics } from './metrics.mjs';

test('local defaults and both requested concurrency profiles', () => {
  assert.equal(parseConfig([]).baseUrl, 'http://localhost:8081');
  for (const users of [10, 100]) assert.equal(parseConfig(['--users', String(users)]).users, users);
  assert.equal(parseConfig(['--base-url', 'http://[::1]:8080']).allowRemote, false);
});

test('non-loopback targets require explicit permission', () => {
  for (const host of ['https://reasonstation2.com', 'https://localhost.example.com', 'http://192.168.1.2']) {
    assert.throws(() => parseConfig(['--base-url', host]), /--allow-remote/);
    assert.equal(parseConfig(['--base-url', host, '--allow-remote']).baseUrl, host);
  }
});

test('invalid inputs fail before launching browsers', () => {
  for (const args of [
    ['--users', '0'], ['--users', '101'], ['--users', '1.5'], ['--duration', 'NaN'],
    ['--duration', ''], ['--ramp', '-1'], ['--timeout', '0'], ['--max-p95', '0'],
    ['--think-min', '4', '--think-max', '1'], ['--search', 'ab'], ['--output', ''],
    ['--base-url', 'file:///'], ['--base-url', 'https://user:password@example.com'],
    ['--base-url', 'http://localhost:8081/path'], ['--base-url', 'http://localhost:8081?users=10']
  ]) assert.throws(() => parseConfig(args), undefined, args.join(' '));
});

test('percentiles use nearest rank and preserve the samples', () => {
  const samples = [100, 10, 50, 20];
  assert.deepEqual(distribution(samples), { count: 4, min: 10, mean: 45, p50: 20, p95: 100, p99: 100, max: 100 });
  assert.deepEqual(samples, [100, 10, 50, 20]);
  assert.equal(distribution([]).p95, null);
});

test('only anonymous auth probes are exempted from HTTP failures', () => {
  assert.equal(expectedAnonymousResponse('/api/me', 401), true);
  assert.equal(expectedAnonymousResponse('/api/me', 403), true);
  assert.equal(expectedAnonymousResponse('/api/me', 500), false);
  assert.equal(expectedAnonymousResponse('/api/catalog/tracks', 403), false);
});

test('failed browser steps are not included in successful latency percentiles', async () => {
  const metrics = new Metrics();
  await metrics.step('home', async () => {});
  await assert.rejects(metrics.step('home', async () => { throw new Error('offline'); }), /home: offline/);
  metrics.request('GET', '/api/tracks/123/image', 200, 15);
  const report = metrics.report();
  assert.equal(report.steps.home.count, 1);
  assert.equal(report.steps.home.failures, 1);
  assert.equal(report.requests['GET /api/tracks/:id/image'].durationMs.p95, 15);
});
