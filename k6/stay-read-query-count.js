// ============================================================================
// [자원 측정] 요청당 DB SELECT 수 — 캐시 히트가 쿼리를 실제로 줄이는지 직접 계측
// ============================================================================
//
// 이 스크립트가 하는 일:
//   VU 1명이 "한 종류"의 조회를 고정 횟수(기본 100회)만큼 순차 호출한다.
//   스크립트 자체는 SELECT 를 세지 않는다 — MySQL 전역 카운터 Com_select 를
//   실행 전/후에 읽어 (ΔCom_select ÷ 성공 요청 수)로 "요청당 SELECT"를 계산한다.
//
// ⚠️ 왜 1 VU · 고정 iteration 인가:
//   Com_select 는 서버 전역 누적값이라 동시 접속·스케줄러가 섞이면 오염된다.
//   동시성을 없애고(1 VU) 요청 수를 정확히 고정해, 오직 이 요청들이 만든 SELECT만 세도록 통제한다.
//
// 측정 절차 (ACTION_PLAN §5 — 오케스트레이션은 스크립트 밖에서 수행):
//   1) 앱 기동 + 시드 초기화, 스케줄러 최초 실행 종료 확인
//   2) 워밍업: 상세 1회 호출(캐시·JVM·DB 예열) — Com_select 시작값 읽기 전에 끝냄
//   3) MySQL:  SHOW GLOBAL STATUS LIKE 'Com_select'  → 시작값 기록
//   4) 이 스크립트 실행 (기본 100 iteration)
//   5) MySQL:  같은 쿼리 → 종료값 기록
//   6) 요청당 SELECT = (종료값 - 시작값) / 성공 요청 수
//   → 상세·목록 각각 3회 반복해 범위를 남긴다.
//
// 실행 예:
//   TARGET=detail STAY_ID=49 k6 run k6/stay-read-query-count.js
//   TARGET=list                k6 run k6/stay-read-query-count.js
// ============================================================================

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const TARGET = (__ENV.TARGET || 'detail').toLowerCase(); // 'detail' | 'list'
const STAY_ID = __ENV.STAY_ID || '49';                   // 상세 측정에 쓸 단일 유효 stay id
const ITERATIONS = Number(__ENV.ITERATIONS || 100);
const LIST_SIZE = Number(__ENV.LIST_SIZE || 15);

if (TARGET !== 'detail' && TARGET !== 'list') {
  throw new Error(`TARGET must be 'detail' or 'list', got '${TARGET}'`);
}

export const options = {
  scenarios: {
    query_count: {
      // per-vu-iterations: VU 1명이 정확히 ITERATIONS번만 실행 → 요청 수를 고정
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: ITERATIONS,
      maxDuration: '120s',
    },
  },
  thresholds: {
    // 모든 응답이 200이어야 요청당 SELECT 계산이 유효하다(오류 요청이 섞이면 분모 오염)
    http_req_failed: ['rate==0'],
    'checks': ['rate==1'],
  },
};

http.setResponseCallback(http.expectedStatuses(200));

const url =
  TARGET === 'detail'
    ? `${BASE_URL}/api/stays/${STAY_ID}`
    : `${BASE_URL}/api/stays?page=1&listSize=${LIST_SIZE}`;

export default function () {
  const res = http.get(url);
  check(res, { [`${TARGET} 200`]: (r) => r.status === 200 });
}
