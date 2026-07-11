package com.example.essentialrag.service.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcademicQuestionDetectorTests {

  private final AcademicQuestionDetector detector = new AcademicQuestionDetector();

  @Test
  void detectsTextbookQuestionsButIgnoresSimpleGreeting() {
    assertThat(detector.isLikelyAcademicQuestion("Giá trị thặng dư là gì?")).isTrue();
    assertThat(detector.isLikelyAcademicQuestion("Tư bản bất biến khác tư bản khả biến thế nào?")).isTrue();
    assertThat(detector.isLikelyAcademicQuestion("Xin chào, bạn là trợ lý môn gì?")).isFalse();
  }
}
