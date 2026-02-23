package com.yourname.aiprep.model;

public record ReviewAnswerRequest(
    String question,
    String answer,
    String jobTitle
) {}
