package com.yourname.aiprep.controller;

import com.yourname.aiprep.model.CourseGuide;
import com.yourname.aiprep.model.GenerateCourseGuideRequest;
import com.yourname.aiprep.service.GroqService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class GroqController {

    private final GroqService grokService;

    public GroqController(GroqService grokService) {
        this.grokService = grokService;
    }

    @PostMapping("/course-guide")
    public CourseGuide createCourseGuide(@RequestBody GenerateCourseGuideRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
        }

        return grokService.generateCourseGuide(request.prompt().trim());
    }
}
