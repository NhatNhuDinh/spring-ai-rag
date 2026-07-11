package com.example.essentialrag.ingestion.parsing;

import com.example.essentialrag.ingestion.model.PageLines;
import com.example.essentialrag.ingestion.model.TextbookBlock;
import com.example.essentialrag.ingestion.model.TextbookProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextbookStructureParserTests {

  private final TextbookStructureParser parser = new TextbookStructureParser();

  @Test
  void keepsNumberedReviewQuestionsAsContent() {
    TextbookProfile profile = new TextbookProfile(
        "classpath:documentation/triet_1.pdf",
        "triet_1.pdf",
        "Giáo trình Triết học Mác - Lênin",
        "Triết học Mác - Lênin");

    List<TextbookBlock> blocks = parser.parse(List.of(
        new PageLines(48, List.of(
            "Chương 1",
            "TRIẾT HỌC VÀ VẤN ĐỀ CƠ BẢN CỦA TRIẾT HỌC",
            "C. CÂU HỎI ÔN TẬP",
            "1. Triết học và vấn đề cơ bản của triết học.",
            "2. Những tiền đề của sự ra đời triết học Mác - Lênin."))),
        profile);

    assertThat(blocks)
        .hasSize(2)
        .allSatisfy(block -> {
          assertThat(block.blockType()).isEqualTo("review_question");
          assertThat(block.chapterNumber()).isEqualTo("1");
          assertThat(block.pageStart()).isEqualTo(48);
        });
    assertThat(blocks).extracting(TextbookBlock::text)
        .containsExactly(
            "1. Triết học và vấn đề cơ bản của triết học.",
            "2. Những tiền đề của sự ra đời triết học Mác Lênin.");
  }

  @Test
  void preservesAccentsInSameLineChapterTitle() {
    TextbookProfile profile = new TextbookProfile(
        "classpath:documentation/triet_2.pdf",
        "triet_2.pdf",
        "Giáo trình Kinh tế chính trị Mác - Lênin",
        "Kinh tế chính trị Mác - Lênin");

    List<TextbookBlock> blocks = parser.parse(List.of(
        new PageLines(75, List.of(
            "Chương 3: GIÁ TRỊ THẶNG DƯ TRONG NỀN KINH TẾ THỊ TRƯỜNG",
            "Giá trị thặng dư là phần giá trị mới dôi ra ngoài giá trị sức lao động do công nhân tạo ra."))),
        profile);

    assertThat(blocks).singleElement()
        .satisfies(block -> {
          assertThat(block.chapterNumber()).isEqualTo("3");
          assertThat(block.chapterTitle()).isEqualTo("GIÁ TRỊ THẶNG DƯ TRONG NỀN KINH TẾ THỊ TRƯỜNG");
        });
  }
}
