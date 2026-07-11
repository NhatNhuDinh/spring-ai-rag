package com.example.essentialrag.service.guardrail;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationValidator {

  private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S\\d+]");
  private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+");

  public CitationValidationResult validate(String answer, String ragContext, boolean citationRequired) {
    Set<String> answerCitations = extractCitations(answer);
    Set<String> contextCitations = extractCitations(ragContext);
    Set<String> invalid = new LinkedHashSet<>(answerCitations);
    invalid.removeAll(contextCitations);

    boolean missingRequiredCitation = citationRequired && answerCitations.isEmpty();
    Set<String> uncitedPassages = citationRequired
        ? findUncitedPassages(answer)
        : Set.of();
    boolean valid = invalid.isEmpty()
        && !missingRequiredCitation
        && uncitedPassages.isEmpty();

    return new CitationValidationResult(
        valid,
        missingRequiredCitation,
        answerCitations,
        contextCitations,
        invalid,
        uncitedPassages);
  }

  public Set<String> extractCitations(String text) {
    Set<String> citations = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return citations;
    }
    Matcher matcher = CITATION_PATTERN.matcher(text);
    while (matcher.find()) {
      citations.add(matcher.group());
    }
    return citations;
  }

  private Set<String> findUncitedPassages(String answer) {
    Set<String> uncited = new LinkedHashSet<>();
    if (answer == null || answer.isBlank()) {
      return uncited;
    }

    for (String line : answer.split("\\R+")) {
      String cleanedLine = line
          .replaceFirst("^\\s*(?:#{1,6}\\s*|[-*+]\\s+|\\d+[.)]\\s+)", "")
          .trim();
      if (cleanedLine.isBlank() || cleanedLine.endsWith(":")) {
        continue;
      }
      for (String sentence : SENTENCE_BOUNDARY.split(cleanedLine)) {
        String passage = sentence.trim();
        if (isSubstantiveClaim(passage) && extractCitations(passage).isEmpty()) {
          uncited.add(passage);
        }
      }
    }
    return uncited;
  }

  private boolean isSubstantiveClaim(String passage) {
    if (passage.isBlank() || passage.split("\\s+").length < 6) {
      return false;
    }
    String normalized = passage.toLowerCase(Locale.ROOT);
    return !(normalized.contains("ngữ cảnh giáo trình") && normalized.contains("chưa đủ"));
  }
}
