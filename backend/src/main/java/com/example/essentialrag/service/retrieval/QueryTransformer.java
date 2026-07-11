package com.example.essentialrag.service.retrieval;

import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class QueryTransformer {

  private final QueryIntentDetector intentDetector;
  private final boolean enabled;
  private final int maxQueries;

  public QueryTransformer(
      QueryIntentDetector intentDetector,
      @Value("${rag.retrieval.query-transformation.enabled:true}") boolean enabled,
      @Value("${rag.retrieval.query-transformation.max-queries:4}") int maxQueries) {

    this.intentDetector = intentDetector;
    this.enabled = enabled;
    this.maxQueries = Math.max(1, maxQueries);
  }

  public QueryTransformation transform(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    RetrievalIntent detectedIntent = intentDetector.detect(query);
    if (!enabled) {
      return new QueryTransformation(query, List.of(query), detectedIntent);
    }

    String normalized = TextbookText.normalizeForMatching(query);
    RetrievalIntent intent = enrichIntent(detectedIntent, normalized);
    Set<String> queries = new LinkedHashSet<>();
    add(queries, query);

    if (isSpecialBlockType(intent.blockType())) {
      addBlockTypeQueries(queries, normalized, intent);
    }
    else {
      addPhilosophyQueries(queries, normalized);
      addPoliticalEconomyQueries(queries, normalized);
    }

    return new QueryTransformation(query, limit(queries), intent);
  }

  private RetrievalIntent enrichIntent(RetrievalIntent detectedIntent, String normalizedQuery) {
    String sourceFile = detectedIntent.sourceFile();
    String chapterNumber = detectedIntent.chapterNumber();
    String blockType = detectedIntent.blockType();

    if (sourceFile == null && containsAny(normalizedQuery,
        "BOC LOT",
        "CONG NHAN",
        "LAO DONG LAM THUE",
        "SUC LAO DONG",
        "THANG DU",
        "BAT BIEN",
        "KHA BIEN")) {
      sourceFile = "triet_2.pdf";
    }

    if (chapterNumber == null && containsAny(normalizedQuery,
        "BOC LOT",
        "CONG NHAN",
        "LAO DONG LAM THUE",
        "SUC LAO DONG",
        "THANG DU",
        "BAT BIEN",
        "KHA BIEN")) {
      chapterNumber = "3";
    }

    if (sourceFile == null && containsAny(normalizedQuery,
        "VAT CHAT CO TRUOC",
        "Y THUC CO TRUOC",
        "CO BAN CUA TRIET HOC",
        "THE GIOI QUAN",
        "PHUONG PHAP LUAN")) {
      sourceFile = "triet_1.pdf";
    }

    if (chapterNumber == null && containsAny(normalizedQuery,
        "VAT CHAT CO TRUOC",
        "Y THUC CO TRUOC",
        "CO BAN CUA TRIET HOC",
        "THE GIOI QUAN",
        "PHUONG PHAP LUAN")) {
      chapterNumber = "1";
    }

    if (blockType == null && (sourceFile != null || chapterNumber != null)
        && !containsAny(normalizedQuery, "BIEN CHUNG", "SIEU HINH")) {
      blockType = "main_content";
    }

    return new RetrievalIntent(sourceFile, chapterNumber, blockType);
  }

  private boolean isSpecialBlockType(String blockType) {
    return blockType != null && !"main_content".equals(blockType);
  }

  private void addBlockTypeQueries(Set<String> queries, String normalizedQuery, RetrievalIntent intent) {
    String chapterText = intent.hasChapterNumber() ? " chương " + intent.chapterNumber() : "";
    String subjectText = "triet_2.pdf".equals(intent.sourceFile())
        ? " kinh tế chính trị Mác - Lênin"
        : " triết học Mác - Lênin";

    switch (intent.blockType()) {
      case "review_question" -> add(queries, "câu hỏi ôn tập" + chapterText + subjectText);
      case "summary" -> add(queries, "tóm tắt" + chapterText + subjectText);
      case "keyword" -> add(queries, "các thuật ngữ cần ghi nhớ" + chapterText + subjectText);
      default -> {
      }
    }

    if (containsAny(normalizedQuery, "GIA TRI THANG DU", "TU BAN", "SUC LAO DONG")) {
      add(queries, "giá trị thặng dư trong nền kinh tế thị trường" + chapterText);
    }
  }

  private void addPhilosophyQueries(Set<String> queries, String normalizedQuery) {
    if (containsAny(normalizedQuery,
        "VAN DE CO BAN CUA TRIET HOC",
        "VAT CHAT CO TRUOC",
        "Y THUC CO TRUOC")) {
      add(queries, "vấn đề cơ bản của triết học và hai mặt của vấn đề cơ bản của triết học");
      add(queries, "chủ nghĩa duy vật và chủ nghĩa duy tâm trong việc giải quyết vấn đề cơ bản của triết học");
      add(queries, "triết học và vấn đề cơ bản của triết học Mác - Lênin");
    }

    if (containsAny(normalizedQuery, "DUY VAT", "DUY TAM")) {
      add(queries, "chủ nghĩa duy vật, chủ nghĩa duy tâm và vấn đề cơ bản của triết học");
      add(queries, "sự đối lập giữa chủ nghĩa duy vật và chủ nghĩa duy tâm");
    }

    if (containsAny(normalizedQuery, "BIEN CHUNG", "SIEU HINH")) {
      add(queries, "phương pháp biện chứng và phương pháp siêu hình trong lịch sử triết học");
      add(queries, "khái niệm biện chứng, siêu hình và phép biện chứng duy vật");
    }

    if (containsAny(normalizedQuery, "VAT CHAT", "Y THUC")) {
      add(queries, "phạm trù vật chất, ý thức và mối quan hệ biện chứng giữa vật chất và ý thức");
      add(queries, "quan điểm của triết học Mác - Lênin về vật chất và ý thức");
    }

    if (containsAny(normalizedQuery, "NHAN THUC", "CHAN LY")) {
      add(queries, "lý luận nhận thức duy vật biện chứng và vấn đề chân lý");
      add(queries, "các giai đoạn của quá trình nhận thức và tiêu chuẩn của chân lý");
    }
  }

  private void addPoliticalEconomyQueries(Set<String> queries, String normalizedQuery) {
    if (containsAny(normalizedQuery, "GIA TRI THANG DU", "THANG DU", "BOC LOT", "CONG NHAN")) {
      add(queries, "nguồn gốc và bản chất của giá trị thặng dư trong lý luận của C. Mác");
      add(queries, "sự sản xuất giá trị thặng dư và hàng hóa sức lao động");
      add(queries, "nhà tư bản chiếm đoạt giá trị thặng dư của lao động làm thuê");
    }

    if (containsAny(normalizedQuery, "HANG HOA SUC LAO DONG", "SUC LAO DONG")) {
      add(queries, "hàng hóa sức lao động và hai thuộc tính của hàng hóa sức lao động");
      add(queries, "điều kiện để sức lao động trở thành hàng hóa");
    }

    if (containsAny(normalizedQuery, "TU BAN BAT BIEN", "TU BAN KHA BIEN", "BAT BIEN", "KHA BIEN")) {
      add(queries, "tư bản bất biến và tư bản khả biến trong quá trình sản xuất giá trị thặng dư");
      add(queries, "khái niệm tư bản bất biến, tư bản khả biến và sự khác nhau giữa chúng");
      add(queries, "lý luận của C. Mác về giá trị thặng dư và tư bản");
    }

    if (containsAny(normalizedQuery, "TICH LUY TU BAN", "TICH LUY")) {
      add(queries, "tích lũy tư bản và các nhân tố ảnh hưởng đến quy mô tích lũy tư bản");
      add(queries, "quá trình tích lũy tư bản, tích tụ và tập trung tư bản");
    }

    if (containsAny(normalizedQuery, "CAU TAO HUU CO")) {
      add(queries, "cấu tạo hữu cơ của tư bản và xu hướng tăng cấu tạo hữu cơ");
      add(queries, "cấu tạo kỹ thuật, cấu tạo giá trị và cấu tạo hữu cơ của tư bản");
    }
  }

  private void add(Set<String> queries, String query) {
    String cleaned = query == null ? "" : query.replaceAll("\\s+", " ").trim();
    if (!cleaned.isBlank()) {
      queries.add(cleaned);
    }
  }

  private List<String> limit(Set<String> queries) {
    List<String> limited = new ArrayList<>();
    for (String query : queries) {
      if (limited.size() >= maxQueries) {
        break;
      }
      limited.add(query);
    }
    return limited;
  }

  private boolean containsAny(String value, String... candidates) {
    for (String candidate : candidates) {
      if (value.contains(candidate)) {
        return true;
      }
    }
    return false;
  }
}
