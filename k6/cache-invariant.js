// ============================================================================
// [정합성 불변식] 숙소 수정·삭제 후 즉시 조회 시 낡은 캐시가 서빙되지 않는다
// ============================================================================
//
// 이 스크립트가 단언하는 것 (in-flight 조회가 없는 통제된 순차 계약):
//   쓰기(수정 또는 삭제) 응답이 끝난 "직후" 상세 조회를 하면, 반드시 최신 상태가 보인다.
//   (= 낡은 캐시가 서빙되는 stale 응답이 0건이다)
//
// MODE 환경변수로 두 경로를 나눠 검증한다:
//   MODE=edit   (기본) — 상세 GET(캐시 적재) → PATCH 수정 → 즉시 GET, 새 description 확인
//   MODE=delete        — 상세 GET(isDeleted=false 적재) → DELETE → 즉시 GET, isDeleted=true 확인
//
// ⚠️ 왜 1 VU 순차인가:
//   동시성 테스트가 아니라 "쓰기 → 즉시 조회" 순서 계약이 목적이므로 VU 1명이어야 한다.
//   동시 read/write 강한 일관성을 주장하는 것이 아니다(ACTION_PLAN 결정 F).
//
// ⚠️ 삭제 경로는 "서로 다른 전용 stay"를 iteration마다 하나씩 쓴다(같은 stay 반복 삭제 금지).
//   DELETE_STAY_IDS 는 로그인 host 소유이고 미래 예약이 없는 삭제 가능 stay여야 한다.
//   soft-delete 라 앱 재시작(ddl-auto=create) 시 시드가 원복되므로 뒷정리는 불필요.
//
// 수정·삭제 API는 ROLE_ADMIN 전용이라 호스트 계정으로 로그인한다.
// ============================================================================

import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

// ── 설정값 ──
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const MODE = (__ENV.MODE || 'edit').toLowerCase(); // 'edit' | 'delete'
const HOST_LOGIN_ID = __ENV.HOST_LOGIN_ID || 'host1';   // ROLE_ADMIN 계정
const HOST_PASSWORD = __ENV.HOST_PASSWORD || 'Password123!';

// edit: 하나의 stay를 반복 수정. delete: 전용 stay를 iteration마다 하나씩 삭제.
const STAY_ID = __ENV.STAY_ID || '49';
const DELETE_STAY_IDS = (__ENV.DELETE_STAY_IDS || '').split(',').filter(Boolean);
const EDIT_ITERATIONS = Number(__ENV.ITERATIONS || 5);

if (MODE !== 'edit' && MODE !== 'delete') {
  throw new Error(`MODE must be 'edit' or 'delete', got '${MODE}'`);
}
if (MODE === 'delete' && DELETE_STAY_IDS.length === 0) {
  throw new Error('MODE=delete 에는 DELETE_STAY_IDS(쉼표 구분, 전용 stay 5개 권장)가 필요하다');
}

const ITERATIONS = MODE === 'edit' ? EDIT_ITERATIONS : DELETE_STAY_IDS.length;

export const options = {
  scenarios: {
    cache_invariant: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: ITERATIONS,
      maxDuration: '60s',
    },
  },
  thresholds: {
    // [핵심] 낡은 캐시 서빙(stale)은 단 1건도 없어야 한다
    stale_read: ['count==0'],
    // [보조] 모든 반복에서 최신 상태가 보여야 한다 (전부 fresh)
    fresh_read: [`count==${ITERATIONS}`],
    // [공통] 서버 오류 0
    http_req_failed: ['rate==0'],
  },
};

// 로그인·조회·수정·삭제 모두 200만 정상으로 취급
http.setResponseCallback(http.expectedStatuses(200));

const staleRead = new Counter('stale_read'); // 쓰기 후에도 옛 상태가 보인 횟수 (0이어야 함)
const freshRead = new Counter('fresh_read'); // 쓰기 후 최신 상태가 보인 횟수

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
    headers: { 'Content-Type': 'application/json', Cookie: data.cookie },
  };
  if (MODE === 'edit') {
    editCycle(authHeaders);
  } else {
    deleteCycle(authHeaders, DELETE_STAY_IDS[__ITER]); // iteration마다 서로 다른 전용 stay
  }
}

// ── 수정 경로: GET(적재) → PATCH(마커로 변경) → 즉시 GET, 마커가 보여야 함 ──
function editCycle(authHeaders) {
  const before = http.get(`${BASE_URL}/api/stays/${STAY_ID}`, authHeaders);
  if (before.status !== 200) {
    fail(`사전 조회 실패: status=${before.status}`);
  }
  // 수정 API가 capacity·areaSize·description을 모두 요구하므로 현재 값을 읽어 되돌려 보낸다
  const capacity = before.json('capacity');
  const areaSize = before.json('areaSize');

  // 반복마다 고유 마커 → 이전 반복의 잔상과 절대 안 섞임
  const marker = `cache-invariant-${Date.now()}`;
  const editRes = http.patch(
    `${BASE_URL}/api/admin/stays/${STAY_ID}`,
    JSON.stringify({ capacity, areaSize, description: marker }),
    authHeaders
  );
  check(editRes, { '숙소 수정 200': (r) => r.status === 200 });

  const after = http.get(`${BASE_URL}/api/stays/${STAY_ID}`, authHeaders);
  const seen = after.json('description');
  recordFreshOrStale(seen === marker, `기대 description="${marker}" 실제="${seen}"`);
  check(after, { '수정 직후 새 description 반영': () => seen === marker });
}

// ── 삭제 경로: GET(isDeleted=false 적재) → DELETE → 즉시 GET, isDeleted=true 여야 함 ──
function deleteCycle(authHeaders, stayId) {
  const before = http.get(`${BASE_URL}/api/stays/${stayId}`, authHeaders);
  if (before.status !== 200) {
    fail(`삭제 대상 사전 조회 실패: stayId=${stayId} status=${before.status}`);
  }
  if (before.json('isDeleted') === true) {
    fail(`삭제 대상 stayId=${stayId} 가 이미 isDeleted=true (전용 stay가 아님)`);
  }

  const delRes = http.del(`${BASE_URL}/api/admin/stays/${stayId}`, null, authHeaders);
  check(delRes, {
    '숙소 삭제 200': (r) => r.status === 200,
    '삭제 처리됨 deleted=true': (r) => r.json('deleted') === true,
  });

  const after = http.get(`${BASE_URL}/api/stays/${stayId}`, authHeaders);
  const seenDeleted = after.json('isDeleted');
  recordFreshOrStale(seenDeleted === true, `stayId=${stayId} 기대 isDeleted=true 실제=${seenDeleted}`);
  check(after, { '삭제 직후 isDeleted=true 반영': () => seenDeleted === true });
}

function recordFreshOrStale(isFresh, detail) {
  if (isFresh) {
    freshRead.add(1);
  } else {
    staleRead.add(1);
    console.error(`[STALE] ${detail} — 캐시 무효화 실패`);
  }
}
