package com.yourname.aiprep.validation;

import com.yourname.aiprep.model.ReviewAnswerRequest;
import org.springframework.stereotype.Component;

/**
 * Validates and sanitizes inbound AI requests before they hit GroqService.
 * Call validate() in your controller before calling the service layer.
 *
 * Place at: src/main/java/com/yourname/aiprep/validation/RequestValidator.java
 */
@Component
public class RequestValidator {

    private static final int MAX_JOB_TITLE_CHARS = 200;
    private static final int MAX_QUESTION_CHARS  = 1_000;
    private static final int MAX_ANSWER_CHARS    = 5_000;
    private static final int MAX_JOB_DESC_CHARS  = 4_000;

    /**
     * Validates a ReviewAnswerRequest (used for both review and ideal-answer endpoints).
     * Throws IllegalArgumentException with a user-safe message on failure.
     */
    public ReviewAnswerRequest validateAndSanitize(ReviewAnswerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }

        String jobTitle = sanitize(request.jobTitle(), MAX_JOB_TITLE_CHARS, "Job title");
        String question = requireNonBlank(
            sanitize(request.question(), MAX_QUESTION_CHARS, "Question"), "Question");
        String answer   = sanitize(request.answer(), MAX_ANSWER_CHARS, "Answer");

        return new ReviewAnswerRequest(jobTitle, question, answer);
    }

    /**
     * Validates a free-text job description prompt (used for interview generation).
     */
    public String validateJobDescription(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Job description is required.");
        }
        return sanitize(prompt, MAX_JOB_DESC_CHARS, "Job description");
    }

    // -------------------------------------------------------------------------

    private String sanitize(String value, int maxChars, String fieldName) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.length() > maxChars) {
            throw new IllegalArgumentException(
                fieldName + " exceeds maximum length of " + maxChars + " characters.");
        }
        return trimmed;
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value;
    }
}