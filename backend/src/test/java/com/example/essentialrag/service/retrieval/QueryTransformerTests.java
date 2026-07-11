package com.example.essentialrag.service.retrieval;

import com.example.essentialrag.ingestion.support.TextbookText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTransformerTests {

  private final QueryTransformer transformer = new QueryTransformer(new QueryIntentDetector(), true, 4);

  @Test
  void expandsEverydayExploitationQuestionToSurplusValueQueries() {
    QueryTransformation transformation = transformer.transform("Vì sao công nhân bị bóc lột?");
    String normalizedQueries = normalizedQueries(transformation);

    assertThat(transformation.intent().sourceFile()).isEqualTo("triet_2.pdf");
    assertThat(transformation.intent().chapterNumber()).isEqualTo("3");
    assertThat(transformation.intent().blockType()).isEqualTo("main_content");
    assertThat(transformation.retrievalQueries())
        .contains("Vì sao công nhân bị bóc lột?");
    assertThat(normalizedQueries)
        .contains("GIA TRI THANG DU")
        .contains("HANG HOA SUC LAO DONG");
  }

  @Test
  void expandsBasicPhilosophyQuestion() {
    QueryTransformation transformation = transformer.transform("Vật chất có trước hay ý thức có trước?");
    String normalizedQueries = normalizedQueries(transformation);

    assertThat(transformation.intent().sourceFile()).isEqualTo("triet_1.pdf");
    assertThat(transformation.intent().chapterNumber()).isEqualTo("1");
    assertThat(transformation.intent().blockType()).isEqualTo("main_content");
    assertThat(normalizedQueries).contains("VAN DE CO BAN CUA TRIET HOC");
  }

  @Test
  void preservesReviewQuestionIntent() {
    QueryTransformation transformation = transformer.transform("Câu hỏi ôn tập chương 3 Kinh tế chính trị gồm những gì?");
    String normalizedQueries = normalizedQueries(transformation);

    assertThat(transformation.intent().sourceFile()).isEqualTo("triet_2.pdf");
    assertThat(transformation.intent().chapterNumber()).isEqualTo("3");
    assertThat(transformation.intent().blockType()).isEqualTo("review_question");
    assertThat(normalizedQueries).contains("CAU HOI ON TAP CHUONG 3");
  }

  @Test
  void doesNotForceMainContentForDialecticalMethodQueries() {
    QueryTransformation transformation = transformer.transform(
        "Phương pháp biện chứng nhận thức đối tượng như thế nào?");

    assertThat(transformation.intent().sourceFile()).isEqualTo("triet_1.pdf");
    assertThat(transformation.intent().chapterNumber()).isEqualTo("1");
    assertThat(transformation.intent().blockType()).isNull();
  }

  private String normalizedQueries(QueryTransformation transformation) {
    return TextbookText.normalizeForMatching(String.join(" ", transformation.retrievalQueries()));
  }
}
