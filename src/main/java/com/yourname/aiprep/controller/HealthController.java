package com.yourname.aiprep.controller;


import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "up",
            "timestamp", Instant.now().toString()
        );
    }

    
}
