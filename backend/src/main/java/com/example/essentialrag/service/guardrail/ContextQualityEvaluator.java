package com.example.essentialrag.service.guardrail;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ContextQualityEvaluator {

  private final int minSources;
  private final double minVectorScore;
  private final double minKeywordScore;
  private final double minHybridScore;

  public ContextQualityEvaluator(
      @Value("${rag.guardrails.context.min-sources:1}") int minSources,
      @Value("${rag.guardrails.context.min-vector-score:0.25}") double minVectorScore,
      @Value("${rag.guardrails.context.min-keyword-score:0.01}") double minKeywordScore,
      @Value("${rag.guardrails.context.min-hybrid-score:0.012}") double minHybridScore) {

    this.minSources = Math.max(1, minSources);
    this.minVectorScore = minVectorScore;
    this.minKeywordScore = minKeywordScore;
    this.minHybridScore = minHybridScore;
  }

  public ContextQualityResult evaluate(List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return new ContextQualityResult(false, "no_retrieved_sources", 0, 0.0, 0.0, false);
    }

    double bestHybridScore = 0.0;
    double bestVectorScore = 0.0;
    double bestKeywordScore = 0.0;
    boolean hasKeywordMatch = false;

    for (Document document : documents) {
      Map<String, Object> metadata = document.getMetadata();
      bestHybridScore = Math.max(bestHybridScore, number(metadata, "hybrid_score", document.getScore()));
      bestVectorScore = Math.max(bestVectorScore, number(metadata, "vector_score", null));
      bestKeywordScore = Math.max(bestKeywordScore, number(metadata, "keyword_score", null));
      String matchedBy = string(metadata, "matched_by");
      hasKeywordMatch = hasKeywordMatch || (matchedBy != null && matchedBy.contains("keyword"));
    }

    if (documents.size() < minSources) {
      return new ContextQualityResult(
          false,
          "not_enough_sources",
          documents.size(),
          bestHybridScore,
          bestVectorScore,
          hasKeywordMatch);
    }

    if (bestHybridScore < minHybridScore) {
      return new ContextQualityResult(
          false,
          "hybrid_score_below_threshold",
          documents.size(),
          bestHybridScore,
          bestVectorScore,
          hasKeywordMatch);
    }

    if ((hasKeywordMatch && bestKeywordScore >= minKeywordScore)
        || bestVectorScore >= minVectorScore) {
      return new ContextQualityResult(
          true,
          "sufficient",
          documents.size(),
          bestHybridScore,
          bestVectorScore,
          hasKeywordMatch);
    }

    return new ContextQualityResult(
        false,
        "weak_vector_only_context",
        documents.size(),
        bestHybridScore,
        bestVectorScore,
        false);
  }

  private String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value == null ? null : value.toString();
  }

  private double number(Map<String, Object> metadata, String key, Double fallback) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value != null && !value.toString().isBlank()) {
      return Double.parseDouble(value.toString());
    }
    return fallback == null ? 0.0 : fallback;
  }
}
