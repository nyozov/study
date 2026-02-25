package com.yourname.aiprep.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:3000", "http://localhost:3001")
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders(
                "X-RateLimit-Limit",
                "X-RateLimit-Remaining",
                "X-RateLimit-Reset",
                "X-RateLimit-Minute-Limit",
                "X-RateLimit-Minute-Remaining",
                "X-RateLimit-Minute-Reset",
                "X-RateLimit-Hour-Limit",
                "X-RateLimit-Hour-Remaining",
                "X-RateLimit-Hour-Reset",
                "X-RateLimit-Day-Limit",
                "X-RateLimit-Day-Remaining",
                "X-RateLimit-Day-Reset"
            );
    }
}
