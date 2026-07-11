package com.example.essentialrag.service.retrieval;

import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class QueryIntentDetector {

  private static final Pattern CHAPTER_PATTERN = Pattern.compile("\\bCHUONG\\s+([0-9IVXLCDM]+)\\b");

  public RetrievalIntent detect(String query) {
    String normalized = TextbookText.normalizeForMatching(query == null ? "" : query);
    return new RetrievalIntent(
        detectSourceFile(normalized),
        detectChapterNumber(normalized),
        detectBlockType(normalized));
  }

  private String detectSourceFile(String normalizedQuery) {
    if (containsAny(normalizedQuery,
        "KINH TE CHINH TRI",
        "GIA TRI THANG DU",
        "TU BAN",
        "HANG HOA SUC LAO DONG",
        "TICH LUY TU BAN",
        "CAU TAO HUU CO",
        "LOI NHUAN",
        "DIA TO")) {
      return "triet_2.pdf";
    }

    if (containsAny(normalizedQuery,
        "TRIET HOC",
        "VAN DE CO BAN CUA TRIET HOC",
        "DUY VAT",
        "DUY TAM",
        "BIEN CHUNG",
        "VAT CHAT",
        "Y THUC",
        "CHAN LY",
        "LENIN")) {
      return "triet_1.pdf";
    }

    return null;
  }

  private String detectChapterNumber(String normalizedQuery) {
    Matcher matcher = CHAPTER_PATTERN.matcher(normalizedQuery);
    if (matcher.find()) {
      return matcher.group(1);
    }

    if (containsAny(normalizedQuery,
        "GIA TRI THANG DU",
        "TU BAN BAT BIEN",
        "TU BAN KHA BIEN",
        "CAU TAO HUU CO",
        "CONG THUC CHUNG CUA TU BAN")) {
      return "3";
    }

    if (containsAny(normalizedQuery,
        "VAN DE CO BAN CUA TRIET HOC",
        "CHU NGHIA DUY VAT",
        "CHU NGHIA DUY TAM",
        "PHUONG PHAP BIEN CHUNG",
        "NGUON GOC LY LUAN TRUC TIEP",
        "VAI TRO CUA V.I. LENIN")) {
      return "1";
    }

    return null;
  }

  private String detectBlockType(String normalizedQuery) {
    if (containsAny(normalizedQuery, "CAU HOI ON TAP", "ON TAP CHUONG")) {
      return "review_question";
    }
    if (containsAny(normalizedQuery, "TOM TAT", "TOM TAT CHUONG")) {
      return "summary";
    }
    if (containsAny(normalizedQuery, "THUAT NGU", "GHI NHO")) {
      return "keyword";
    }
    return null;
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
