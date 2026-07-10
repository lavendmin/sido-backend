package com.sido.backend.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration
@EnableCaching
public class CacheConfig {

	// 캐시별 TTL 분리 — 수정 시 @CacheEvict가 주 경로, TTL은 evict 누락(우회 경로·버그)에 대비한 상한선
	@Bean
	public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
			.disableCachingNullValues()
			.serializeValuesWith(RedisSerializationContext.SerializationPair
				.fromSerializer(new GenericJackson2JsonRedisSerializer()));

		Map<String, RedisCacheConfiguration> configs = Map.of(
			"stay", base.entryTtl(Duration.ofMinutes(5)) // 숙소 상세 정적 정보
		);

		return RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
			.withInitialCacheConfigurations(configs)
			.build();
	}
}
