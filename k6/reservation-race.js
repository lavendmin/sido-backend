import http from 'k6/http';
import { check, fail } from 'k6';

const VUS = Number(__ENV.VUS || 100);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const LOGIN_ID = __ENV.LOGIN_ID || 'user1';
const PASSWORD = __ENV.PASSWORD || 'Password123!';
const STAY_ID = __ENV.STAY_ID || '49';
const START_DATE = __ENV.START_DATE || '2026-09-09';
const END_DATE = __ENV.END_DATE || '2026-09-11';

export const options = {
  scenarios: {
    confirm_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // 서버 자체가 죽지 않는지 확인용
    http_req_failed: ['rate<0.01'],
    // checks threshold 제거 — before 시나리오에서 200이 여러 번 나오는 것이 버그 재현 성공
  },
};

function jsonParams(cookie) {
  return {
    headers: {
      'Content-Type': 'application/json',
      Cookie: cookie,
    },
  };
}

export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/api/members/signin`,
    JSON.stringify({ loginId: LOGIN_ID, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (loginRes.status !== 200) {
    fail(`login failed: status=${loginRes.status}, body=${loginRes.body}`);
  }

  const accessToken = loginRes.cookies.accessToken?.[0]?.value;
  if (!accessToken) {
    fail('login succeeded, but accessToken cookie was not returned');
  }

  const cookie = `accessToken=${accessToken}`;

  const createRes = http.post(
    `${BASE_URL}/api/stays/${STAY_ID}/reservations`,
    JSON.stringify({
      startDate: START_DATE,
      endDate: END_DATE,
      personCnt: 2,
    }),
    jsonParams(cookie)
  );

  if (createRes.status !== 201) {
    fail(`reservation create failed: status=${createRes.status}, body=${createRes.body}`);
  }

  const reservationId = createRes.json('reservationId');
  return { cookie, reservationId };
}

export default function (data) {
  const confirmRes = http.patch(
    `${BASE_URL}/api/reservations/${data.reservationId}/confirm`,
    JSON.stringify({
      startDate: START_DATE,
      endDate: END_DATE,
      personCnt: 2,
      isFarm: false,
    }),
    jsonParams(data.cookie)
  );

  check(confirmRes, {
    'confirm 성공 (200)': (res) => res.status === 200,
    'confirm 차단 (409)': (res) => res.status === 409,
  });
}
