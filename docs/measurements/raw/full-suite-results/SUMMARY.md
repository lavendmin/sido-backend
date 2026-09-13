# 전체 테스트 스위트 결과 (브랜치 refactor/reservation-date-consistency, 버전 B)

- 총 45개 테스트, 실패 0, 오류 0. 전부 통과.
- 실 MySQL 8.0, Redis 7 통합. 같은 폴더의 `*.xml` 원본 참조.

| 테스트 클래스 | tests | fail | error | skip |
|---|---|---|---|---|
| BackendApplicationTests | 1 | 0 | 0 | 0 |
| festival.repository.FestivalRepositoryTest | 3 | 0 | 0 | 0 |
| member.repository.MemberRepositoryTest | 2 | 0 | 0 | 0 |
| realestate.repository.RealEstatesRepositoryTest | 2 | 0 | 0 | 0 |
| reservation.concurrency.PessimisticLockDeadlockTest | 1 | 0 | 0 | 0 |
| reservation.concurrency.ReservationVsDateChangeConcurrencyTest | 6 | 0 | 0 | 0 |
| reservation.repository.ReservationRepositoryTest | 3 | 0 | 0 | 0 |
| reservation.service.DateHoldServiceRedisTest | 6 | 0 | 0 | 0 |
| reservation.service.PendingExpiryBoundaryTest | 3 | 0 | 0 | 0 |
| reservation.service.ReservationCreateCompensationTest | 1 | 0 | 0 | 0 |
| reservation.service.ReservationLifecycleConcurrencyTest | 4 | 0 | 0 | 0 |
| reservation.service.ReservationLifecycleIntegrationTest | 6 | 0 | 0 | 0 |
| stay.cache.StayDetailCacheEvictIsolationTest | 1 | 0 | 0 | 0 |
| stay.cache.StayDetailCacheInvalidationTest | 3 | 0 | 0 | 0 |
| stay.repository.StayRepositoryTest | 3 | 0 | 0 | 0 |
