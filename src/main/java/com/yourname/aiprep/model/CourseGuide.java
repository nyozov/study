package com.yourname.aiprep.model;

import java.util.List;

public record CourseGuide(
    String jobTitle,
    String overview,
    List<Module> modules,
    List<String> mockInterviewQuestions
) {
    public record Module(
        String title,
        String description,
        List<String> resources,
        List<QuizQuestion> quizQuestions
    ) {}

    public record QuizQuestion(
        String question,
        List<String> options,
        int correctIndex,
        String explanation
    ) {}
}
