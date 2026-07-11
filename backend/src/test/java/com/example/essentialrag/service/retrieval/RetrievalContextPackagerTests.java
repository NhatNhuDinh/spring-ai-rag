package com.example.essentialrag.service.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalContextPackagerTests {

  @Test
  void formatsContextWithCitationLabelsAndSourceMetadata() {
    RetrievalContextPackager packager = new RetrievalContextPackager(12, 10_000);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source_file", "triet_2.pdf");
    metadata.put("book_title", "Giáo trình Kinh tế chính trị Mác - Lênin");
    metadata.put("chapter_number", "3");
    metadata.put("chapter_title", "GIÁ TRỊ THẶNG DƯ");
    metadata.put("section_path", "I > 1 > d");
    metadata.put("page_start", 82);
    metadata.put("page_end", 83);
    metadata.put("block_type", "main_content");
    metadata.put("retrieval_role", "seed");
    metadata.put("seed_rank", 1);

    Document document = Document.builder()
        .id("doc-1")
        .text("""
            Giáo trình: Giáo trình Kinh tế chính trị Mác - Lênin
            Môn học: Kinh tế chính trị Mác - Lênin
            Nội dung:
            Tư bản bất biến và tư bản khả biến là hai bộ phận của tư bản sản xuất.
            """)
        .metadata(metadata)
        .score(0.7)
        .build();

    String context = packager.packageContext(
        "Tư bản bất biến và tư bản khả biến khác nhau như thế nào?",
        List.of(document));

    assertThat(context)
        .contains("RAG_CONTEXT")
        .contains("Câu hỏi:")
        .contains("Nguồn trích dẫn:")
        .contains("[S1]")
        .contains("- File: triet_2.pdf")
        .contains("- Giáo trình: Giáo trình Kinh tế chính trị Mác - Lênin")
        .contains("- Trang: 82-83")
        .contains("Nội dung:")
        .contains("Tư bản bất biến và tư bản khả biến");
    assertThat(context).doesNotContain("Môn học:");
    assertThat(context).doesNotContain("Mon hoc:");
  }
}
