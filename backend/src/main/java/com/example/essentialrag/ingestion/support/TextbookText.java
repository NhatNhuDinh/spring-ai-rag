package com.example.essentialrag.ingestion.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextbookText {

  private TextbookText() {
  }

  public static String cleanLine(String rawLine) {
    return Normalizer.normalize(rawLine, Normalizer.Form.NFC)
        .replace('\u00A0', ' ')
        .replace('\u200B', ' ')
        .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
        .replaceAll("[ \\t]+", " ")
        .trim();
  }

  public static String cleanParagraph(String text) {
    return text
        .replaceAll("-\\s+", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static String normalizeForMatching(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    return decomposed
        .replaceAll("\\p{M}", "")
        .replace('Đ', 'D')
        .replace('đ', 'd')
        .toUpperCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .trim();
  }

  public static String noiseKey(String line) {
    return normalizeForMatching(line).replaceAll("\\s+", " ").trim();
  }

  public static List<String> rawNonBlankLines(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\\R")) {
      String cleaned = cleanLine(line);
      if (!cleaned.isBlank()) {
        lines.add(cleaned);
      }
    }
    return lines;
  }

  public static List<String> splitSentences(String text) {
    List<String> sentences = new ArrayList<>();
    Matcher matcher = Pattern.compile("[^.!?;:]+[.!?;:]?").matcher(text);
    while (matcher.find()) {
      String sentence = matcher.group().trim();
      if (!sentence.isBlank()) {
        sentences.add(sentence);
      }
    }
    if (sentences.isEmpty()) {
      sentences.add(text);
    }
    return sentences;
  }

  public static int tokenCount(String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    return text.trim().split("\\s+").length;
  }

  public static void trimBlankEdges(List<String> lines) {
    while (!lines.isEmpty() && lines.get(0).isBlank()) {
      lines.remove(0);
    }
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
      lines.remove(lines.size() - 1);
    }
  }

  public static String parentId(String sourceFile, String chapterNumber, String primarySection) {
    return "parent:%s:%s:%s".formatted(
        safeIdPart(sourceFile),
        safeIdPart(chapterNumber),
        shortHash(primarySection));
  }

  public static String safeIdPart(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return normalizeForMatching(value)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
  }

  public static String shortHash(String value) {
    return sha256(value == null ? "" : value).substring(0, 12);
  }

  public static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append("%02x".formatted(b));
      }
      return hex.toString();
    }
    catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
