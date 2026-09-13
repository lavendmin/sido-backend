// 같은 숙소·서로 다른 날짜 확정 경합.
// VU 100명이 각자 서로 다른 2박 구간(겹치지 않음)을 확정한다.
// - Stay 락 없음(A): 날짜 범위 락이 날짜별로 독립 → 병렬 처리
// - Stay 락 있음(B/C): 모든 확정이 같은 Stay 행 락에서 직렬화
// 확정은 모두 성공(200)해야 한다. 직렬화 여부가 지연·처리량으로 드러난다.
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter, Rate } from 'k6/metrics';

const confirmDuration = new Trend('confirm_duration', true); // 확정 요청만 (setup 생성 제외)
const confirmOk = new Counter('confirm_ok');
const confirmSuccess = new Rate('confirm_success'); // 확정 요청 전용 성공률 (로그인·생성 제외)

const VUS = Number(__ENV.VUS || 100);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const LOGIN_ID = __ENV.LOGIN_ID || 'user1';
const PASSWORD = __ENV.PASSWORD || 'Password123!';
const STAY_ID = __ENV.STAY_ID || '22';
const BASE_DATE = __ENV.BASE_DATE || '2026-10-01'; // 각 VU는 base + 2*idx 일부터 2박

export const options = {
  scenarios: {
    confirm_distinct: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],   // 전체 요청 중 5xx·타임아웃 (로그인·생성 포함)
    confirm_success: ['rate==1.0'],   // 시나리오 계약: 겹치지 않는 100건 확정은 전부 성공해야 함
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409, 410));

function jsonParams(cookie) {
  return { headers: { 'Content-Type': 'application/json', Cookie: cookie } };
}

function addDays(iso, days) {
  const d = new Date(iso + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/api/members/signin`,
    JSON.stringify({ loginId: LOGIN_ID, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginRes.status !== 200) fail(`login failed: ${loginRes.status} ${loginRes.body}`);
  const accessToken = loginRes.cookies.accessToken?.[0]?.value;
  if (!accessToken) fail('no accessToken cookie');
  const cookie = `accessToken=${accessToken}`;

  const items = [];
  for (let i = 0; i < VUS; i += 1) {
    const startDate = addDays(BASE_DATE, 2 * i);
    const endDate = addDays(BASE_DATE, 2 * i + 2);
    const createRes = http.post(
      `${BASE_URL}/api/stays/${STAY_ID}/reservations`,
      JSON.stringify({ startDate, endDate, personCnt: 2 }),
      jsonParams(cookie)
    );
    if (createRes.status !== 201) {
      fail(`create failed [${i}] (${startDate}~${endDate}): ${createRes.status} ${createRes.body}`);
    }
    items.push({ reservationId: createRes.json('reservationId'), startDate, endDate });
  }
  return { cookie, items };
}

export default function (data) {
  const item = data.items[exec.vu.idInTest - 1];
  const res = http.patch(
    `${BASE_URL}/api/reservations/${item.reservationId}/confirm`,
    JSON.stringify({ personCnt: 2, isFarm: false }),
    jsonParams(data.cookie)
  );
  confirmDuration.add(res.timings.duration);
  confirmSuccess.add(res.status === 200);
  if (res.status === 200) confirmOk.add(1);
  check(res, {
    'confirm 성공 (200)': (r) => r.status === 200,
    '5xx 아님': (r) => r.status < 500,
  });
}
