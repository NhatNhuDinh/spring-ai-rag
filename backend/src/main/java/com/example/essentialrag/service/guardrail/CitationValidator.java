package com.example.essentialrag.service.guardrail;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationValidator {

  private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S\\d+]");

  public CitationValidationResult validate(String answer, String ragContext, boolean citationRequired) {
    Set<String> answerCitations = extractCitations(answer);
    Set<String> contextCitations = extractCitations(ragContext);
    Set<String> invalid = new LinkedHashSet<>(answerCitations);
    invalid.removeAll(contextCitations);

    boolean missingRequiredCitation = citationRequired && answerCitations.isEmpty();
    boolean valid = invalid.isEmpty() && !missingRequiredCitation;

    return new CitationValidationResult(
        valid,
        missingRequiredCitation,
        answerCitations,
        contextCitations,
        invalid);
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
}
