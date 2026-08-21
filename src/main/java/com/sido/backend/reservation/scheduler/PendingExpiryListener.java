package com.sido.backend.reservation.scheduler;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알람 키 만료(Keyspace Notification) 즉시성 담당. 만료된 PENDING을 예약 행 잠금 아래 회수한다.
 * 실제 상태 전이·재검증·hold 해제는 PendingExpiryProcessor(항목별 트랜잭션)에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingExpiryListener implements MessageListener {

	private static final String ALARM_PREFIX = "reservation:expire:";

	private final PendingExpiryProcessor pendingExpiryProcessor;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String expiredKey = new String(message.getBody());

		if (!expiredKey.startsWith(ALARM_PREFIX)) {
			return;
		}

		try {
			Long reservationId = Long.parseLong(expiredKey.substring(ALARM_PREFIX.length()));
			pendingExpiryProcessor.expireIfStillPending(reservationId);
		} catch (Exception e) {
			log.error("PENDING 만료 처리 실패: key={}", expiredKey, e);
		}
	}
}
