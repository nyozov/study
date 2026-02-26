package com.yourname.aiprep.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Configures a shared RestClient bean with explicit timeouts so a slow or
 * hung Groq response can't exhaust your thread pool.
 *
 * Place at: src/main/java/com/yourname/aiprep/config/RestClientConfig.java
 *
 * Then inject RestClient.Builder in GroqService instead of calling RestClient.create().
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient groqRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(30).toMillis());

        return RestClient.builder()
            .requestFactory(factory)
            .build();
    }
}