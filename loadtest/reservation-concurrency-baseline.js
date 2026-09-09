import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const programId = __ENV.PROGRAM_ID;
const runId = __ENV.RUN_ID || 'manual';
const vus = Number(__ENV.VUS || '200');
const summaryPath = __ENV.SUMMARY_PATH || '';

if (!programId) {
  throw new Error('PROGRAM_ID is required');
}
if (!Number.isInteger(vus) || vus <= 0) {
  throw new Error(`VUS must be a positive integer: ${__ENV.VUS}`);
}

const status201 = new Counter('reservation_status_201');
const status409 = new Counter('reservation_status_409');
const status5xx = new Counter('reservation_status_5xx');
const unexpectedStatus = new Counter('reservation_status_unexpected');
const unexpectedFailure = new Rate('reservation_unexpected_failure');
const reservationDuration = new Trend('reservation_request_duration', true);

export const options = {
  scenarios: {
    reservation_race: {
      executor: 'per-vu-iterations',
      vus,
      iterations: 1,
      maxDuration: '60s',
      gracefulStop: '0s',
      tags: {
        workload: 'reservation_concurrency_before',
        endpoint: 'create_reservation',
      },
    },
  },
  discardResponseBodies: false,
  systemTags: [
    'status', 'method', 'url', 'name', 'scenario', 'group', 'check',
    'error', 'error_code',
  ],
  thresholds: {
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const participantId = `k6-${runId}-${exec.vu.idInTest}`;
  const response = http.post(
    `${baseUrl}/api/programs/${programId}/reservations`,
    JSON.stringify({ participantId }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: {
        workload: 'reservation_concurrency_before',
        endpoint: 'create_reservation',
        request_kind: 'reservation',
      },
      timeout: '60s',
    },
  );

  const is201 = response.status === 201;
  const is409 = response.status === 409;
  const is5xx = response.status >= 500 && response.status <= 599;
  const isExpectedCategory = is201 || is409 || is5xx;

  if (is201) status201.add(1);
  else if (is409) status409.add(1);
  else if (is5xx) status5xx.add(1);
  else unexpectedStatus.add(1);

  reservationDuration.add(response.timings.duration);
  unexpectedFailure.add(is5xx || !isExpectedCategory);

  check(response, {
    'server returned a classified HTTP response': () => isExpectedCategory,
    'participant id is unique for this run': () => participantId.endsWith(`-${exec.vu.idInTest}`),
  });
}

export function handleSummary(data) {
  data.testMetadata = {
    workload: 'reservation_concurrency_before',
    baseUrl,
    programId: Number(programId),
    runId,
    vus,
    participantsAreUnique: true,
    iterationsPerVu: 1,
  };
  if (!summaryPath) {
    return { stdout: JSON.stringify(data, null, 2) };
  }
  return { [summaryPath]: JSON.stringify(data, null, 2) };
}
