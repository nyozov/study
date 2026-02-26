package com.yourname.aiprep.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.aiprep.model.IdealAnswerResponse;
import com.yourname.aiprep.model.MockInterviewSession;
import com.yourname.aiprep.model.ReviewAnswerRequest;
import com.yourname.aiprep.model.ReviewAnswerResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GroqService {
    private static final String PRIMARY_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";
    private static final String FALLBACK_MODEL = "groq/compound";

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

    public MockInterviewSession generateMockInterviewSession(String userPrompt) {
        String safePrompt = truncate(userPrompt, 4000);
        try {
            return requestMockInterviewSession(buildInterviewPrompt(false, 0), safePrompt, 700);
        } catch (IllegalStateException ex) {
            try {
                return requestMockInterviewSession(buildInterviewPrompt(true, 0), safePrompt, 650);
            } catch (IllegalStateException retryEx) {
                try {
                    return requestMockInterviewSession(buildInterviewPrompt(true, 1), safePrompt, 520);
                } catch (IllegalStateException compactEx) {
                    return requestMockInterviewSession(buildInterviewPrompt(true, 2), safePrompt, 420);
                }
            }
        }
    }

    public MockInterviewSession generateMockInterviewSessionWithProgress(
        String userPrompt,
        Consumer<String> progress
    ) {
        progress.accept("Analyzing the role...");
        try {
            return requestMockInterviewSession(buildInterviewPrompt(false, 0), userPrompt, 700);
        } catch (IllegalStateException ex) {
            progress.accept("Retrying with stricter JSON...");
            try {
                return requestMockInterviewSession(buildInterviewPrompt(true, 0), userPrompt, 650);
            } catch (IllegalStateException retryEx) {
                progress.accept("Retrying with fewer questions...");
                try {
                    return requestMockInterviewSession(buildInterviewPrompt(true, 1), userPrompt, 520);
                } catch (IllegalStateException compactEx) {
                    progress.accept("Final retry with compact output...");
                    return requestMockInterviewSession(buildInterviewPrompt(true, 2), userPrompt, 420);
                }
            }
        }
    }

    private MockInterviewSession requestMockInterviewSession(
        String systemPrompt,
        String userPrompt,
        int maxTokens
    ) {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", "Job Description:\n" + userPrompt)
        );

        Map<?, ?> response = postChat(messages, 0.4, maxTokens);

        String content = extractContent(response);
        String normalizedJson = normalizeJson(content);
        String jsonCandidate = extractJsonObject(normalizedJson);

        if (!isLikelyCompleteJson(jsonCandidate)) {
            throw new IllegalStateException(
                "Likely truncated JSON. Raw content: " + summarize(content)
            );
        }

        try {
            return parseMockInterviewSession(jsonCandidate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse mock interview session. Raw content: " + summarize(content),
                e
            );
        }
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

        String userContent = String.format(
            "Role: %s%nQuestion: %s%nAnswer: %s",
            request.jobTitle() == null ? "" : request.jobTitle(),
            request.question() == null ? "" : request.question(),
            request.answer() == null ? "" : request.answer()
        );

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userContent)
        );

        Map<?, ?> response = postChat(messages, 0.3, null);

        String content = extractContent(response);
        String normalizedJson = normalizeJson(content);
        String jsonCandidate = extractJsonObject(normalizedJson);

        try {
            return parseReview(jsonCandidate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse review response. Raw content: " + summarize(content),
                e
            );
        }
    }

    public IdealAnswerResponse generateIdealAnswer(ReviewAnswerRequest request) {
        String systemPrompt = """
            You are a senior interviewer. Provide an ideal, concise answer to the question.
            Return ONLY valid JSON, no markdown, no explanation. Use this exact structure:
            { "answer": "string" }
            Keep the answer under 180 words. Use clear, practical language.
            """;

        String userContent = String.format(
            "Role: %s%nQuestion: %s",
            request.jobTitle() == null ? "" : request.jobTitle(),
            request.question() == null ? "" : request.question()
        );

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userContent)
        );

        Map<?, ?> response = postChat(messages, 0.2, 350);

        String content = extractContent(response);
        String normalizedJson = normalizeJson(content);
        String jsonCandidate = extractJsonObject(normalizedJson);

        try {
            return parseIdealAnswer(jsonCandidate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse ideal answer. Raw content: " + summarize(content),
                e
            );
        }
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
        Map<String, Object> body = new HashMap<>();
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
        if (status == 429 || status == 402 || status == 403) {
            return true;
        }
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("rate limit")
            || lower.contains("quota")
            || lower.contains("exceeded")
            || lower.contains("insufficient")
            || lower.contains("tokens");
    }

    private String extractContent(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalStateException("Empty groq response");
        }

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("groq response missing choices");
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> firstChoiceMap)) {
            throw new IllegalStateException("groq response choice is not an object");
        }

        Object messageObj = firstChoiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("groq response missing message");
        }

        Object contentObj = messageMap.get("content");
        if (!(contentObj instanceof String content)) {
            throw new IllegalStateException("groq response content is not a string");
        }

        return content;
    }

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
        if (content == null) {
            return "";
        }

        String trimmed = content.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1).trim();
        }

        return trimmed;
    }

    private String summarize(String content) {
        if (content == null) {
            return "<null>";
        }
        String sanitized = content.replaceAll("\\s+", " ").trim();
        if (sanitized.length() <= 500) {
            return sanitized;
        }
        return sanitized.substring(0, 500) + "...";
    }

    private MockInterviewSession parseMockInterviewSession(String json)
        throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, MockInterviewSession.class);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(json, MockInterviewSession.class);
        }
    }

    private ReviewAnswerResponse parseReview(String json)
        throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, ReviewAnswerResponse.class);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(json, ReviewAnswerResponse.class);
        }
    }

    private IdealAnswerResponse parseIdealAnswer(String json)
        throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, IdealAnswerResponse.class);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            JsonNode node = lenient.readTree(json);
            JsonNode answerNode = node.has("answer") ? node.get("answer") : node;
            String answer = coerceAnswer(answerNode);
            return new IdealAnswerResponse(answer);
        }
    }

    private String coerceAnswer(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String text = coerceAnswer(item);
                if (!text.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append(" ");
                    }
                    builder.append(text.trim());
                }
            }
            return builder.toString().trim();
        }
        if (node.isObject()) {
            StringBuilder builder = new StringBuilder();
            node.fields().forEachRemaining(entry -> {
                String value = coerceAnswer(entry.getValue());
                if (!value.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append(" ");
                    }
                    builder.append(entry.getKey()).append(": ").append(value.trim()).append(".");
                }
            });
            return builder.toString().trim();
        }
        return node.asText();
    }

    private String buildInterviewPrompt(boolean strictJson, int compactLevel) {
        String base = """
            You are a senior interviewer. Return ONLY valid JSON.
            Use this exact structure:
            {
              "jobTitle": "string",
              "questions": ["string", "string", "string"]
            }
            Generate 8-10 short-answer interview questions tailored to the role.
            Include exactly 5 technical questions based on the job description's required tools/stack.
            The rest can be experience, leadership, or problem-solving questions.
            Technical questions should be concrete (e.g., if React is required, ask about useMemo, hydration vs render, state management tradeoffs).
            Avoid trivia or syntax-only questions.
            """;

        if (compactLevel >= 1) {
            base = base.replace(
                "Generate 8-10 short-answer interview questions tailored to the role.",
                "Generate 6 short-answer interview questions tailored to the role."
            );
        }

        if (compactLevel >= 2) {
            base = base.replace(
                "Generate 6 short-answer interview questions tailored to the role.",
                "Generate 5 short-answer interview questions tailored to the role."
            );
        }

        if (!strictJson) {
            return base;
        }

        return base + """

            Output must be a single-line JSON object. Escape any newlines as \\n and any quotes inside strings.
            Do not include trailing commas or comments. Do not wrap the JSON in code fences.
            """;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "...";
    }

    private boolean isLikelyCompleteJson(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth == 0 && trimmed.endsWith("}");
    }
}
