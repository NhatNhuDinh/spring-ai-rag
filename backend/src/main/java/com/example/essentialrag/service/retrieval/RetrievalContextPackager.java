package com.example.essentialrag.service.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RetrievalContextPackager {

  private final int maxSources;
  private final int maxCharacters;

  public RetrievalContextPackager(
      @Value("${rag.retrieval.context-packaging.max-sources:12}") int maxSources,
      @Value("${rag.retrieval.context-packaging.max-characters:16000}") int maxCharacters) {

    this.maxSources = Math.max(1, maxSources);
    this.maxCharacters = Math.max(2000, maxCharacters);
  }

  public String packageContext(String query, List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return """
          RAG_CONTEXT
          Câu hỏi: %s

          Không tìm thấy nguồn giáo trình phù hợp trong vectorstore.
          Khi trả lời, hãy nói rõ rằng ngữ cảnh giáo trình hiện có không đủ để kết luận.
          """.formatted(blankToUnknown(query));
    }

    StringBuilder context = new StringBuilder();
    context.append("RAG_CONTEXT\n");
    context.append("Câu hỏi: ").append(blankToUnknown(query)).append("\n\n");
    context.append("Hướng dẫn trả lời:\n");
    context.append("- Chỉ dùng các nguồn bên dưới để trả lời câu hỏi.\n");
    context.append("- Khi nêu ý kiến/khái niệm, dẫn nguồn bằng nhãn [S1], [S2]... tương ứng.\n");
    context.append("- Nếu nguồn không đủ hoặc không liên quan, nói rõ là ngữ cảnh giáo trình chưa đủ.\n\n");
    context.append("Nguồn trích dẫn:\n");

    int sourceCount = Math.min(documents.size(), maxSources);
    for (int i = 0; i < sourceCount; i++) {
      Document document = documents.get(i);
      String source = formatSource(i + 1, document);
      String remainingAwareContent = truncateToRemaining(context, source);
      if (remainingAwareContent.isBlank()) {
        context.append("\n[Context bị cắt: đạt giới hạn ")
            .append(maxCharacters)
            .append(" ký tự]");
        break;
      }
      context.append(remainingAwareContent);
    }

    return enforceMaxCharacters(context.toString().trim());
  }

  public int maxCharacters() {
    return maxCharacters;
  }

  private String formatSource(int index, Document document) {
    Map<String, Object> metadata = document.getMetadata();
    String label = "[S" + index + "]";
    String pageTrace = pageTrace(metadata);
    String role = blankToDefault(string(metadata, "retrieval_role"), "seed");
    String seedRank = blankToDefault(string(metadata, "seed_rank"), "");
    String seedText = seedRank.isBlank() ? role : role + ", seed_rank=" + seedRank;

    return """

        %s
        - File: %s
        - Giáo trình: %s
        - Chương: %s%s
        - Mục: %s
        - Trang: %s
        - Loại nội dung: %s
        - Vai trò retrieval: %s
        - Retrieval mode: %s
        - Matched by: %s
        - Hybrid score: %s
        - Retrieval query: %s
        Nội dung:
        %s
        """.formatted(
        label,
        blankToUnknown(string(metadata, "source_file")),
        blankToUnknown(string(metadata, "book_title")),
        blankToDefault(string(metadata, "chapter_number"), "unknown"),
        chapterTitleSuffix(string(metadata, "chapter_title")),
        blankToUnknown(string(metadata, "section_path")),
        pageTrace,
        blankToUnknown(string(metadata, "block_type")),
        seedText,
        blankToDefault(string(metadata, "retrieval_mode"), "vector"),
        blankToUnknown(string(metadata, "matched_by")),
        blankToUnknown(string(metadata, "hybrid_score")),
        blankToUnknown(string(metadata, "retrieval_query")),
        normalizeContent(stripEmbeddingHeader(document.getText())));
  }

  private String truncateToRemaining(StringBuilder current, String nextSource) {
    int remaining = maxCharacters - current.length();
    if (remaining <= 80) {
      return "";
    }
    if (nextSource.length() <= remaining) {
      return nextSource;
    }
    return nextSource.substring(0, Math.max(0, remaining - 50)).stripTrailing()
        + "\n[Nguồn bị cắt ngắn]\n";
  }

  private String enforceMaxCharacters(String context) {
    if (context.length() <= maxCharacters) {
      return context;
    }
    return context.substring(0, Math.max(0, maxCharacters - 80)).stripTrailing()
        + "\n\n[Context bị cắt: đạt giới hạn " + maxCharacters + " ký tự]";
  }

  private String stripEmbeddingHeader(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    int marker = text.indexOf("Nội dung:");
    int markerLength = "Nội dung:".length();
    if (marker < 0) {
      marker = text.indexOf("Noi dung:");
      markerLength = "Noi dung:".length();
    }
    if (marker < 0) {
      return text;
    }
    return text.substring(marker + markerLength);
  }

  private String normalizeContent(String text) {
    return text
        .replaceAll("[ \\t]+", " ")
        .replaceAll("\\R{3,}", "\n\n")
        .trim();
  }

  private String pageTrace(Map<String, Object> metadata) {
    String start = string(metadata, "page_start");
    String end = string(metadata, "page_end");
    if (start == null && end == null) {
      return "unknown";
    }
    if (end == null || end.equals(start)) {
      return blankToUnknown(start);
    }
    return start + "-" + end;
  }

  private String chapterTitleSuffix(String chapterTitle) {
    if (chapterTitle == null || chapterTitle.isBlank()) {
      return "";
    }
    return " - " + chapterTitle;
  }

  private String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value == null ? null : value.toString();
  }

  private String blankToUnknown(String value) {
    return blankToDefault(value, "unknown");
  }

  private String blankToDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
