package com.yourname.aiprep.controller;

import com.yourname.aiprep.model.GenerateMockInterviewRequest;
import com.yourname.aiprep.model.MockInterviewSession;
import com.yourname.aiprep.model.ReviewAnswerRequest;
import com.yourname.aiprep.model.ReviewAnswerResponse;
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
public class MockInterviewController {

    private final GroqService groqService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public MockInterviewController(GroqService groqService) {
        this.groqService = groqService;
    }

    @PostMapping(path = "/mock-interview/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMockInterview(@RequestBody GenerateMockInterviewRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
        }

        SseEmitter emitter = new SseEmitter(0L);

        executor.submit(() -> {
            try {
                MockInterviewSession session = groqService.generateMockInterviewSessionWithProgress(
                    request.prompt().trim(),
                    message -> sendEvent(emitter, "progress", message)
                );
                sendEvent(emitter, "result", session);
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

    @PostMapping("/mock-interview/review")
    public ReviewAnswerResponse review(@RequestBody ReviewAnswerRequest request) {
        if (request == null || request.answer() == null || request.answer().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        return groqService.reviewMockAnswer(request);
    }

    @PostMapping("/mock-interview/ideal")
    public com.yourname.aiprep.model.IdealAnswerResponse ideal(
        @RequestBody ReviewAnswerRequest request
    ) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        return groqService.generateIdealAnswer(request);
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            emitter.completeWithError(ignored);
        }
    }
}
