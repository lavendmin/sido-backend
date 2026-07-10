// ============================================================================
// [성능 목표] 숙소 조회 성능 측정 — develop vs feat/redis-cache 브랜치 비교용
// ============================================================================
//
// 이 스크립트가 하는 일:
//   여러 명의 가상 사용자(VU)가 30초 동안 쉬지 않고
//   ① 숙소 상세 조회(캐싱 대상)와 ② 숙소 목록 조회(캐싱 안 함 = 대조군)를
//   반복 호출하면서 각각의 응답시간을 따로 기록한다.
//
// 실험 설계 (왜 목록도 같이 재는가):
//   - 상세 조회는 feat/redis-cache에서만 캐싱된다 → 브랜치 간 차이가 나야 함
//   - 목록 조회는 두 브랜치 모두 캐싱이 없다 → 브랜치 간 차이가 없어야 함 (대조군)
//   - "상세만 빨라지고 목록은 그대로"라면, 개선이 캐시 덕분임을 교란 없이 증명
//
// 실행 방법 (두 브랜치에서 같은 스크립트를 실행):
//   1) develop 체크아웃 → 앱 재시작 → k6 run k6/stay-read-perf.js (1회차 = 워밍업, 버림)
//      → 곧바로 한 번 더 실행 (2회차 = 웜 상태 측정, 이 수치를 채택)
//   2) feat/redis-cache 체크아웃 → 앱 재시작 → 같은 방식으로 2회 실행
//   ※ 측정 조건(콜드/웜)을 결과에 반드시 함께 기록할 것
// ============================================================================

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

// ── 환경변수로 바꿀 수 있는 설정값들 (기본값은 로컬 기준) ──
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
// 상세 조회에 사용할 숙소 id 목록 — 시드 데이터에 존재하는 id여야 한다 (쉼표로 구분)
const STAY_IDS = (__ENV.STAY_IDS || '47,48,49').split(',');

export const options = {
  scenarios: {
    stay_read: {
      // constant-vus: 지정한 수의 VU가 duration 동안 쉬지 않고 반복 실행하는 방식.
      // (per-vu-iterations와 달리 "일정 시간 동안 계속 부하"를 거는 실행기 —
      //  응답시간 분포(p90 등)를 안정적으로 얻는 데 적합)
      executor: 'constant-vus',
      vus: 50,           // 동시 사용자 50명
      duration: '30s',   // 30초 동안 지속
    },
  },
  thresholds: {
    // [단언] 서버 오류(5xx·타임아웃)가 1% 미만이어야 테스트 통과.
    // 이 스크립트의 목적은 "측정"이라 p(90) 절대값은 단언하지 않는다 —
    // 절대값은 브랜치 간 비교표로 해석한다 (단언하면 환경 차이로 오탐 발생).
    http_req_failed: ['rate<0.01'],
  },
};

// ── 커스텀 지표: 엔드포인트별 응답시간을 따로 모은다 ──
// Trend = 여러 값의 분포(avg, p90, p95...)를 집계하는 지표 타입.
// 두 번째 인자 true = 시간(ms) 단위로 표시하라는 뜻.
const detailDuration = new Trend('stay_detail_duration', true); // 상세 조회 (캐싱 대상)
const listDuration = new Trend('stay_list_duration', true);    // 목록 조회 (대조군)

// default 함수 = 각 VU가 반복 실행하는 본문
export default function () {
  // ① 숙소 상세 조회 — STAY_IDS 중 하나를 무작위로 골라 호출
  //    (한 id만 때리면 캐시 히트율 100%라 비현실적, 여러 id로 분산)
  const stayId = STAY_IDS[Math.floor(Math.random() * STAY_IDS.length)];
  const detailRes = http.get(`${BASE_URL}/api/stays/${stayId}`);

  // 응답시간(res.timings.duration = 요청~응답 전체 시간 ms)을 상세 전용 지표에 기록
  detailDuration.add(detailRes.timings.duration);

  // check = 결과 검증 (threshold와 달리 실패해도 테스트를 멈추지 않고 집계만 됨)
  check(detailRes, {
    '상세 조회 200': (r) => r.status === 200,
  });

  // ② 숙소 목록 조회 — 캐싱하지 않은 대조군 엔드포인트
  const listRes = http.get(`${BASE_URL}/api/stays?page=1&listSize=15`);

  listDuration.add(listRes.timings.duration);

  check(listRes, {
    '목록 조회 200': (r) => r.status === 200,
  });
}
