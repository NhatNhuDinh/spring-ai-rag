package com.example.essentialrag.service.guardrail;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextQualityEvaluatorTests {

  private final ContextQualityEvaluator evaluator = new ContextQualityEvaluator(1, 0.25, 0.0);

  @Test
  void rejectsEmptyAndWeakVectorOnlyContext() {
    assertThat(evaluator.evaluate(List.of()).sufficient()).isFalse();

    ContextQualityResult weak = evaluator.evaluate(List.of(
        document("vector", 0.1, "vector", 0.10)));

    assertThat(weak.sufficient()).isFalse();
    assertThat(weak.reason()).isEqualTo("weak_vector_only_context");
  }

  @Test
  void acceptsKeywordMatchOrStrongVectorMatch() {
    ContextQualityResult keyword = evaluator.evaluate(List.of(
        document("keyword", 0.01, "keyword", 0.05)));
    ContextQualityResult vector = evaluator.evaluate(List.of(
        document("vector", 0.01, "vector", 0.70)));

    assertThat(keyword.sufficient()).isTrue();
    assertThat(vector.sufficient()).isTrue();
  }

  private Document document(String id, double score, String matchedBy, double vectorScore) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("matched_by", matchedBy);
    metadata.put("hybrid_score", score);
    metadata.put("vector_score", vectorScore);
    return Document.builder()
        .id(id)
        .text("Nội dung")
        .metadata(metadata)
        .score(score)
        .build();
  }
}
