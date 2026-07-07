package com.sido.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.sido.backend.reservation.scheduler.PendingExpiryListener;

@Configuration
public class RedisConfig {

	@Bean
	public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}

	@Bean
	public RedisMessageListenerContainer redisMessageListenerContainer(
		RedisConnectionFactory connectionFactory,
		PendingExpiryListener pendingExpiryListener) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(pendingExpiryListener, new PatternTopic("__keyevent@0__:expired"));
		return container;
	}
}
