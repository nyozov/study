package com.yourname.aiprep.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.aiprep.model.IdealAnswerResponse;
import com.yourname.aiprep.model.MockInterviewSession;
import com.yourname.aiprep.model.ReviewAnswerRequest;
import com.yourname.aiprep.model.ReviewAnswerResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);

    private static final String PRIMARY_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";
    private static final String FALLBACK_MODEL = "groq/compound";
    private static final int MAX_PROMPT_CHARS = 4000;

    // Retry ladder: each entry is (strictJson, compactLevel, maxTokens, progressMessage)
    private record RetryConfig(boolean strict, int compactLevel, int maxTokens, String progressMessage) {}
    private static final List<RetryConfig> INTERVIEW_RETRY_LADDER = List.of(
        new RetryConfig(false, 0, 700, "Analyzing the role..."),
        new RetryConfig(true,  0, 650, "Retrying with stricter JSON..."),
        new RetryConfig(true,  1, 520, "Retrying with fewer questions..."),
        new RetryConfig(true,  2, 420, "Final retry with compact output...")
    );

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.url}")
    private String apiUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GroqService(ObjectMapper objectMapper) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public MockInterviewSession generateMockInterviewSession(String userPrompt) {
        return generateWithRetry(userPrompt, null);
    }

    public MockInterviewSession generateMockInterviewSessionWithProgress(
        String userPrompt,
        Consumer<String> progress
    ) {
        return generateWithRetry(userPrompt, progress);
    }

    public ReviewAnswerResponse reviewMockAnswer(ReviewAnswerRequest request) {
        String systemPrompt = """
            You are a technical interviewer. Review the candidate's answer and provide constructive feedback.
            Return ONLY valid JSON, no markdown, no explanation. Use this exact structure:
            {
              "summary": "string",
              "strengths": ["string", "string", "string"],
              "improvements": ["string", "string", "string"],
              "score": "string (0-10)"
            }
            Keep the summary to 2-4 sentences. Strengths and improvements should be concrete and actionable.
            """;

        String userContent = "Role: %s%nQuestion: %s%nAnswer: %s".formatted(
            nullSafe(request.jobTitle()),
            nullSafe(request.question()),
            nullSafe(request.answer())
        );

        String json = callAndExtractJson(systemPrompt, userContent, 0.3, null);
        try {
            return parseWithFallback(json, ReviewAnswerResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse review response. Raw: " + summarize(json), e);
        }
    }

    public IdealAnswerResponse generateIdealAnswer(ReviewAnswerRequest request) {
        String systemPrompt = """
            You are a senior interviewer. Provide an ideal, concise answer to the question.
            Return ONLY valid JSON, no markdown, no explanation. Use this exact structure:
            { "answer": "string" }
            Keep the answer under 180 words. Use clear, practical language.
            """;

        String userContent = "Role: %s%nQuestion: %s".formatted(
            nullSafe(request.jobTitle()),
            nullSafe(request.question())
        );

        String json = callAndExtractJson(systemPrompt, userContent, 0.2, 350);
        try {
            return parseIdealAnswer(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse ideal answer. Raw: " + summarize(json), e);
        }
    }

    // -------------------------------------------------------------------------
    // Core retry logic
    // -------------------------------------------------------------------------

    private MockInterviewSession generateWithRetry(String userPrompt, Consumer<String> progress) {
        String safePrompt = truncate(userPrompt, MAX_PROMPT_CHARS);
        IllegalStateException lastError = null;

        for (RetryConfig config : INTERVIEW_RETRY_LADDER) {
            notify(progress, config.progressMessage());
            try {
                return requestMockInterviewSession(
                    buildInterviewPrompt(config.strict(), config.compactLevel()),
                    safePrompt,
                    config.maxTokens()
                );
            } catch (IllegalStateException e) {
                log.warn("Interview generation attempt failed (strict={}, compact={}): {}",
                    config.strict(), config.compactLevel(), e.getMessage());
                lastError = e;
            }
        }
        throw lastError;
    }

    private MockInterviewSession requestMockInterviewSession(
        String systemPrompt,
        String userPrompt,
        int maxTokens
    ) {
        String json = callAndExtractJson(
            systemPrompt,
            "Job Description:\n" + userPrompt,
            0.4,
            maxTokens
        );

        try {
            return parseWithFallback(json, MockInterviewSession.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse mock interview session. Raw: " + summarize(json), e);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private String callAndExtractJson(
        String systemPrompt,
        String userContent,
        double temperature,
        Integer maxTokens
    ) {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userContent)
        );
        Map<?, ?> response = postChat(messages, temperature, maxTokens);
        String content = extractContent(response);
        return extractJsonObject(normalizeJson(content));
    }

    private Map<?, ?> postChat(
        List<Map<String, String>> messages,
        double temperature,
        Integer maxTokens
    ) {
        try {
            return postChatWithModel(PRIMARY_MODEL, messages, temperature, maxTokens);
        } catch (RestClientResponseException e) {
            if (isQuotaError(e)) {
                log.warn("Primary model quota exceeded, falling back to {}", FALLBACK_MODEL);
                return postChatWithModel(FALLBACK_MODEL, messages, temperature, maxTokens);
            }
            throw e;
        }
    }

    private Map<?, ?> postChatWithModel(
        String model,
        List<Map<String, String>> messages,
        double temperature,
        Integer maxTokens
    ) {
        var body = new java.util.HashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }

        return restClient.post()
            .uri(apiUrl)
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);
    }

    private boolean isQuotaError(RestClientResponseException e) {
        int status = e.getRawStatusCode();
        if (status == 429 || status == 402) return true;
        String body = e.getResponseBodyAsString();
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("rate_limit_exceeded")
            || lower.contains("insufficient_quota")
            || lower.contains("quota_exceeded");
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private String extractContent(Map<?, ?> response) {
        if (response == null) throw new IllegalStateException("Empty Groq response");

        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty())
            throw new IllegalStateException("Groq response missing choices");

        if (!(choices.get(0) instanceof Map<?, ?> choice))
            throw new IllegalStateException("Groq choice is not an object");

        if (!(choice.get("message") instanceof Map<?, ?> message))
            throw new IllegalStateException("Groq response missing message");

        if (!(message.get("content") instanceof String content))
            throw new IllegalStateException("Groq response content is not a string");

        return content;
    }

    private <T> T parseWithFallback(String json, Class<T> type) throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return lenientMapper().readValue(json, type);
        }
    }

    private IdealAnswerResponse parseIdealAnswer(String json) throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, IdealAnswerResponse.class);
        } catch (JsonProcessingException e) {
            JsonNode node = lenientMapper().readTree(json);
            String answer = coerceAnswer(node.has("answer") ? node.get("answer") : node);
            return new IdealAnswerResponse(answer);
        }
    }

    private ObjectMapper lenientMapper() {
        return objectMapper.copy()
            .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
    }

    private String coerceAnswer(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : node) {
                String text = coerceAnswer(item);
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(text.trim());
                }
            }
            return sb.toString().trim();
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder();
            node.fields().forEachRemaining(entry -> {
                String value = coerceAnswer(entry.getValue());
                if (!value.isBlank()) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(entry.getKey()).append(": ").append(value.trim()).append(".");
                }
            });
            return sb.toString().trim();
        }
        return node.asText();
    }

    // -------------------------------------------------------------------------
    // JSON utilities
    // -------------------------------------------------------------------------

    private String normalizeJson(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String extractJsonObject(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        return (first >= 0 && last > first) ? trimmed.substring(first, last + 1).trim() : trimmed;
    }

    // -------------------------------------------------------------------------
    // Prompt building
    // -------------------------------------------------------------------------

    private String buildInterviewPrompt(boolean strictJson, int compactLevel) {
        int questionCount = switch (compactLevel) {
            case 2 -> 5;
            case 1 -> 6;
            default -> 8;
        };
        String countLabel = compactLevel == 0 ? "8-10" : String.valueOf(questionCount);

        String base = """
            You are a senior interviewer. Return ONLY valid JSON.
            Use this exact structure:
            {
              "jobTitle": "string",
              "questions": ["string", "string", "string"]
            }
            Generate %s short-answer interview questions tailored to the role.
            Include exactly 5 technical questions based on the job description's required tools/stack.
            The rest can be experience, leadership, or problem-solving questions.
            Technical questions should be concrete (e.g., if React is required, ask about useMemo, hydration vs render, state management tradeoffs).
            Avoid trivia or syntax-only questions.
            """.formatted(countLabel);

        if (!strictJson) return base;

        return base + """

            Output must be a single-line JSON object. Escape any newlines as \\n and any quotes inside strings.
            Do not include trailing commas or comments. Do not wrap the JSON in code fences.
            """;
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private static String truncate(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    private static String summarize(String content) {
        if (content == null) return "<null>";
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void notify(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }
}