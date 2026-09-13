# 예약 확정 ↔ 예약가능일 변경/숙소 비활성화 경합 — 공통 Stay 행 잠금 검증

예약 확정과 운영자의 예약가능일 변경(`updateOpenDates`)·숙소 비활성화(`deleteStay`)가
경합할 때의 정합성을, 공통 Stay 행 잠금 도입 전(before) / 후(after)로 비교 측정한다.

- 대상 브랜치: `refactor/reservation-date-consistency`
- 기준선: `origin/develop` (`5f51f02`)
- 환경: MySQL 8.0 (REPEATABLE READ), Redis 7. 실 DB·Redis 통합 테스트.
- 방법: `@MockitoSpyBean`으로 운영자 경로가 Stay 행 잠금을 획득한 직후를 래치로 잡아, 그 창에서
  확정을 완주시켜 경합을 결정적으로 만든다. 래치는 타임아웃으로 재개해 교착을 막는다.
- 원자료: `raw/baseline-red-*.xml`(도입 전), `raw/postfix-green-*.xml`(도입 후) — JUnit 결과 XML(system-out 포함).

## 검증하는 규칙

- 충돌하는 확정과 날짜 닫기가 **모두 성공하지 않는다** — 공통 보호 구간을 먼저 확보한 쪽이 이긴다.
- 확정된 점유일(`ReservationDay`)은 반드시 예약 가능일(`StayAvailDate`)로 남아 있다.
- 확정된 예정 예약이 있으면 숙소 비활성화는 거절된다(기존 정책 유지).

## 테스트가 단언하는 것

'둘 다 성공 금지'만이 아니라 **올바른 승자와 패자의 정확한 도메인 예외**까지 단언한다(양쪽이 예기치 못한 오류로 실패해 통과하는 것을 방지).

- 운영자 선행(강제 인터리빙): 운영자 작업 성공 + 확정은 `ConflictException`(날짜 닫힘) / `ResourceGoneException`(숙소 비활성) 으로 거절 + 점유 행 없음.
- 확정 선행(순차): 확정 성공 + 이후 `updateOpenDates`는 `ConflictException`, `deleteStay`는 `hasUpcomingReservations=true`로 거절.
- 운영자 선행 트랜잭션 롤백: 운영자가 Stay 잠금을 쥔 뒤 롤백하면, 확정은 원래(그대로 열린) 상태를 보고 성공.
- 잠금 검증: `StayRepository.findByIdForUpdate`가 실제 `PESSIMISTIC_WRITE` 잠금을 획득하는지 별도 단언(경합 테스트가 잠금을 대체하므로 `@Lock` 회귀를 놓치지 않도록).

## 결과

### 도입 전 (baseline, RED)

두 트랜잭션이 모두 성공해 정합성이 깨진다.

| 시나리오 | 확정 | 운영자 작업 | 점유일(reserved) | 예약가능일(avail) | 판정 |
|---|---|---|---|---|---|
| 확정 ↔ 날짜 닫기 | 성공 (RESERVED) | `updateOpenDates` 성공 | `[d0, d1]` | `[]` | 위반 — 확정 점유일이 예약가능일에서 사라짐 |
| 확정 ↔ 숙소 비활성화 | 성공 (RESERVED) | `deleteStay` 성공(비활성) | `[d0, d1]` | `[]` | 위반 — 확정 예약이 비활성 숙소에 남음 |

원자료 `raw/baseline-red-*.xml`의 system-out:

```
결과: updateSucceeded=true, confirmSucceeded=true, status=RESERVED, avail=[], reserved=[2026-11-02, 2026-11-03]
결과: deleteResult=StayDeleteDTO[deleted=true, hasUpcomingReservations=false], confirmSucceeded=true, active=false, avail=[], reserved=[2026-11-02, 2026-11-03]
```

두 테스트 모두 "두 작업이 모두 성공하면 안 된다" 단언에서 실패(`Expecting value to be false but was true`).

### 도입 후 (after, GREEN)

먼저 Stay 행 잠금을 확보한 운영자 작업이 이기고, 확정은 최신 상태를 보고 거절된다. 고아 점유가 없다.

| 시나리오 | 확정 | 운영자 작업 | 점유일(reserved) | 예약가능일(avail) | 판정 |
|---|---|---|---|---|---|
| 확정 ↔ 날짜 닫기 | 거절 (PENDING 유지) | `updateOpenDates` 성공 | `[]` | `[]` | 정합 — 확정 점유일과 예약가능일이 어긋나지 않음 |
| 확정 ↔ 숙소 비활성화 | 거절 (PENDING 유지) | `deleteStay` 성공(비활성) | `[]` | `[]` | 정합 — 비활성 숙소에 확정 예약이 남지 않음 |

원자료 `raw/postfix-green-*.xml`의 system-out:

```
결과: updateSucceeded=true, confirmSucceeded=false, status=PENDING, avail=[], reserved=[]
결과: deleteResult=StayDeleteDTO[deleted=true, hasUpcomingReservations=false], confirmSucceeded=false, active=false, avail=[], reserved=[]
```

두 테스트 모두 통과. 3회 연속 안정 통과, 데드락·교착 없음.

> 강제한 인터리빙에서는 운영자 작업이 잠금을 먼저 확보하도록 래치가 순서를 고정하므로 운영자가 이긴다.
> 반대 순서(확정이 먼저 잠금 확보)에서는 확정이 이기고 운영자 작업이 거절되며, 두 경우 모두 규칙을 만족한다.

## 회귀

전체 테스트 스위트 45건 통과(실패·오류 0). 기존 수명주기·동시성·Redis·캐시 테스트 회귀 없음.
클래스별 결과는 `raw/full-suite-results/SUMMARY.md`와 같은 폴더의 JUnit XML 참조.

## 범위 밖

- 성능 실험(보호 규칙 도입 비용, 날짜 범위 잠금 제거/축소 효과의 A/B 측정)은 별도. 이번 변경은
  정확성 기준으로 날짜 범위 잠금을 유지한다.

## 재현

```bash
docker compose up -d                                    # MySQL 8.0, Redis 7
./gradlew test --tests "*ReservationVsDateChangeConcurrencyTest"   # after: 통과
```

도입 전 RED는 `refactor/reservation-date-consistency` 이전(공통 Stay 행 잠금 커밋 전) 상태에서 같은 명령으로 관찰한다.
