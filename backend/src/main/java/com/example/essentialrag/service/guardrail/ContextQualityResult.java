package com.example.essentialrag.service.guardrail;

public record ContextQualityResult(
    boolean sufficient,
    String reason,
    int sourceCount,
    double bestHybridScore,
    double bestVectorScore,
    boolean hasKeywordMatch) {
}
