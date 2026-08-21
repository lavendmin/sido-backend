import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const VUS = Number(__ENV.VUS || 100);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const LOGIN_ID = __ENV.LOGIN_ID || 'user1';
const PASSWORD = __ENV.PASSWORD || 'Password123!';
const STAY_ID = __ENV.STAY_ID || '49';
const START_DATE = __ENV.START_DATE || '2026-09-09';
const END_DATE = __ENV.END_DATE || '2026-09-11';

// Phase 2 Step 9 — createReservation 단계 Redis SETNX 선점 검증
// 100 VU가 같은 stay/날짜로 동시에 PENDING 생성 시도
// 기대: 201 성공 1건 / 409 차단 99건 / DB PENDING 1건 (이중 PENDING 0건)
export const options = {
  scenarios: {
    pending_hold_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],           // 5xx·타임아웃 없어야 함
    reservation_created: [`count==1`],         // 성공은 정확히 1건
    hold_blocked: [`count==${VUS - 1}`],       // 나머지는 전부 Redis 차단
  },
};

// 200(로그인), 201(성공), 409(선점 차단) 모두 예상된 응답 — http_req_failed가 진짜 오류만 카운트
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const created = new Counter('reservation_created');
const blocked = new Counter('hold_blocked');

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

  return { cookie: `accessToken=${accessToken}` };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/stays/${STAY_ID}/reservations`,
    JSON.stringify({
      startDate: START_DATE,
      endDate: END_DATE,
      personCnt: 2,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        Cookie: data.cookie,
      },
    }
  );

  // 201/409는 상호 배타 — 해당 결과의 check만 수행해야 checks 지표가 "50% 실패"처럼 보이지 않는다.
  // 건수 판정은 위 thresholds(reservation_created/hold_blocked)가 담당.
  if (res.status === 201) {
    created.add(1);
    check(res, { 'PENDING 생성 성공 (201)': () => true });
  } else if (res.status === 409) {
    blocked.add(1);
    check(res, { 'Redis 선점 차단 (409, 안내 문구 포함)': (r) => r.body.includes('선택 중인 날짜') });
  } else {
    check(res, { [`예상 밖 상태 코드: ${res.status}`]: () => false });
  }
}
