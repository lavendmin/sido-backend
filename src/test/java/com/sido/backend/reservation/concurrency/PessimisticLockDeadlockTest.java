package com.sido.backend.reservation.concurrency;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.sido.backend.stay.entity.Stay;
import com.sido.backend.stay.entity.StayAvailDate;
import com.sido.backend.stay.repository.StayAvailDateRepository;
import com.sido.backend.stay.repository.StayRepository;

/**
 * 비관적 락 데드락 재현 및 해결 테스트.
 *
 * [재현] ORDER BY 없이 반대 방향으로 잠금 → MySQL Deadlock(에러 1213)
 * [해결] ORDER BY availableDate ASC → 항상 같은 순서로 잠금 → 순환 대기 불가
 */
@SpringBootTest
class PessimisticLockDeadlockTest {

    @Autowired private DataSource dataSource;
    @Autowired private StayRepository stayRepository;
    @Autowired private StayAvailDateRepository stayAvailDateRepository;

    private Long stayId;

    @BeforeEach
    void setUp() {
        // 테스트용 숙소 (주소 유니크 제약 회피 위해 고유 주소 사용)
        Stay stay = Stay.builder()
                .isHomestay(Boolean.TRUE)
                .title("데드락 테스트 숙소")
                .address("deadlock-test-addr-" + System.currentTimeMillis())
                .detailAddress("101호")
                .capacity(5)
                .areaSize(30)
                .description("deadlock test")
                .build();
        stay = stayRepository.save(stay);
        stayId = stay.getId();

        // 오픈 날짜: 9/8, 9/9, 9/10
        stayAvailDateRepository.save(
                StayAvailDate.builder().stay(stay).availableDate(LocalDate.of(2026, 9, 8)).build());
        stayAvailDateRepository.save(
                StayAvailDate.builder().stay(stay).availableDate(LocalDate.of(2026, 9, 9)).build());
        stayAvailDateRepository.save(
                StayAvailDate.builder().stay(stay).availableDate(LocalDate.of(2026, 9, 10)).build());
    }

    @AfterEach
    void tearDown() {
        // Stay 삭제 시 FK cascade로 StayAvailDate도 함께 삭제
        stayRepository.deleteById(stayId);
    }

    /**
     * 반대 방향 잠금 순서로 데드락 재현.
     *
     * 이 시나리오는 ORDER BY 없는 findWithLockInRange에서
     * MySQL이 날짜 행을 임의 순서로 잠글 때 발생할 수 있는 상황을
     * CountDownLatch로 타이밍 제어해 강제 재현한 것.
     */
    @Test
    @DisplayName("ORDER BY 없을 때 반대 잠금 순서 → 데드락 발생(에러 1213)")
    void deadlock_발생확인_역방향_잠금순서() throws InterruptedException {
        CountDownLatch t1Locked99  = new CountDownLatch(1);
        CountDownLatch t2Locked910 = new CountDownLatch(1);
        AtomicBoolean deadlockDetected = new AtomicBoolean(false);

        // T1: 9/9 먼저 잠금 → 9/10 잠금 시도 (T2가 9/10 보유 중)
        Thread t1 = new Thread(() -> {
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(false);
                lockSingleDate(con, stayId, "2026-09-09");      // 9/9 잠금 획득
                t1Locked99.countDown();                           // T2에게 신호
                t2Locked910.await(5, TimeUnit.SECONDS);           // T2가 9/10 잠글 때까지 대기

                lockSingleDate(con, stayId, "2026-09-10");       // T2 보유 → 순환 대기 → 데드락
                con.commit();
            } catch (SQLException e) {
                if (e.getErrorCode() == 1213) {                   // ER_LOCK_DEADLOCK
                    deadlockDetected.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "TX-A");

        // T2: T1이 9/9 잠그면 → 9/10 잠금 → 9/9 잠금 시도 (T1이 9/9 보유 중)
        Thread t2 = new Thread(() -> {
            try (Connection con = dataSource.getConnection()) {
                con.setAutoCommit(false);
                t1Locked99.await(5, TimeUnit.SECONDS);            // T1이 9/9 잠글 때까지 대기

                lockSingleDate(con, stayId, "2026-09-10");        // 9/10 잠금 획득
                t2Locked910.countDown();                           // T1에게 신호

                lockSingleDate(con, stayId, "2026-09-09");        // T1 보유 → 순환 대기 → 데드락
                con.commit();
            } catch (SQLException e) {
                if (e.getErrorCode() == 1213) {                   // ER_LOCK_DEADLOCK
                    deadlockDetected.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "TX-B");

        t1.start();
        t2.start();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(deadlockDetected.get())
                .as("반대 방향 잠금 순서 → MySQL 데드락(에러 1213) 발생해야 한다")
                .isTrue();
    }

    private void lockSingleDate(Connection con, Long stayId, String date) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id FROM StayAvailDate WHERE stay = ? AND availableDate = ? FOR UPDATE")) {
            ps.setLong(1, stayId);
            ps.setString(2, date);
            ps.executeQuery();
        }
    }
}
