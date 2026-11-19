import { parseArgs } from 'node:util';

export const help = `Anonymous headless Chromium load test

  npm.cmd run load:10  -- [options]
  npm.cmd run load:100 -- [options]
  npm.cmd run load -- --users 1 --duration 10 --ramp 0 --think-min 0 --think-max 0

Options:
  --base-url URL       Default http://localhost:8081 (Compose); use :8080 for host Java
  --allow-remote       Required for ANY non-loopback target, including production
  --users N           Concurrent anonymous browser contexts, 1-100 (default 10)
  --duration N        Steady-load seconds after ramp-up, 1-3600 (default 60)
  --ramp N            Seconds over which users enter, 0-600 (default 10)
  --think-min N       Minimum pause between actions in seconds (default 1)
  --think-max N       Maximum pause between actions in seconds (default 3)
  --timeout N         Per-action timeout in seconds, 1-120 (default 30)
  --search TERM       Search/filter term with 3+ letters/digits (default reason)
  --max-p95 N         Optional maximum successful browser-step p95 in milliseconds
  --output DIR        Report parent directory (default results)
  --help              Show help without starting a browser
`;

export function parseConfig(args) {
  const { values } = parseArgs({
    args,
    options: {
      'base-url': { type: 'string', default: 'http://localhost:8080' },
      'allow-remote': { type: 'boolean', default: false },
      users: { type: 'string', default: '10' },
      duration: { type: 'string', default: '60' },
      ramp: { type: 'string', default: '10' },
      'think-min': { type: 'string', default: '1' },
      'think-max': { type: 'string', default: '3' },
      timeout: { type: 'string', default: '30' },
      search: { type: 'string', default: 'reason' },
      'max-p95': { type: 'string' },
      output: { type: 'string', default: 'results' },
      help: { type: 'boolean', default: false }
    }
  });
  if (values.help) return { help: true };

  function number(name, min, max, integer = false) {
    const value = Number(values[name]);
    if (!values[name]?.trim() || !Number.isFinite(value) || value < min || value > max
        || (integer && !Number.isInteger(value))) {
      throw new Error(`--${name} must be ${integer ? 'an integer' : 'a number'} from ${min} to ${max}`);
    }
    return value;
  }

  const url = new URL(values['base-url']);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('--base-url must be an HTTP(S) origin without credentials, path, query or fragment');
  }
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (!local && !values['allow-remote']) {
    throw new Error('Remote load requires --allow-remote. Only test systems you own or are authorized to test.');
  }
  const thinkMin = number('think-min', 0, 60);
  const thinkMax = number('think-max', 0, 60);
  if (thinkMax < thinkMin) throw new Error('--think-max must be >= --think-min');
  if ((values.search.match(/[\p{L}\p{N}]/gu) || []).length < 3 || values.search.length > 100) {
    throw new Error('--search must contain at least 3 letters/digits and be at most 100 characters');
  }
  if (!values.output.trim()) throw new Error('--output must not be empty');
  return {
    baseUrl: url.origin,
    allowRemote: values['allow-remote'],
    users: number('users', 1, 100, true),
    duration: number('duration', 1, 3600),
    ramp: number('ramp', 0, 600),
    thinkMin,
    thinkMax,
    timeout: number('timeout', 1, 120) * 1000,
    search: values.search,
    maxP95: values['max-p95'] === undefined ? null : number('max-p95', 1, 3600000),
    output: values.output
  };
}
