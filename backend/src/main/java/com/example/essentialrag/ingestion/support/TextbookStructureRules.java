package com.example.essentialrag.ingestion.support;

import java.util.regex.Pattern;

public final class TextbookStructureRules {

  private static final Pattern REVIEW_QUESTIONS_HEADING =
      Pattern.compile("^([A-Z]\\s*[.)-]\\s*)?CAU HOI ON TAP\\b.*$");

  private static final Pattern DISCUSSION_HEADING =
      Pattern.compile("^([A-Z]\\s*[.)-]\\s*)?(VAN DE THAO LUAN|THAO LUAN)\\b.*$");

  private static final Pattern SUMMARY_HEADING =
      Pattern.compile("^([A-Z]\\s*[.)-]\\s*)?TOM TAT( CHUONG)?\\b.*$");

  private static final Pattern KEYWORDS_HEADING =
      Pattern.compile("^([A-Z]\\s*[.)-]\\s*)?(CAC )?THUAT NGU( CAN GHI NHO)?\\b.*$");

  private static final Pattern LEARNING_OBJECTIVE_HEADING =
      Pattern.compile("^([A-Z]\\s*[.)-]\\s*)?MUC TIEU\\b.*$");

  private static final Pattern BOX_HEADING =
      Pattern.compile("^HOP\\s+(\\d+(\\.\\d+)?|KIEN THUC)\\b.*$");

  public static final Pattern CHAPTER_PATTERN =
      Pattern.compile("^CHUONG\\s+([0-9IVXLCDM]+)\\b\\s*[:.\\-]?\\s*(.*)$");

  public static final Pattern ROMAN_SECTION_PATTERN =
      Pattern.compile("^[IVXLCDM]+\\s*[-.]\\s+.+$");

  public static final Pattern NUMBERED_SECTION_PATTERN =
      Pattern.compile("^\\d{1,2}\\s*[.)]\\s+.+$");

  public static final Pattern LOWER_LETTER_SECTION_PATTERN =
      Pattern.compile("^[a-z]\\)\\s+.+$");

  public static final Pattern PAGE_NUMBER_PATTERN =
      Pattern.compile("^\\s*\\d{1,4}\\s*$");

  public static final Pattern TOC_LINE_PATTERN =
      Pattern.compile("^.{3,}\\.{5,}\\s*\\d{1,4}\\s*$");

  private TextbookStructureRules() {
  }

  public static String blockTypeForHeading(String normalizedHeading) {
    if (REVIEW_QUESTIONS_HEADING.matcher(normalizedHeading).matches()) {
      return "review_question";
    }
    if (DISCUSSION_HEADING.matcher(normalizedHeading).matches()) {
      return "discussion_question";
    }
    if (SUMMARY_HEADING.matcher(normalizedHeading).matches()) {
      return "summary";
    }
    if (KEYWORDS_HEADING.matcher(normalizedHeading).matches()) {
      return "keyword";
    }
    if (LEARNING_OBJECTIVE_HEADING.matcher(normalizedHeading).matches()) {
      return "learning_objective";
    }
    if (BOX_HEADING.matcher(normalizedHeading).matches()) {
      return "box";
    }
    return null;
  }

  public static boolean isStructureHeading(String line) {
    String normalized = TextbookText.normalizeForMatching(line);
    return CHAPTER_PATTERN.matcher(normalized).matches()
        || ROMAN_SECTION_PATTERN.matcher(normalized).matches()
        || NUMBERED_SECTION_PATTERN.matcher(normalized).matches()
        || blockTypeForHeading(normalized) != null;
  }
}
