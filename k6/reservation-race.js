import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const VUS = Number(__ENV.VUS || 10000);
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
    http_req_duration: ['p(95)<1000'],
    checks: ['rate>0.95'],
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
  const reservationIds = [];

  for (let i = 0; i < VUS; i += 1) {
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

    reservationIds.push(createRes.json('reservationId'));
  }

  return { cookie, reservationIds };
}

export default function (data) {
  const reservationId = data.reservationIds[exec.vu.idInTest - 1];

  const confirmRes = http.patch(
    `${BASE_URL}/api/reservations/${reservationId}/confirm`,
    JSON.stringify({
      startDate: START_DATE,
      endDate: END_DATE,
      personCnt: 2,
      isFarm: false,
    }),
    jsonParams(data.cookie)
  );

  check(confirmRes, {
    'confirm returns 200 or 409': (res) => res.status === 200 || res.status === 409,
  });
}
