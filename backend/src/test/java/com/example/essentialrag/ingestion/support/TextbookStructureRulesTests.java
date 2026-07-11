package com.example.essentialrag.ingestion.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextbookStructureRulesTests {

  @Test
  void detectsExplicitBoxHeadingsOnly() {
    assertThat(blockTypeForHeading("Hộp 2.6 Quan niệm của kinh tế vi mô"))
        .isEqualTo("box");
    assertThat(blockTypeForHeading("Hộp kiến thức"))
        .isEqualTo("box");
  }

  @Test
  void doesNotTreatOrdinaryHopWordsAsBox() {
    assertThat(blockTypeForHeading("Sự kết hợp giữa lý luận và thực tiễn"))
        .isNull();
    assertThat(blockTypeForHeading("Nó phù hợp với điều kiện lịch sử"))
        .isNull();
    assertThat(blockTypeForHeading("Các học thuyết được tổng hợp"))
        .isNull();
  }

  @Test
  void detectsSpecialTextbookSectionsWithOptionalLetterPrefix() {
    assertThat(blockTypeForHeading("C. Câu hỏi ôn tập"))
        .isEqualTo("review_question");
    assertThat(blockTypeForHeading("Tóm tắt chương"))
        .isEqualTo("summary");
    assertThat(blockTypeForHeading("Các thuật ngữ cần ghi nhớ"))
        .isEqualTo("keyword");
  }

  private String blockTypeForHeading(String heading) {
    return TextbookStructureRules.blockTypeForHeading(TextbookText.normalizeForMatching(heading));
  }
}
