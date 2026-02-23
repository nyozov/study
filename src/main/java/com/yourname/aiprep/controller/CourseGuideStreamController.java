package com.yourname.aiprep.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.aiprep.model.CourseGuide;
import com.yourname.aiprep.model.GenerateCourseGuideRequest;
import com.yourname.aiprep.service.GroqService;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class CourseGuideStreamController {

    private final GroqService grokService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public CourseGuideStreamController(GroqService grokService, ObjectMapper objectMapper) {
        this.grokService = grokService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/course-guide/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamCourseGuide(@RequestBody GenerateCourseGuideRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
        }

        SseEmitter emitter = new SseEmitter(0L);

        executor.submit(() -> {
            try {
                CourseGuide guide = grokService.generateCourseGuideWithProgress(
                    request.prompt().trim(),
                    message -> sendEvent(emitter, "progress", message)
                );
                String payload = objectMapper.writeValueAsString(guide);
                sendEvent(emitter, "result", payload);
                emitter.complete();
            } catch (Exception ex) {
                try {
                    sendEvent(emitter, "error", ex.getMessage());
                } finally {
                    emitter.completeWithError(ex);
                }
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            emitter.completeWithError(ignored);
        }
    }
}
