package com.example.essentialrag.service.evaluation;

import java.util.List;

public record AnswerEvaluationSummary(
    int total,
    int passed,
    int failed,
    List<AnswerEvaluationResult> results) {
}
