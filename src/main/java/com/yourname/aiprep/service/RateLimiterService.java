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

    @Value("${rate-limit.max-requests-per-minute}")
    private int maxRequestsPerMinute;

    @Value("${rate-limit.window-minute-seconds}")
    private long windowMinuteSeconds;

    @Value("${rate-limit.max-requests-per-day}")
    private int maxRequestsPerDay;

    @Value("${rate-limit.window-day-seconds:86400}")
    private long windowDaySeconds;

    public RateLimiterService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RateLimitStatus consume(String ip) {
        WindowStatus minute = consumeWindow("rate_limit:minute:" + ip, maxRequestsPerMinute, windowMinuteSeconds);
        WindowStatus day = consumeWindow("rate_limit:day:" + ip, maxRequestsPerDay, windowDaySeconds);

        boolean allowed = minute.allowed() && day.allowed();
        WindowStatus primary = pickPrimary(minute, day);

        return new RateLimitStatus(
            allowed,
            primary.limit(),
            primary.remaining(),
            primary.resetSeconds(),
            minute,
            day
        );
    }

    private WindowStatus consumeWindow(String key, int limit, long windowSeconds) {
        Long count;
        try {
            count = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.commands().incr(key.getBytes())
            );
        } catch (DataAccessException ex) {
            // Fail open if Redis is temporarily unavailable.
            return new WindowStatus(true, limit, limit, windowSeconds);
        }

        if (count == null) {
            return new WindowStatus(true, limit, limit, windowSeconds);
        }

        if (count == 1) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        long resetSeconds = ttl != null && ttl > 0 ? ttl : windowSeconds;
        long remaining = Math.max(0, limit - count);
        boolean allowed = count <= limit;

        return new WindowStatus(allowed, limit, remaining, resetSeconds);
    }

    private WindowStatus pickPrimary(WindowStatus... windows) {
        WindowStatus primary = windows[0];
        for (WindowStatus window : windows) {
            if (window.remaining() < primary.remaining()) {
                primary = window;
            } else if (window.remaining() == primary.remaining()
                && window.resetSeconds() < primary.resetSeconds()) {
                primary = window;
            }
        }
        return primary;
    }

    public record RateLimitStatus(
        boolean allowed,
        long limit,
        long remaining,
        long resetSeconds,
        WindowStatus minute,
        WindowStatus day
    ) {}

    public record WindowStatus(
        boolean allowed,
        long limit,
        long remaining,
        long resetSeconds
    ) {}
}
