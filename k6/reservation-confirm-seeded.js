// 동일 날짜 확정 경합 (회귀 확인).
// 사전에 SQL로 시드한 PENDING 예약 ID들(RES_IDS)을 VU들이 같은 날짜에 동시 확정한다.
// 같은 날짜 범위라 A(날짜 범위 락)·B(Stay 락) 모두 직렬화 → 1건 성공, 나머지 409(예약 불가).
// 이중예약(200이 2건 이상)·5xx가 없어야 한다.
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';

const confirmDuration = new Trend('confirm_duration', true);
const confirmOk = new Counter('confirm_ok');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const LOGIN_ID = __ENV.LOGIN_ID || 'user1';
const PASSWORD = __ENV.PASSWORD || 'Password123!';
const RES_IDS = (__ENV.RES_IDS || '').split(',').filter((s) => s.length > 0);
const VUS = RES_IDS.length;

export const options = {
  scenarios: {
    confirm_same_date: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 5xx·타임아웃 없어야 함
    confirm_ok: ['count==1'],       // 동일 날짜 경합: 정확히 1건만 확정 성공(이중예약 없음)
  },
};

http.setResponseCallback(http.expectedStatuses(200, 201, 409, 410));

function jsonParams(cookie) {
  return { headers: { 'Content-Type': 'application/json', Cookie: cookie } };
}

export function setup() {
  if (VUS === 0) fail('RES_IDS is empty');
  const loginRes = http.post(
    `${BASE_URL}/api/members/signin`,
    JSON.stringify({ loginId: LOGIN_ID, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginRes.status !== 200) fail(`login failed: ${loginRes.status}`);
  return { cookie: `accessToken=${loginRes.cookies.accessToken?.[0]?.value}` };
}

export default function (data) {
  const reservationId = RES_IDS[exec.vu.idInTest - 1];
  const res = http.patch(
    `${BASE_URL}/api/reservations/${reservationId}/confirm`,
    JSON.stringify({ personCnt: 2, isFarm: false }),
    jsonParams(data.cookie)
  );
  confirmDuration.add(res.timings.duration);
  if (res.status === 200) confirmOk.add(1);
  check(res, {
    'confirm 200 또는 409': (r) => r.status === 200 || r.status === 409,
    '5xx 아님': (r) => r.status < 500,
  });
}
