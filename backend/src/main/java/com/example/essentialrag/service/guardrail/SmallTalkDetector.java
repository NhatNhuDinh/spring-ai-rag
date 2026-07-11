package com.example.essentialrag.service.guardrail;

import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SmallTalkDetector {

  private static final Set<String> SMALL_TALK_MESSAGES = Set.of(
      "XIN CHAO",
      "XIN CHAO BAN",
      "CHAO",
      "CHAO BAN",
      "HELLO",
      "HI",
      "CAM ON",
      "CAM ON BAN",
      "THANK YOU",
      "BAN LA AI",
      "BAN CO KHOE KHONG",
      "TRO LY MON GI",
      "BAN LA TRO LY MON GI");

  public boolean isSmallTalk(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }

    String normalized = TextbookText.normalizeForMatching(message)
        .replaceAll("[^A-Z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return SMALL_TALK_MESSAGES.contains(normalized);
  }
}
