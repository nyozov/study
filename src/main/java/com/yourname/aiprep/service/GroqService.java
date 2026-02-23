package com.yourname.aiprep.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.aiprep.model.CourseGuide;
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

    @Value("${grok.api.key}")
    private String apiKey;

    @Value("${grok.api.url}")
    private String apiUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GroqService(ObjectMapper objectMapper) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
    }

    public CourseGuide generateCourseGuide(String userPrompt) {
        String safePrompt = truncate(userPrompt, 4000);
        try {
            return requestCourseGuide(buildCourseGuidePrompt(false, 0), safePrompt, 2400);
        } catch (IllegalStateException ex) {
            try {
                return requestCourseGuide(buildCourseGuidePrompt(true, 0), safePrompt, 2200);
            } catch (IllegalStateException retryEx) {
                try {
                    return requestCourseGuide(buildCourseGuidePrompt(true, 1), safePrompt, 1600);
                } catch (IllegalStateException compactEx) {
                    try {
                        return requestCourseGuide(buildCourseGuidePrompt(true, 2), safePrompt, 1200);
                    } catch (IllegalStateException finalEx) {
                        return requestCourseGuideTwoStep(safePrompt, msg -> {});
                    }
                }
            }
        }
    }

    public CourseGuide generateCourseGuideWithProgress(
        String userPrompt,
        Consumer<String> progress
    ) {
        progress.accept("Starting course generation...");
        try {
            return requestCourseGuide(buildCourseGuidePrompt(false, 0), userPrompt, 2400);
        } catch (IllegalStateException ex) {
            progress.accept("Retrying with stricter JSON...");
            try {
                return requestCourseGuide(buildCourseGuidePrompt(true, 0), userPrompt, 2200);
            } catch (IllegalStateException retryEx) {
                progress.accept("Retrying with compact output...");
                try {
                    return requestCourseGuide(buildCourseGuidePrompt(true, 1), userPrompt, 1600);
                } catch (IllegalStateException compactEx) {
                    progress.accept("Switching to step-by-step generation...");
                    return requestCourseGuideTwoStep(userPrompt, progress);
                }
            }
        }
    }

    private CourseGuide requestCourseGuide(String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", "User Prompt:\n" + userPrompt)
        );

        Map<?, ?> response = postChat(messages, 0.6, maxTokens);

        String content = extractContent(response);
        String normalizedJson = normalizeJson(content);
        String jsonCandidate = extractJsonObject(normalizedJson);

        if (!isLikelyCompleteJson(jsonCandidate)) {
            throw new IllegalStateException(
                "Likely truncated JSON. Raw content: " + summarize(content)
            );
        }

        try {
            return shuffleQuizOptions(parseCourseGuide(jsonCandidate));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse Grok response as CourseGuide. Raw content: " + summarize(content),
                e
            );
        }
    }

    private CourseGuide requestCourseGuideTwoStep(String userPrompt, Consumer<String> progress) {
        String outlinePrompt = """
            You are a curriculum designer. Return ONLY valid JSON for a compact outline.
            Structure:
            {
              "jobTitle": "string",
              "overview": "string",
              "modules": [
                { "title": "string", "description": "string" }
              ]
            }
            Generate 3 modules with short descriptions. Keep the JSON compact.
            """;

        List<Map<String, String>> outlineMessages = List.of(
            Map.of("role", "system", "content", outlinePrompt),
            Map.of("role", "user", "content", "User Prompt:\n" + userPrompt)
        );

        Map<?, ?> outlineResponse = postChat(outlineMessages, 0.4, 700);

        String outlineContent = extractContent(outlineResponse);
        String outlineJson = extractJsonObject(normalizeJson(outlineContent));

        Outline outline;
        try {
            outline = parseOutline(outlineJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse outline response. Raw content: " + summarize(outlineContent),
                e
            );
        }

        List<Outline.ModuleOutline> outlineModules = outline.modules() == null
            ? List.of()
            : outline.modules();
        List<CourseGuide.Module> modules = new java.util.ArrayList<>();
        int total = outlineModules.size();
        for (int i = 0; i < total; i++) {
            Outline.ModuleOutline module = outlineModules.get(i);
            progress.accept("Generating module " + (i + 1) + " of " + total + "...");
            modules.add(generateModuleDetail(userPrompt, outline.jobTitle(), module));
        }

        progress.accept("Generating mock interview questions...");
        List<String> interviewQuestions = generateInterviewQuestions(userPrompt, outline.jobTitle());

        return new CourseGuide(
            outline.jobTitle(),
            outline.overview(),
            modules,
            interviewQuestions
        );
    }

    private CourseGuide.Module generateModuleDetail(
        String userPrompt,
        String jobTitle,
        Outline.ModuleOutline module
    ) {
        String detailPrompt = """
            You are a curriculum designer. Return ONLY valid JSON for a single module focused on practice.
            Use this exact structure:
            {
              "title": "string",
              "description": "string",
              "resources": ["string - book, article, or course recommendation"],
              "quizQuestions": [
                {
                  "question": "string",
                  "options": ["A", "B", "C", "D"],
                  "correctIndex": 0,
                  "explanation": "string"
                }
              ]
            }
            Generate 2 resources and 3 quiz questions. Keep JSON compact.
            Quiz questions must be job-interview style for the role. Avoid trivia or syntax-only questions.
            """;

        String userContent = String.format(
            "Job Title: %s%nModule Title: %s%nModule Description: %s%nJob Description:%n%s",
            jobTitle == null ? "" : jobTitle,
            module.title(),
            module.description(),
            userPrompt
        );

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", detailPrompt),
            Map.of("role", "user", "content", userContent)
        );

        Map<?, ?> response = postChat(messages, 0.5, 1200);

        String content = extractContent(response);
        String jsonCandidate = extractJsonObject(normalizeJson(content));

        try {
            return shuffleQuizOptions(parseModule(jsonCandidate));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse module response. Raw content: " + summarize(content),
                e
            );
        }
    }

    private List<String> generateInterviewQuestions(String userPrompt, String jobTitle) {
        String prompt = """
            You are an interviewer. Return ONLY valid JSON:
            { "questions": ["string", "string", "string", "string", "string"] }
            Generate 5 mock interview questions tailored to the role.
            """;

        String userContent = String.format(
            "Job Title: %s%nJob Description:%n%s",
            jobTitle == null ? "" : jobTitle,
            userPrompt
        );

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", prompt),
            Map.of("role", "user", "content", userContent)
        );

        Map<?, ?> response = postChat(messages, 0.4, 400);

        String content = extractContent(response);
        String jsonCandidate = extractJsonObject(normalizeJson(content));

        try {
            return parseInterviewQuestions(jsonCandidate);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to parse interview questions. Raw content: " + summarize(content),
                e
            );
        }
    }

    public com.yourname.aiprep.model.ReviewAnswerResponse reviewMockAnswer(
        com.yourname.aiprep.model.ReviewAnswerRequest request
    ) {
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

    public com.yourname.aiprep.model.IdealAnswerResponse generateIdealAnswer(
        com.yourname.aiprep.model.ReviewAnswerRequest request
    ) {
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

    private CourseGuide shuffleQuizOptions(CourseGuide guide) {
        if (guide == null || guide.modules() == null) {
            return guide;
        }

        List<CourseGuide.Module> shuffledModules = guide.modules().stream()
            .map(this::shuffleQuizOptions)
            .toList();

        return new CourseGuide(
            guide.jobTitle(),
            guide.overview(),
            shuffledModules,
            guide.mockInterviewQuestions()
        );
    }

    private CourseGuide.Module shuffleQuizOptions(CourseGuide.Module module) {
        if (module == null || module.quizQuestions() == null) {
            return module;
        }

        List<CourseGuide.QuizQuestion> shuffledQuestions = module.quizQuestions().stream()
            .map(this::shuffleQuizOptions)
            .toList();

        return new CourseGuide.Module(
            module.title(),
            module.description(),
            module.resources(),
            shuffledQuestions
        );
    }

    private CourseGuide.QuizQuestion shuffleQuizOptions(CourseGuide.QuizQuestion quiz) {
        if (quiz == null || quiz.options() == null || quiz.options().isEmpty()) {
            return quiz;
        }

        List<String> options = new java.util.ArrayList<>(quiz.options());
        int correctIndex = quiz.correctIndex();
        if (correctIndex < 0 || correctIndex >= options.size()) {
            return quiz;
        }

        List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            indices.add(i);
        }
        java.util.Collections.shuffle(indices);

        List<String> shuffledOptions = new java.util.ArrayList<>();
        int newCorrectIndex = 0;
        for (int i = 0; i < indices.size(); i++) {
            int originalIndex = indices.get(i);
            shuffledOptions.add(options.get(originalIndex));
            if (originalIndex == correctIndex) {
                newCorrectIndex = i;
            }
        }

        return new CourseGuide.QuizQuestion(
            quiz.question(),
            shuffledOptions,
            newCorrectIndex,
            quiz.explanation()
        );
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

    private CourseGuide parseCourseGuide(String json) throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, CourseGuide.class);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(json, CourseGuide.class);
        }
    }

    private com.yourname.aiprep.model.ReviewAnswerResponse parseReview(String json)
        throws JsonProcessingException {
        try {
            return objectMapper.readValue(
                json,
                com.yourname.aiprep.model.ReviewAnswerResponse.class
            );
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(
                json,
                com.yourname.aiprep.model.ReviewAnswerResponse.class
            );
        }
    }

    private com.yourname.aiprep.model.IdealAnswerResponse parseIdealAnswer(String json)
        throws JsonProcessingException {
        try {
            return objectMapper.readValue(
                json,
                com.yourname.aiprep.model.IdealAnswerResponse.class
            );
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(
                json,
                com.yourname.aiprep.model.IdealAnswerResponse.class
            );
        }
    }

    private Outline parseOutline(String json) throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, Outline.class);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(json, Outline.class);
        }
    }

    private CourseGuide.Module parseModule(String json) throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, CourseGuide.Module.class);
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(json, CourseGuide.Module.class);
        }
    }

    private List<String> parseInterviewQuestions(String json) throws JsonProcessingException {
        try {
            return objectMapper.readValue(json, InterviewQuestions.class).questions();
        } catch (JsonProcessingException strictError) {
            ObjectMapper lenient = objectMapper.copy()
                .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
                .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
            return lenient.readValue(json, InterviewQuestions.class).questions();
        }
    }

    private String buildCourseGuidePrompt(boolean strictJson, int compactLevel) {
        String base = """
            You are a curriculum designer. Given a user prompt, return a SHORT structured JSON study guide
            in a Coursera-like style plus a mock interview. Return ONLY valid JSON, no markdown, no explanation.
            Use this exact structure:
            {
              "jobTitle": "string",
              "overview": "string",
              "modules": [
                {
                  "title": "string",
                  "description": "string",
                  "resources": ["string - book, article, or course recommendation"],
                  "quizQuestions": [
                    {
                      "question": "string",
                      "options": ["A", "B", "C", "D"],
                      "correctIndex": 0,
                      "explanation": "string"
                    }
                  ]
                }
              ],
              "mockInterviewQuestions": ["string", "string", "string"]
            }
            Generate 3-4 modules and 4-6 quiz questions per module.
            Each quiz question must include exactly 4 options, a 0-based correctIndex, and a 1-sentence explanation.
            Make the correct option unambiguously correct. The other 3 options must be clearly incorrect, not merely less precise.
            Avoid subjective or multi-correct questions. Prefer role-relevant, job-interview style questions over trivia or syntax-only questions.
            Generate 6-8 interview questions.
            """;

        if (compactLevel >= 1) {
            base = base.replace(
                "Generate 3-4 modules and 4-6 quiz questions per module.",
                "Generate 3 modules and 3 quiz questions per module."
            ).replace(
                "Generate 6-8 interview questions.",
                "Generate 5 interview questions."
            );
        }

        if (compactLevel >= 2) {
            base = base.replace(
                "Generate 3 modules and 3 quiz questions per module.",
                "Generate 2 modules and 2 quiz questions per module."
            ).replace(
                "Generate 5 interview questions.",
                "Generate 4 interview questions."
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

    private record Outline(
        String jobTitle,
        String overview,
        List<ModuleOutline> modules
    ) {
        private record ModuleOutline(String title, String description) {}
    }

    private record InterviewQuestions(List<String> questions) {}
}
