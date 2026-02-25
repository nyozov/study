package com.yourname.aiprep.filter;

import java.io.IOException;

import com.yourname.aiprep.service.RateLimiterService;
import com.yourname.aiprep.service.RateLimiterService.RateLimitStatus;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(1) // Run this filter first
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String ip = getClientIp(request);

        RateLimitStatus status = rateLimiterService.consume(ip);
        response.setHeader("X-RateLimit-Limit", String.valueOf(status.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(status.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(status.resetSeconds()));
        response.setHeader("X-RateLimit-Minute-Limit", String.valueOf(status.minute().limit()));
        response.setHeader("X-RateLimit-Minute-Remaining", String.valueOf(status.minute().remaining()));
        response.setHeader("X-RateLimit-Minute-Reset", String.valueOf(status.minute().resetSeconds()));
        response.setHeader("X-RateLimit-Hour-Limit", String.valueOf(status.hour().limit()));
        response.setHeader("X-RateLimit-Hour-Remaining", String.valueOf(status.hour().remaining()));
        response.setHeader("X-RateLimit-Hour-Reset", String.valueOf(status.hour().resetSeconds()));
        response.setHeader("X-RateLimit-Day-Limit", String.valueOf(status.day().limit()));
        response.setHeader("X-RateLimit-Day-Remaining", String.valueOf(status.day().remaining()));
        response.setHeader("X-RateLimit-Day-Reset", String.valueOf(status.day().resetSeconds()));

        if (!status.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                {"error": "Too many requests. Please slow down."}
            """);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        // Handles proxies / load balancers
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
