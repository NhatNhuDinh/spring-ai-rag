package com.example.essentialrag.service.guardrail;

import java.util.Set;

public record CitationValidationResult(
    boolean valid,
    boolean missingRequiredCitation,
    Set<String> answerCitations,
    Set<String> contextCitations,
    Set<String> invalidCitations,
    Set<String> uncitedPassages) {
}
