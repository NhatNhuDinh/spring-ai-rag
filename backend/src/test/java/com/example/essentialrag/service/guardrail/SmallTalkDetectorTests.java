package com.example.essentialrag.service.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmallTalkDetectorTests {

  private final SmallTalkDetector detector = new SmallTalkDetector();

  @Test
  void recognizesOnlyExplicitSmallTalkAndDefaultsOtherQuestionsToRag() {
    assertThat(detector.isSmallTalk("Xin chào")).isTrue();
    assertThat(detector.isSmallTalk("Cảm ơn bạn")).isTrue();
    assertThat(detector.isSmallTalk("Xin chào, giá trị thặng dư là gì?")).isFalse();
    assertThat(detector.isSmallTalk("Nguồn gốc của nhà nước là gì?")).isFalse();
    assertThat(detector.isSmallTalk("Quy luật lượng chất vận động thế nào?")).isFalse();
  }
}
