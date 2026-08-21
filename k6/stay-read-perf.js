// ============================================================================
// [성능 측정] 숙소 조회 웜 응답시간 분포 — 기준선(37774de) vs 후보 브랜치 비교용
// ============================================================================
//
// 이 스크립트가 하는 일:
//   50명의 가상 사용자(VU)가 30초 동안 쉬지 않고 "한 종류"의 조회만 반복 호출하며
//   응답시간 분포(p90 등)를 기록한다. TARGET 환경변수로 상세/목록 중 하나만 측정한다.
//
// ⚠️ 왜 한 실행에 한 엔드포인트만 호출하는가 (구버전과의 차이):
//   구버전은 한 VU 반복 안에서 상세→목록을 연달아 호출했다. 그러면 상세가 빨라질수록
//   목록 호출 빈도·전체 부하도 함께 달라져 목록이 독립 대조군이 되지 못한다.
//   그래서 TARGET=detail 과 TARGET=list 를 "서로 다른 실행"으로 분리한다.
//
// 실험 설계 (왜 목록도 재는가):
//   - 상세(detail): 후보 브랜치에서만 캐싱된다 → 기준선과 차이가 나야 함
//   - 목록(list)  : 두 코드 모두 캐싱 없음(대조군) → 기준선과 차이가 없어야 함
//   - "상세만 빨라지고 목록은 그대로"라면 개선이 캐시 덕분임을 교란 없이 보인다.
//
// 실행 방법 (ACTION_PLAN §6 — 대상별 5 pair 교차 순서):
//   각 (코드 × 대상)마다: 앱 기동 → 같은 워밍업 1회 실행(버림) → 웜 상태 30초 측정 1회
//   예)  TARGET=detail k6 run k6/stay-read-perf.js      # 상세 측정
//        TARGET=list   k6 run k6/stay-read-perf.js      # 목록 측정(대조군)
//   ※ 콜드/웜·코드·pair·순서를 결과에 반드시 함께 기록할 것
// ============================================================================

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// ── 환경변수 설정값 (기본값은 로컬 기준) ──
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const TARGET = (__ENV.TARGET || 'detail').toLowerCase(); // 'detail' | 'list'
// 상세 조회에 사용할 숙소 id 목록 — 시드에 존재하는 id여야 한다 (쉼표 구분)
const STAY_IDS = (__ENV.STAY_IDS || '47,48,49').split(',');
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '30s';
const LIST_SIZE = Number(__ENV.LIST_SIZE || 15);

if (TARGET !== 'detail' && TARGET !== 'list') {
  throw new Error(`TARGET must be 'detail' or 'list', got '${TARGET}'`);
}

export const options = {
  scenarios: {
    stay_read: {
      // constant-vus: 지정 VU가 duration 동안 쉬지 않고 반복 — 응답시간 분포(p90) 측정에 적합
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    // 목적은 "측정"이므로 p(90) 절대값은 단언하지 않는다(환경차로 오탐).
    // 서버 오류(5xx·타임아웃)만 1% 미만을 단언한다.
    http_req_failed: ['rate<0.01'],
  },
};

// 대상 엔드포인트의 응답시간만 별도 Trend로 집계 (ms 단위)
const detailDuration = new Trend('stay_detail_duration', true);
const listDuration = new Trend('stay_list_duration', true);

export default function () {
  if (TARGET === 'detail') {
    // 상세 조회 — 여러 id로 분산(한 id만 때리면 히트율 100%라 비현실적)
    const stayId = STAY_IDS[Math.floor(Math.random() * STAY_IDS.length)];
    const res = http.get(`${BASE_URL}/api/stays/${stayId}`);
    detailDuration.add(res.timings.duration);
    check(res, { '상세 조회 200': (r) => r.status === 200 });
  } else {
    // 목록 조회 — 비캐싱 대조군
    const res = http.get(`${BASE_URL}/api/stays?page=1&listSize=${LIST_SIZE}`);
    listDuration.add(res.timings.duration);
    check(res, { '목록 조회 200': (r) => r.status === 200 });
  }
}
