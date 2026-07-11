package com.example.essentialrag.service.evaluation;

import com.example.essentialrag.service.guardrail.CitationValidationResult;
import com.example.essentialrag.service.guardrail.ContextQualityResult;

import java.util.List;

public record AnswerEvaluationResult(
    String id,
    String question,
    boolean passed,
    String answer,
    int sourceCount,
    ContextQualityResult contextQuality,
    CitationValidationResult citationValidation,
    boolean expectedSourceHit,
    boolean expectedChapterHit,
    List<String> missingMustMention,
    List<String> unexpectedMentions,
    boolean refusalPassed) {
}
