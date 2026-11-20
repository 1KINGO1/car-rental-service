import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const name = __ENV.SCENARIO || 'steady';
const definitions = {
  steady: { executor: 'constant-vus', vus: 10, duration: '45s', gracefulStop: '10s' },
  ramp: { executor: 'ramping-vus', startVUs: 1, stages: [
    { duration: '15s', target: 10 }, { duration: '15s', target: 30 },
    { duration: '15s', target: 60 }, { duration: '15s', target: 0 },
  ], gracefulRampDown: '10s', gracefulStop: '10s' },
  spike: { executor: 'ramping-vus', startVUs: 5, stages: [
    { duration: '10s', target: 5 }, { duration: '2s', target: 80 },
    { duration: '15s', target: 80 }, { duration: '3s', target: 5 },
    { duration: '20s', target: 5 },
  ], gracefulRampDown: '10s', gracefulStop: '10s' },
};
if (!definitions[name]) throw new Error(`Unknown SCENARIO: ${name}`);
const limit = { steady: 500, ramp: 1000, spike: 1500 }[name];
export const options = {
  scenarios: { [name]: definitions[name] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    contract_errors: ['rate==0'],
    checks: ['rate==1'],
    http_req_duration: [`p(95)<${limit}`],
    'http_req_duration{operation:unary}': [`p(95)<${limit}`],
    'http_req_duration{operation:list}': [`p(95)<${limit}`],
    'http_req_duration{operation:client_stream}': [`p(95)<${limit}`],
    'http_req_duration{operation:bidi}': [`p(95)<${limit}`],
    ...(name === 'spike' ? { 'http_req_duration{phase:recovery}': ['p(95)<500'] } : {}),
  },
};
const context = JSON.parse(open(__ENV.CONTEXT));
const errors = new Rate('contract_errors');
const items = [
  { requestId: 'a', vehicleId: context.vehicleIds[0], days: 2 },
  { requestId: 'b', vehicleId: context.vehicleIds[1], days: 3 },
];
import exec from 'k6/execution';
export default function () {
  const operation = ['unary', 'list', 'client_stream', 'bidi'][(__ITER + __VU) % 4];
  const elapsed = exec.instance.currentTestRunDuration / 1000;
  const phase = name === 'spike' ? (elapsed < 10 ? 'baseline' : elapsed < 30 ? 'spike' : 'recovery') : name;
  const params = { headers: { 'Content-Type': 'application/json' }, timeout: '10s', tags: { operation, phase } };
  let response;
  if (operation === 'unary') response = http.get(`${context.bookingUrl}/api/grpc/vehicles/${context.vehicleIds[0]}`, params);
  else if (operation === 'list') response = http.get(`${context.bookingUrl}/api/grpc/vehicles`, params);
  else response = http.post(`${context.bookingUrl}/api/grpc/quotes/${operation === 'bidi' ? 'bidi' : 'client-stream'}`, JSON.stringify(items), params);
  let valid = false;
  try {
    const body = response.json();
    const vehicle = (v, index) => v.id === context.vehicleIds[index] &&
      v.brand === ['Toyota', 'Skoda'][index] && v.model === ['Corolla', 'Octavia'][index] &&
      v.plate === ['CONTRACT100', 'CONTRACT200'][index] && Number(v.dailyRate) === [1200, 1500][index];
    if (operation === 'unary') valid = vehicle(body, 0);
    else if (operation === 'list') valid = body.length === 2 && context.vehicleIds.every((id, index) => body.some(v => v.id === id && vehicle(v, index)));
    else if (operation === 'client_stream') valid = body.itemCount === 2 && body.total === '6900.00' && body.currency === 'UAH';
    else valid = body.length === 2 && body.every((line, i) => line.requestId === items[i].requestId &&
      line.vehicleId === items[i].vehicleId && line.days === items[i].days &&
      line.amount === ['2400.00', '4500.00'][i] && line.currency === 'UAH');
  } catch (_) { valid = false; }
  check(response, { 'HTTP 200': r => r.status === 200, 'contract payload valid': () => valid });
  errors.add(response.status !== 200 || !valid);
  sleep(0.1);
}
export function handleSummary(data) {
  return { [__ENV.SUMMARY]: JSON.stringify(data, null, 2), stdout: `${name}: ${JSON.stringify(data.metrics.http_req_duration.values)}\n` };
}
