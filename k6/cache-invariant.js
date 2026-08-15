// ============================================================================
// [안전 불변식 ①] 숙소 수정 시 캐시 무효화 검증 — feat/redis-cache 브랜치 전용
// ============================================================================
//
// 이 스크립트가 단언하는 것:
//   "숙소 정보를 수정한 직후 상세 조회를 하면, 반드시 수정된 내용이 보인다.
//    (= 낡은 캐시가 서빙되는 일이 0건이다)"
//
// 검증 흐름 (매 반복마다):
//   1. GET  상세 조회        → 캐시에 적재됨 (이 시점의 description을 기억)
//   2. PATCH 숙소 수정       → description을 고유한 마커 문자열로 변경
//                              (@CacheEvict가 캐시를 지워야 함)
//   3. GET  상세 조회 (즉시) → 응답의 description이 마커와 같아야 함
//      - 같으면  → fresh_read 카운트 (정상: 무효화 동작)
//      - 다르면  → stale_read 카운트 (버그: 낡은 캐시가 서빙됨)
//
// [안전 불변식 ②]는 별도 스크립트가 필요 없다:
//   기존 k6/pending-hold-race.js를 이 브랜치에서 재실행 →
//   1건 성공/99건 차단/이중 PENDING 0건 임계값이 그대로 통과하면
//   "캐싱 도입이 Phase 2 동시성 보장을 훼손하지 않음"이 회귀 검증된다.
//
// 주의:
//   - 이 스크립트는 stay 데이터의 description을 실제로 바꾼다.
//     앱 재시작 시 ddl-auto=create가 시드 데이터를 원복하므로 뒷정리는 불필요.
//   - 수정 API는 ROLE_ADMIN 전용이라 호스트 계정으로 로그인한다.
// ============================================================================

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

// ── 설정값 ──
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const HOST_LOGIN_ID = __ENV.HOST_LOGIN_ID || 'host1';       // ROLE_ADMIN 계정
const HOST_PASSWORD = __ENV.HOST_PASSWORD || 'Password123!';
const STAY_ID = __ENV.STAY_ID || '49';                      // 검증에 사용할 숙소
const ITERATIONS = Number(__ENV.ITERATIONS || 5);           // 수정→조회 사이클 반복 횟수

export const options = {
  scenarios: {
    cache_invariant: {
      // VU 1명이 순차적으로 ITERATIONS번 반복 —
      // 동시성 테스트가 아니라 "수정 → 즉시 조회" 순서 보장이 목적이므로 1명이어야 한다
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: ITERATIONS,
      maxDuration: '60s',
    },
  },
  thresholds: {
    // [단언 ①-핵심] 낡은 캐시 서빙은 단 1건도 없어야 한다
    stale_read: ['count==0'],
    // [단언 ①-보조] 모든 반복에서 수정된 내용이 보여야 한다 (전부 fresh)
    fresh_read: [`count==${ITERATIONS}`],
    // [단언 공통] 서버 오류 0
    http_req_failed: ['rate==0'],
  },
};

// 200(조회·수정·로그인)만 정상 응답으로 취급 — 그 외는 전부 http_req_failed로 집계
http.setResponseCallback(http.expectedStatuses(200));

const staleRead = new Counter('stale_read'); // 수정 후에도 옛 데이터가 보인 횟수 (0이어야 함)
const freshRead = new Counter('fresh_read'); // 수정 후 새 데이터가 보인 횟수

// setup = 테스트 시작 전 1회 실행. 호스트 계정으로 로그인해 인증 쿠키를 확보한다
export function setup() {
  const loginRes = http.post(
    `${BASE_URL}/api/members/signin`,
    JSON.stringify({ loginId: HOST_LOGIN_ID, password: HOST_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (loginRes.status !== 200) {
    fail(`호스트 로그인 실패: status=${loginRes.status}, body=${loginRes.body}`);
  }

  const accessToken = loginRes.cookies.accessToken?.[0]?.value;
  if (!accessToken) {
    fail('로그인은 됐지만 accessToken 쿠키가 없음');
  }

  return { cookie: `accessToken=${accessToken}` };
}

export default function (data) {
  const authHeaders = {
    headers: {
      'Content-Type': 'application/json',
      Cookie: data.cookie,
    },
  };

  // ── 1단계: 상세 조회 → 캐시 적재 + 현재 capacity/areaSize 확보 ──
  // (수정 API가 capacity·areaSize·description을 모두 요구하므로 현재 값을 읽어서 되돌려 보낸다)
  const before = http.get(`${BASE_URL}/api/stays/${STAY_ID}`, authHeaders);
  if (before.status !== 200) {
    fail(`사전 조회 실패: status=${before.status}`);
  }
  const capacity = before.json('capacity');
  const areaSize = before.json('areaSize');

  // ── 2단계: 숙소 수정 — description을 이번 반복에서만 쓰는 고유 마커로 변경 ──
  // Date.now() = 현재 시각(ms)이라 반복마다 값이 달라짐 → 이전 반복의 잔상과 절대 안 섞임
  const marker = `cache-invariant-${Date.now()}`;
  const editRes = http.patch(
    `${BASE_URL}/api/admin/stays/${STAY_ID}`,
    // StayUpdateDTO가 @JsonUnwrapped라 {capacity, areaSize, description} 평평한 형태로 보낸다
    JSON.stringify({ capacity: capacity, areaSize: areaSize, description: marker }),
    authHeaders
  );
  check(editRes, {
    '숙소 수정 200': (r) => r.status === 200,
  });

  // ── 3단계: 수정 "직후" 상세 조회 — 캐시가 무효화됐다면 마커가 보여야 한다 ──
  const after = http.get(`${BASE_URL}/api/stays/${STAY_ID}`, authHeaders);
  const seenDescription = after.json('description');

  if (seenDescription === marker) {
    freshRead.add(1); // 정상: 수정이 즉시 반영됨 (evict 동작)
  } else {
    staleRead.add(1); // 버그: 낡은 캐시가 서빙됨
    console.error(
      `[STALE] 기대="${marker}" 실제="${seenDescription}" — 캐시 무효화 실패`
    );
  }

  check(after, {
    '수정 직후 조회에 새 description 반영': () => seenDescription === marker,
  });
}
