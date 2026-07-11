package com.example.essentialrag.ingestion.cleaning;

import com.example.essentialrag.ingestion.model.PageLines;
import com.example.essentialrag.ingestion.model.PageText;
import com.example.essentialrag.ingestion.support.TextbookStructureRules;
import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TextbookTextCleaner {

  public List<PageLines> clean(List<PageText> rawPages) {
    Set<String> repeatedNoiseLines = detectRepeatedHeaderFooterLines(rawPages);
    return rawPages.stream()
        .map(page -> cleanPage(page, repeatedNoiseLines))
        .toList();
  }

  private Set<String> detectRepeatedHeaderFooterLines(List<PageText> pages) {
    Map<String, Integer> counts = new HashMap<>();

    for (PageText page : pages) {
      List<String> lines = TextbookText.rawNonBlankLines(page.text());
      Set<String> pageCandidates = new HashSet<>();

      firstAndLastLines(lines, 3).stream()
          .map(TextbookText::cleanLine)
          .map(TextbookText::noiseKey)
          .filter(line -> !line.isBlank())
          .filter(line -> line.length() <= 90)
          .filter(line -> !TextbookStructureRules.isStructureHeading(line))
          .forEach(pageCandidates::add);

      pageCandidates.forEach(line -> counts.merge(line, 1, Integer::sum));
    }

    int minimumRepeats = Math.max(3, pages.size() / 8);
    Set<String> noise = new HashSet<>();
    counts.forEach((line, count) -> {
      if (count >= minimumRepeats) {
        noise.add(line);
      }
    });
    return noise;
  }

  private PageLines cleanPage(PageText page, Set<String> repeatedNoiseLines) {
    List<String> cleaned = new ArrayList<>();

    for (String rawLine : page.text().split("\\R")) {
      String line = TextbookText.cleanLine(rawLine);
      if (line.isBlank()) {
        if (!cleaned.isEmpty() && !cleaned.get(cleaned.size() - 1).isBlank()) {
          cleaned.add("");
        }
        continue;
      }

      String noiseKey = TextbookText.noiseKey(line);
      if (repeatedNoiseLines.contains(noiseKey)) {
        continue;
      }
      if (TextbookStructureRules.PAGE_NUMBER_PATTERN.matcher(line).matches()) {
        continue;
      }
      if (TextbookStructureRules.TOC_LINE_PATTERN.matcher(line).matches()) {
        continue;
      }

      cleaned.add(line);
    }

    TextbookText.trimBlankEdges(cleaned);
    return new PageLines(page.pageNumber(), cleaned);
  }

  private List<String> firstAndLastLines(List<String> lines, int count) {
    LinkedHashSet<String> selected = new LinkedHashSet<>();
    for (int i = 0; i < Math.min(count, lines.size()); i++) {
      selected.add(lines.get(i));
    }
    for (int i = Math.max(0, lines.size() - count); i < lines.size(); i++) {
      selected.add(lines.get(i));
    }
    return new ArrayList<>(selected);
  }
}
