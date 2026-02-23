package com.yourname.aiprep.model;

import java.util.List;

public record ReviewAnswerResponse(
    String summary,
    List<String> strengths,
    List<String> improvements,
    String score
) {}
