package com.yourname.aiprep.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate-limit.max-requests}")
    private int maxRequests;

    @Value("${rate-limit.window-seconds}")
    private long windowSeconds;

    public RateLimiterService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RateLimitStatus consume(String ip) {
        String key = "rate_limit:" + ip;

        Long count;
        try {
            count = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.commands().incr(key.getBytes())
            );
        } catch (DataAccessException ex) {
            // Fail open if Redis is temporarily unavailable.
            return new RateLimitStatus(true, maxRequests, maxRequests, windowSeconds);
        }

        if (count == null) {
            return new RateLimitStatus(true, maxRequests, maxRequests, windowSeconds);
        }

        if (count == 1) {
            // First request — set the expiry window
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        long resetSeconds = ttl != null && ttl > 0 ? ttl : windowSeconds;
        long remaining = Math.max(0, maxRequests - count);

        return new RateLimitStatus(count <= maxRequests, maxRequests, remaining, resetSeconds);
    }

    public record RateLimitStatus(
        boolean allowed,
        long limit,
        long remaining,
        long resetSeconds
    ) {}
}
