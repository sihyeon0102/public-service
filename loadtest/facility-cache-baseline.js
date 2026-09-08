import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const endpoints = {
  A: {
    name: 'hot_seoul_library',
    path: '/api/facilities?region=SEOUL&type=LIBRARY&page=0&size=20',
    totalElements: 50000,
  },
  B: {
    name: 'hot_gwangju',
    path: '/api/facilities?region=GWANGJU&page=0&size=20',
    totalElements: 40000,
  },
};

const targetKey = (__ENV.TARGET || 'A').toUpperCase();
const target = endpoints[targetKey];
const mode = (__ENV.MODE || 'smoke').toLowerCase();
const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const thinkTimeSeconds = Number(__ENV.THINK_TIME || '0.2');
const summaryPath = __ENV.SUMMARY_PATH || '';

if (!target) {
  throw new Error(`Unknown TARGET '${targetKey}'. Use A or B.`);
}
if (!['smoke', 'warmup', 'baseline'].includes(mode)) {
  throw new Error(`Unknown MODE '${mode}'. Use smoke, warmup, or baseline.`);
}

const stageMetrics = {};
for (const vus of [5, 10, 20, 40]) {
  stageMetrics[vus] = {
    duration: new Trend(`hot_read_duration_vu_${vus}`, true),
    failed: new Rate(`hot_read_failed_vu_${vus}`),
    requests: new Counter(`hot_read_requests_vu_${vus}`),
  };
}

function baselineScenarios() {
  const scenarios = {};
  let startSeconds = 0;
  for (const vus of [5, 10, 20, 40]) {
    scenarios[`hot_read_${vus}`] = {
      executor: 'ramping-vus',
      exec: `load${vus}`,
      startTime: `${startSeconds}s`,
      startVUs: 0,
      stages: [
        { duration: '10s', target: vus },
        { duration: '40s', target: vus },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { endpoint: target.name, workload: 'hot_read', load_stage: String(vus) },
    };
    startSeconds += 55;
  }
  return scenarios;
}

function selectedScenarios() {
  if (mode === 'smoke') {
    return {
      smoke: {
        executor: 'constant-vus',
        exec: 'smoke',
        vus: 1,
        duration: '10s',
        gracefulStop: '5s',
        tags: { endpoint: target.name, workload: 'hot_read', load_stage: 'smoke' },
      },
    };
  }
  if (mode === 'warmup') {
    return {
      warmup: {
        executor: 'ramping-vus',
        exec: 'warmup',
        startVUs: 1,
        stages: [
          { duration: '5s', target: 3 },
          { duration: '20s', target: 3 },
          { duration: '5s', target: 0 },
        ],
        gracefulRampDown: '5s',
        tags: { endpoint: target.name, workload: 'hot_read', load_stage: 'warmup' },
      },
    };
  }
  return baselineScenarios();
}

export const options = {
  scenarios: selectedScenarios(),
  discardResponseBodies: false,
  systemTags: ['status', 'method', 'url', 'name', 'scenario', 'group', 'check', 'error', 'error_code'],
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function requestFacility(vus) {
  const response = http.get(`${baseUrl}${target.path}`, {
    tags: { endpoint: target.name, workload: 'hot_read', request_kind: 'facility_search' },
    timeout: '60s',
  });

  let body;
  try {
    body = response.json();
  } catch (_) {
    body = null;
  }

  const ok = check(response, {
    'status is 200': (r) => r.status === 200,
    'response is JSON': () => body !== null && typeof body === 'object',
    'content has 20 facilities': () => Array.isArray(body?.content) && body.content.length === 20,
    'totalElements matches dataset': () => body?.totalElements === target.totalElements,
    'page metadata is correct': () => body?.page === 0 && body?.size === 20,
  }, { endpoint: target.name, workload: 'hot_read' });

  if (vus) {
    stageMetrics[vus].duration.add(response.timings.duration, { endpoint: target.name });
    stageMetrics[vus].failed.add(!ok || response.status !== 200, { endpoint: target.name });
    stageMetrics[vus].requests.add(1, { endpoint: target.name });
  }
  sleep(thinkTimeSeconds);
}

export function setup() {
  const response = http.get(`${baseUrl}${target.path}`, {
    tags: { endpoint: target.name, workload: 'hot_read', request_kind: 'setup_validation' },
    timeout: '60s',
  });
  if (response.status !== 200) {
    exec.test.abort(`Setup failed: HTTP ${response.status} for ${target.path}`);
  }
  const body = response.json();
  if (!Array.isArray(body.content) || body.content.length !== 20
      || body.totalElements !== target.totalElements) {
    exec.test.abort(`Setup failed: response shape or totalElements differs for TARGET ${targetKey}`);
  }
  return { target: targetKey, endpoint: target.name, mode };
}

export function smoke() { requestFacility(null); }
export function warmup() { requestFacility(null); }
export function load5() { requestFacility(5); }
export function load10() { requestFacility(10); }
export function load20() { requestFacility(20); }
export function load40() { requestFacility(40); }

export function handleSummary(data) {
  if (!summaryPath) {
    return { stdout: JSON.stringify(data, null, 2) };
  }
  data.testMetadata = {
    target: targetKey,
    endpoint: target.name,
    workload: 'hot_read',
    mode,
    baseUrl,
    thinkTimeSeconds,
  };
  return { [summaryPath]: JSON.stringify(data, null, 2) };
}
