package com.example.essentialrag.ingestion.parsing;

import com.example.essentialrag.ingestion.model.PageLines;
import com.example.essentialrag.ingestion.model.TextbookBlock;
import com.example.essentialrag.ingestion.model.TextbookProfile;
import com.example.essentialrag.ingestion.support.TextbookStructureRules;
import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

@Component
public class TextbookStructureParser {

  public List<TextbookBlock> parse(List<PageLines> pages, TextbookProfile profile) {
    StructureState state = new StructureState();
    List<TextbookBlock> blocks = new ArrayList<>();
    ParagraphBuffer paragraph = new ParagraphBuffer();

    for (PageLines page : pages) {
      for (String line : page.lines()) {
        if (line.isBlank()) {
          flushParagraph(blocks, paragraph, state, profile);
          continue;
        }

        Heading heading = detectHeading(line, state);
        if (heading != null) {
          flushParagraph(blocks, paragraph, state, profile);
          state.apply(heading);
          continue;
        }

        paragraph.append(line, page.pageNumber(), shouldJoinLine(paragraph.text(), line));
        if (endsParagraph(line)) {
          flushParagraph(blocks, paragraph, state, profile);
        }
      }
      flushParagraph(blocks, paragraph, state, profile);
    }

    flushParagraph(blocks, paragraph, state, profile);
    return blocks;
  }

  private void flushParagraph(
      List<TextbookBlock> blocks,
      ParagraphBuffer paragraph,
      StructureState state,
      TextbookProfile profile) {

    if (paragraph.isEmpty()) {
      return;
    }

    String text = TextbookText.cleanParagraph(paragraph.text());
    int pageStart = paragraph.pageStart();
    int pageEnd = paragraph.pageEnd();
    paragraph.clear();

    if (text.isBlank() || TextbookText.tokenCount(text) < 6) {
      return;
    }

    blocks.add(new TextbookBlock(
        text,
        state.blockType(),
        pageStart,
        pageEnd,
        state.chapterNumber(),
        state.chapterTitle(),
        state.sectionPath(),
        state.sectionTitle(),
        TextbookText.parentId(profile.sourceFile(), state.chapterNumber(), state.primarySection())));
  }

  private Heading detectHeading(String line, StructureState state) {
    String normalized = TextbookText.normalizeForMatching(line);
    Matcher chapterMatcher = TextbookStructureRules.CHAPTER_PATTERN.matcher(normalized);
    if (chapterMatcher.matches()) {
      String number = chapterMatcher.group(1);
      String title = chapterMatcher.group(2).isBlank()
          ? line
          : line.substring(chapterMatcher.start(2)).trim();
      return Heading.chapter(number, title);
    }

    if (state.waitingForChapterTitle() && looksLikeTitle(line)) {
      return Heading.chapterTitle(line);
    }

    String blockType = TextbookStructureRules.blockTypeForHeading(normalized);
    if (blockType != null) {
      return Heading.section(2, line, blockType);
    }

    if (TextbookStructureRules.ROMAN_SECTION_PATTERN.matcher(normalized).matches()) {
      return Heading.section(2, line, "main_content");
    }
    if (!isListContentBlock(state.blockType())) {
      if (TextbookStructureRules.NUMBERED_SECTION_PATTERN.matcher(normalized).matches()) {
        return Heading.section(3, line, nestedBlockType(state.blockType()));
      }
      if (TextbookStructureRules.LOWER_LETTER_SECTION_PATTERN.matcher(line).matches()) {
        return Heading.section(4, line, nestedBlockType(state.blockType()));
      }
    }
    if (state.blockType().equals("main_content") && looksLikeStandaloneHeading(line)) {
      return Heading.section(2, line, "main_content");
    }

    return null;
  }

  private String nestedBlockType(String currentBlockType) {
    return switch (currentBlockType) {
      case "review_question", "discussion_question", "summary", "keyword", "learning_objective" -> currentBlockType;
      default -> "main_content";
    };
  }

  private boolean isListContentBlock(String currentBlockType) {
    return switch (currentBlockType) {
      case "review_question", "discussion_question", "summary", "keyword" -> true;
      default -> false;
    };
  }

  private boolean looksLikeTitle(String line) {
    return looksLikeStandaloneHeading(line);
  }

  private boolean looksLikeStandaloneHeading(String line) {
    if (line.length() < 8 || line.length() > 140) {
      return false;
    }

    String normalized = TextbookText.normalizeForMatching(line);
    if (normalized.endsWith(".") || normalized.endsWith(",") || normalized.endsWith(";")) {
      return false;
    }

    long letters = line.chars().filter(Character::isLetter).count();
    if (letters < 6) {
      return false;
    }

    if (TextbookText.tokenCount(line) > 10) {
      return false;
    }
    if (line.contains(",") || line.contains("...") || normalized.contains(" TOAN TAP")
        || normalized.matches(".*\\bT\\.?\\d+\\b.*") || normalized.matches(".*\\bTR\\.?\\s*\\d+.*")) {
      return false;
    }

    long upperLetters = line.chars()
        .filter(Character::isLetter)
        .filter(Character::isUpperCase)
        .count();
    return letters > 0 && ((double) upperLetters / letters) > 0.85;
  }

  private boolean shouldJoinLine(String currentParagraph, String nextLine) {
    if (currentParagraph.isBlank()) {
      return false;
    }
    String current = currentParagraph.stripTrailing();
    if (current.endsWith("-")) {
      return true;
    }
    return !endsParagraph(current) && !TextbookStructureRules.isStructureHeading(nextLine);
  }

  private boolean endsParagraph(String line) {
    String trimmed = line.stripTrailing();
    return trimmed.endsWith(".")
        || trimmed.endsWith("?")
        || trimmed.endsWith("!")
        || trimmed.endsWith(":")
        || trimmed.endsWith(";")
        || trimmed.endsWith(")")
        || trimmed.endsWith("\"");
  }

  private static final class Heading {

    private final int level;
    private final String text;
    private final String blockType;
    private final String chapterNumber;
    private final boolean chapterTitleOnly;

    private Heading(int level, String text, String blockType, String chapterNumber, boolean chapterTitleOnly) {
      this.level = level;
      this.text = text;
      this.blockType = blockType;
      this.chapterNumber = chapterNumber;
      this.chapterTitleOnly = chapterTitleOnly;
    }

    static Heading chapter(String chapterNumber, String title) {
      return new Heading(1, title, "main_content", chapterNumber, false);
    }

    static Heading chapterTitle(String title) {
      return new Heading(1, title, "main_content", null, true);
    }

    static Heading section(int level, String title, String blockType) {
      return new Heading(level, title, blockType, null, false);
    }
  }

  private static final class StructureState {

    private String chapterNumber = "unknown";
    private String chapterTitle = "";
    private String blockType = "main_content";
    private final Map<Integer, String> sectionPath = new LinkedHashMap<>();
    private boolean waitingForChapterTitle;

    void apply(Heading heading) {
      if (heading.chapterTitleOnly) {
        this.chapterTitle = heading.text;
        this.waitingForChapterTitle = false;
        return;
      }

      if (heading.level == 1 && heading.chapterNumber != null) {
        this.chapterNumber = heading.chapterNumber;
        this.chapterTitle = heading.text;
        this.blockType = "main_content";
        this.sectionPath.clear();
        this.waitingForChapterTitle = heading.text.equalsIgnoreCase(heading.chapterNumber)
            || heading.text.toUpperCase(Locale.ROOT).startsWith("CH");
        return;
      }

      this.waitingForChapterTitle = false;
      this.blockType = heading.blockType == null || heading.blockType.isBlank()
          ? "main_content"
          : heading.blockType;
      this.sectionPath.entrySet().removeIf(entry -> entry.getKey() >= heading.level);
      this.sectionPath.put(heading.level, heading.text);
    }

    boolean waitingForChapterTitle() {
      return waitingForChapterTitle;
    }

    String chapterNumber() {
      return chapterNumber;
    }

    String chapterTitle() {
      return chapterTitle;
    }

    String blockType() {
      return blockType;
    }

    String sectionPath() {
      return String.join(" > ", sectionPath.values());
    }

    String sectionTitle() {
      if (sectionPath.isEmpty()) {
        return "";
      }
      List<String> values = new ArrayList<>(sectionPath.values());
      return values.get(values.size() - 1);
    }

    String primarySection() {
      return sectionPath.getOrDefault(2, sectionPath());
    }
  }

  private static final class ParagraphBuffer {

    private final StringBuilder text = new StringBuilder();
    private int pageStart;
    private int pageEnd;

    void append(String line, int pageNumber, boolean joinWithPrevious) {
      if (pageStart == 0) {
        pageStart = pageNumber;
      }
      pageEnd = pageNumber;

      if (!text.isEmpty()) {
        if (joinWithPrevious && text.charAt(text.length() - 1) == '-') {
          text.deleteCharAt(text.length() - 1);
        }
        else {
          text.append(' ');
        }
      }
      text.append(line);
    }

    String text() {
      return text.toString();
    }

    int pageStart() {
      return pageStart;
    }

    int pageEnd() {
      return pageEnd;
    }

    boolean isEmpty() {
      return text.isEmpty();
    }

    void clear() {
      text.setLength(0);
      pageStart = 0;
      pageEnd = 0;
    }
  }
}
