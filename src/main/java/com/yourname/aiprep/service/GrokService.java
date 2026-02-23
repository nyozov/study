package com.yourname.aiprep.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.aiprep.model.CourseGuide;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GrokService {

    @Value("${grok.api.key}")
    private String apiKey;

    @Value("${grok.api.url}")
    private String apiUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GrokService(ObjectMapper objectMapper) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
    }

    public CourseGuide generateCourseGuide(String userPrompt) {
        String systemPrompt = """
            You are a curriculum designer. Given a user prompt, return a SHORT structured JSON study guide
            in a Coursera-like style plus a mock interview. Return ONLY valid JSON, no markdown, no explanation.
            Use this exact structure:
            {
              \"jobTitle\": \"string\",
              \"overview\": \"string\",
              \"modules\": [
                {
                  \"title\": \"string\",
                  \"description\": \"string\",
                  \"lessons\": [\"string\"],
                  \"resources\": [\"string - book, article, or course recommendation\"]
                }
              ],
              \"mockInterviewQuestions\": [\"string\", \"string\", \"string\"]
            }
            Generate 3-4 modules and 6-8 interview questions.
            """;

        Map<String, Object> body = Map.of(
            "model", "llama-3.3-70b-versatile",
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", "User Prompt:\n" + userPrompt)
            ),
            "temperature", 0.6
        );

        Map<?, ?> response = restClient.post()
            .uri(apiUrl)
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);

        String content = extractContent(response);
        String normalizedJson = normalizeJson(content);

        try {
            return objectMapper.readValue(normalizedJson, CourseGuide.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Grok response as CourseGuide", e);
        }
    }

    private String extractContent(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalStateException("Empty Grok response");
        }

        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("Grok response missing choices");
        }

        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> firstChoiceMap)) {
            throw new IllegalStateException("Grok response choice is not an object");
        }

        Object messageObj = firstChoiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("Grok response missing message");
        }

        Object contentObj = messageMap.get("content");
        if (!(contentObj instanceof String content)) {
            throw new IllegalStateException("Grok response content is not a string");
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
}
