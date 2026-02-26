package com.yourname.aiprep.model;

import java.util.List;

public record MockInterviewSession(
    String jobTitle,
    List<String> questions
) {}
