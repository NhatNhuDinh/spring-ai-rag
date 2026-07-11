package com.example.essentialrag.service.retrieval;

import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class KeywordBooster {

  private static final Set<String> STOPWORDS = Set.of(
      "VA", "LA", "CUA", "CHO", "THE", "NAO", "NHU", "VI", "SAO", "GI", "GI?",
      "TRONG", "DUOC", "CAC", "NHUNG", "MOT", "HAI", "BA", "VE", "VOI", "TU",
      "CO", "KHONG", "HAY", "GOM", "NOI", "DUNG", "PHAN", "TICH", "GIAI", "THICH");

  public KeywordBoost score(Document document, QueryTransformation transformation) {
    return score(document, transformation.retrievalQueries());
  }

  public KeywordBoost score(Document document, List<String> retrievalQueries) {
    List<String> phrases = keywordPhrases(retrievalQueries);
    String searchable = searchableText(document);
    double score = 0.0;
    List<String> matched = new ArrayList<>();

    for (String phrase : phrases) {
      if (searchable.contains(phrase)) {
        score += phraseWeight(phrase);
        matched.add(phrase);
      }
    }

    return new KeywordBoost(score, matched);
  }

  private double phraseWeight(String phrase) {
    int tokens = phrase.split("\\s+").length;
    if (tokens >= 4) {
      return 0.06;
    }
    if (tokens == 3) {
      return 0.04;
    }
    if (tokens == 2) {
      return 0.02;
    }
    return 0.004;
  }

  private String searchableText(Document document) {
    Map<String, Object> metadata = document.getMetadata();
    return TextbookText.normalizeForMatching("""
        %s
        %s
        %s
        %s
        %s
        %s
        """.formatted(
        document.getText(),
        value(metadata, "book_title"),
        value(metadata, "chapter_title"),
        value(metadata, "section_path"),
        value(metadata, "section_title"),
        value(metadata, "block_type")));
  }

  private List<String> keywordPhrases(List<String> retrievalQueries) {
    String normalized = TextbookText.normalizeForMatching(String.join(" ", retrievalQueries));
    Set<String> phrases = new LinkedHashSet<>();

    addIfPresent(phrases, normalized, "VAN DE CO BAN CUA TRIET HOC");
    addIfPresent(phrases, normalized, "CHU NGHIA DUY VAT");
    addIfPresent(phrases, normalized, "CHU NGHIA DUY TAM");
    addIfPresent(phrases, normalized, "PHUONG PHAP BIEN CHUNG");
    addIfPresent(phrases, normalized, "PHUONG PHAP SIEU HINH");
    addIfPresent(phrases, normalized, "VAT CHAT");
    addIfPresent(phrases, normalized, "Y THUC");
    addIfPresent(phrases, normalized, "NHAN THUC");
    addIfPresent(phrases, normalized, "CHAN LY");
    addIfPresent(phrases, normalized, "GIA TRI THANG DU");
    addIfPresent(phrases, normalized, "HANG HOA SUC LAO DONG");
    addIfPresent(phrases, normalized, "SUC LAO DONG");
    addIfPresent(phrases, normalized, "LAO DONG LAM THUE");
    addIfPresent(phrases, normalized, "NHA TU BAN");
    addIfPresent(phrases, normalized, "TU BAN BAT BIEN");
    addIfPresent(phrases, normalized, "TU BAN KHA BIEN");
    addIfPresent(phrases, normalized, "CONG THUC CHUNG CUA TU BAN");
    addIfPresent(phrases, normalized, "TICH LUY TU BAN");
    addIfPresent(phrases, normalized, "CAU TAO HUU CO");

    if (phrases.isEmpty()) {
      phrases.addAll(generatedPhrases(normalized));
    }

    return new ArrayList<>(phrases);
  }

  private void addIfPresent(Set<String> phrases, String normalized, String phrase) {
    if (normalized.contains(phrase)) {
      phrases.add(phrase);
    }
  }

  private List<String> generatedPhrases(String normalized) {
    List<String> tokens = List.of(normalized.split("\\s+")).stream()
        .filter(token -> token.length() >= 3)
        .filter(token -> !STOPWORDS.contains(token))
        .toList();
    Set<String> phrases = new LinkedHashSet<>();
    for (int size = 3; size >= 2; size--) {
      for (int i = 0; i <= tokens.size() - size; i++) {
        phrases.add(String.join(" ", tokens.subList(i, i + size)));
        if (phrases.size() >= 12) {
          return new ArrayList<>(phrases);
        }
      }
    }
    if (phrases.isEmpty()) {
      phrases.addAll(tokens.stream().limit(8).toList());
    }
    return new ArrayList<>(phrases);
  }

  private String value(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value == null ? "" : value.toString();
  }

  public record KeywordBoost(double score, List<String> matchedTerms) {
  }
}
