import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const VUS = Number(__ENV.VUS || 100);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const LOGIN_ID = __ENV.LOGIN_ID || 'user1';
const PASSWORD = __ENV.PASSWORD || 'Password123!';
const STAY_ID = __ENV.STAY_ID || '49';
const START_DATE = __ENV.START_DATE || '2026-09-09';
const END_DATE = __ENV.END_DATE || '2026-09-11';

// ── Redis 유무 A/B 비교용 E2E 시나리오 ──────────────────────────────
// 100 VU가 같은 stay·같은 날짜로 create → (201이면) 자기 예약을 confirm.
// 같은 스크립트를 두 브랜치에서 실행해 "경합 해소 지점"의 차이를 측정한다.
//
//   develop (Redis 없음):   create 100건 전부 성공 → confirm 100건이 비관적 락에서 경합 → 1건 승리
//   feat/redis-hold:        create 1건만 성공(99건 Redis fail-fast) → confirm은 경합 없이 1건
//
// 브랜치마다 달라지는 값(create 성공 수, confirm 도달 수)은 단언하지 않는다 — 그게 측정 대상.
// 공통 불변식만 단언한다: 최종 승자는 정확히 1명, 서버 오류 0.
// ────────────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    e2e_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 5xx·타임아웃 없어야 함
    confirm_success: ['count==1'],  // 어느 브랜치든 최종 확정은 정확히 1건
  },
};

// 200(로그인·confirm), 201(create), 409(차단/충돌) 모두 예상된 응답
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

// ── 브랜치 간 비교의 핵심 지표 ──
const createSuccess = new Counter('create_success');        // PENDING 생성 성공 수 (develop≈100 vs redis=1)
const createBlocked = new Counter('create_blocked');        // create 단계 409 (redis=99 vs develop=0)
const confirmSuccess = new Counter('confirm_success');      // 최종 확정 (양쪽 모두 1)
const confirmConflict = new Counter('confirm_conflict');    // confirm 단계 409 (develop≈99 vs redis=0)
const journeyDuration = new Trend('journey_duration', true);       // VU별 전체 여정 시간
const loserDecisionTime = new Trend('loser_decision_time', true);  // 탈락자가 "실패"를 확정적으로 안 시점까지의 시간
const winnerJourney = new Trend('winner_journey_duration', true);  // 승자의 create→confirm 완주 시간

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

  return { cookie: `accessToken=${accessToken}` };
}

export default function (data) {
  const t0 = Date.now();

  // 1단계: PENDING 생성 시도 (전원 같은 날짜 → 경합)
  const createRes = http.post(
    `${BASE_URL}/api/stays/${STAY_ID}/reservations`,
    JSON.stringify({
      startDate: START_DATE,
      endDate: END_DATE,
      personCnt: 2,
    }),
    jsonParams(data.cookie)
  );

  if (createRes.status === 409) {
    // Redis 브랜치에서만 발생 — 첫 요청에서 즉시 탈락 확정 (fail-fast)
    createBlocked.add(1);
    const elapsed = Date.now() - t0;
    journeyDuration.add(elapsed);
    loserDecisionTime.add(elapsed);
    check(createRes, { '여정 종료: create 단계 차단 (409)': () => true });
    return;
  }

  if (createRes.status !== 201) {
    fail(`unexpected create status=${createRes.status}, body=${createRes.body}`);
  }

  createSuccess.add(1);
  const reservationId = createRes.json('reservationId');

  // 2단계: 자기 예약 확정 시도 (develop에서는 여기서 100건이 락 경합)
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

  const elapsed = Date.now() - t0;
  journeyDuration.add(elapsed);

  if (confirmRes.status === 200) {
    confirmSuccess.add(1);
    winnerJourney.add(elapsed);
    check(confirmRes, { '여정 종료: 확정 성공 (200)': () => true });
  } else if (confirmRes.status === 409) {
    // develop에서만 발생 — 락 대기까지 겪은 뒤에야 탈락 확정
    confirmConflict.add(1);
    loserDecisionTime.add(elapsed);
    check(confirmRes, { '여정 종료: confirm 단계 충돌 (409)': () => true });
  } else {
    fail(`unexpected confirm status=${confirmRes.status}, body=${confirmRes.body}`);
  }
}
