export function distribution(samples) {
  if (!samples.length) return { count: 0, min: null, mean: null, p50: null, p95: null, p99: null, max: null };
  const sorted = [...samples].sort((a, b) => a - b);
  const round = value => Math.round(value * 100) / 100;
  const percentile = p => round(sorted[Math.ceil(sorted.length * p) - 1]);
  return {
    count: sorted.length,
    min: round(sorted[0]),
    mean: round(sorted.reduce((sum, value) => sum + value, 0) / sorted.length),
    p50: percentile(0.5),
    p95: percentile(0.95),
    p99: percentile(0.99),
    max: round(sorted.at(-1))
  };
}

export function expectedAnonymousResponse(path, status) {
  return path === '/api/me' && [401, 403].includes(status);
}

export class Metrics {
  steps = new Map();
  requests = new Map();
  failures = 0;
  errors = [];
  completedJourneys = 0;
  failedJourneys = 0;
  activeUsers = 0;
  peakUsers = 0;
  skipped = {};

  error(kind, message, user = null) {
    this.failures++;
    if (this.errors.length < 100) this.errors.push({ kind, user, message: String(message).slice(0, 2000) });
  }

  skip(name) {
    this.skipped[name] = (this.skipped[name] || 0) + 1;
  }

  async step(name, action) {
    const entry = this.steps.get(name) || { successes: [], failures: 0 };
    this.steps.set(name, entry);
    const start = performance.now();
    try {
      await action();
      entry.successes.push(performance.now() - start);
    } catch (error) {
      entry.failures++;
      throw new Error(`${name}: ${error.message}`, { cause: error });
    }
  }

  request(method, path, status, duration) {
    // Normalize detail IDs to keep the report useful across different datasets.
    const key = `${method} ${path.replace(/\/\d+(?=\/|$)/g, '/:id')}`;
    const entry = this.requests.get(key) || { statuses: {}, durations: [] };
    this.requests.set(key, entry);
    entry.statuses[status] = (entry.statuses[status] || 0) + 1;
    if (duration >= 0) entry.durations.push(duration);
  }

  report() {
    const samples = [...this.steps.values()].flatMap(entry => entry.successes);
    return {
      failures: this.failures,
      errorSamples: this.errors,
      completedJourneys: this.completedJourneys,
      failedJourneys: this.failedJourneys,
      peakUsers: this.peakUsers,
      skipped: this.skipped,
      browserStepMs: distribution(samples),
      steps: Object.fromEntries([...this.steps].map(([name, entry]) =>
        [name, { ...distribution(entry.successes), failures: entry.failures }])),
      requests: Object.fromEntries([...this.requests].map(([name, entry]) =>
        [name, { statuses: entry.statuses, durationMs: distribution(entry.durations) }]))
    };
  }
}
