package com.example.essentialrag.service.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryIntentDetectorTests {

  private final QueryIntentDetector detector = new QueryIntentDetector();

  @Test
  void detectsReviewQuestionIntent() {
    RetrievalIntent intent = detector.detect("Câu hỏi ôn tập chương 3 Kinh tế chính trị là gì?");

    assertThat(intent.sourceFile()).isEqualTo("triet_2.pdf");
    assertThat(intent.chapterNumber()).isEqualTo("3");
    assertThat(intent.blockType()).isEqualTo("review_question");
  }

  @Test
  void detectsSummaryIntent() {
    RetrievalIntent intent = detector.detect("Tóm tắt chương 3 Kinh tế chính trị nêu những nội dung nào?");

    assertThat(intent.sourceFile()).isEqualTo("triet_2.pdf");
    assertThat(intent.chapterNumber()).isEqualTo("3");
    assertThat(intent.blockType()).isEqualTo("summary");
  }

  @Test
  void detectsKeywordIntent() {
    RetrievalIntent intent = detector.detect("Các thuật ngữ cần ghi nhớ của chương 3 gồm những gì?");

    assertThat(intent.chapterNumber()).isEqualTo("3");
    assertThat(intent.blockType()).isEqualTo("keyword");
  }

  @Test
  void detectsTextbookByTopic() {
    assertThat(detector.detect("Tư bản bất biến và tư bản khả biến khác nhau như thế nào?").sourceFile())
        .isEqualTo("triet_2.pdf");
    assertThat(detector.detect("Vấn đề cơ bản của triết học là gì?").sourceFile())
        .isEqualTo("triet_1.pdf");
  }
}
