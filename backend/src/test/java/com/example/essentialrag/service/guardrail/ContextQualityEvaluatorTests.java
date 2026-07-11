package com.example.essentialrag.service.guardrail;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextQualityEvaluatorTests {

  private final ContextQualityEvaluator evaluator = new ContextQualityEvaluator(1, 0.25, 0.01, 0.012);

  @Test
  void rejectsEmptyAndWeakVectorOnlyContext() {
    assertThat(evaluator.evaluate(List.of()).sufficient()).isFalse();

    ContextQualityResult weak = evaluator.evaluate(List.of(
        document("vector", 0.1, "vector", 0.10)));

    assertThat(weak.sufficient()).isFalse();
    assertThat(weak.reason()).isEqualTo("weak_vector_only_context");
  }

  @Test
  void acceptsStrongKeywordOrVectorMatch() {
    ContextQualityResult keyword = evaluator.evaluate(List.of(
        document("keyword", 0.02, "keyword", 0.05)));
    ContextQualityResult vector = evaluator.evaluate(List.of(
        document("vector", 0.02, "vector", 0.70)));

    assertThat(keyword.sufficient()).isTrue();
    assertThat(vector.sufficient()).isTrue();
  }

  @Test
  void rejectsKeywordMatchesBelowTheHybridQualityGate() {
    ContextQualityResult weakKeyword = evaluator.evaluate(List.of(
        document("keyword", 0.005, "keyword", 0.05)));

    assertThat(weakKeyword.sufficient()).isFalse();
    assertThat(weakKeyword.reason()).isEqualTo("hybrid_score_below_threshold");
  }

  private Document document(String id, double score, String matchedBy, double vectorScore) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("matched_by", matchedBy);
    metadata.put("hybrid_score", score);
    metadata.put("vector_score", vectorScore);
    if (matchedBy.contains("keyword")) {
      metadata.put("keyword_score", 0.05);
    }
    return Document.builder()
        .id(id)
        .text("Nội dung")
        .metadata(metadata)
        .score(score)
        .build();
  }
}
