package com.yourname.aiprep.controller;

import com.yourname.aiprep.model.ReviewAnswerRequest;
import com.yourname.aiprep.model.ReviewAnswerResponse;
import com.yourname.aiprep.service.GroqService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class MockInterviewController {

    private final GroqService grokService;

    public MockInterviewController(GroqService grokService) {
        this.grokService = grokService;
    }

    @PostMapping("/mock-interview/review")
    public ReviewAnswerResponse review(@RequestBody ReviewAnswerRequest request) {
        if (request == null || request.answer() == null || request.answer().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        return grokService.reviewMockAnswer(request);
    }

    @PostMapping("/mock-interview/ideal")
    public com.yourname.aiprep.model.IdealAnswerResponse ideal(
        @RequestBody ReviewAnswerRequest request
    ) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }

        return grokService.generateIdealAnswer(request);
    }
}
