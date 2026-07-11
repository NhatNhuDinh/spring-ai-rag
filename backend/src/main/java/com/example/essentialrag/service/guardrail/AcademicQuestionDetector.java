package com.example.essentialrag.service.guardrail;

import com.example.essentialrag.ingestion.support.TextbookText;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AcademicQuestionDetector {

  private static final Set<String> GREETING_TERMS = Set.of(
      "XIN CHAO",
      "CHAO",
      "HELLO",
      "HI",
      "BAN LA AI",
      "TRO LY MON GI");

  private static final Set<String> ACADEMIC_TERMS = Set.of(
      "TRIET HOC",
      "KINH TE CHINH TRI",
      "MAC",
      "LENIN",
      "DUY VAT",
      "DUY TAM",
      "BIEN CHUNG",
      "SIEU HINH",
      "VAT CHAT",
      "Y THUC",
      "NHAN THUC",
      "CHAN LY",
      "GIA TRI THANG DU",
      "TU BAN",
      "SUC LAO DONG",
      "HANG HOA",
      "BOC LOT",
      "CONG NHAN",
      "LAO DONG LAM THUE",
      "CAU HOI ON TAP",
      "TOM TAT",
      "THUAT NGU");

  public boolean isLikelyAcademicQuestion(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }

    String normalized = TextbookText.normalizeForMatching(message);
    if (GREETING_TERMS.stream().anyMatch(normalized::contains)
        && ACADEMIC_TERMS.stream().noneMatch(normalized::contains)) {
      return false;
    }

    return ACADEMIC_TERMS.stream().anyMatch(normalized::contains);
  }
}
