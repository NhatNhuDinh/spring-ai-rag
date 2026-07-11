package com.example.essentialrag.service.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTests {

  private final CitationValidator validator = new CitationValidator();

  @Test
  void acceptsAnswerCitationsThatExistInContext() {
    CitationValidationResult result = validator.validate(
        "Theo [S1], vật chất có trước ý thức.",
        "RAG_CONTEXT\n[S1]\nNội dung: ...\n[S2]\nNội dung: ...",
        true);

    assertThat(result.valid()).isTrue();
    assertThat(result.invalidCitations()).isEmpty();
  }

  @Test
  void rejectsMissingRequiredCitationAndInventedCitation() {
    CitationValidationResult missing = validator.validate(
        "Vật chất có trước ý thức.",
        "RAG_CONTEXT\n[S1]\nNội dung: ...",
        true);
    CitationValidationResult invented = validator.validate(
        "Theo [S9], vật chất có trước ý thức.",
        "RAG_CONTEXT\n[S1]\nNội dung: ...",
        true);

    assertThat(missing.valid()).isFalse();
    assertThat(missing.missingRequiredCitation()).isTrue();
    assertThat(invented.valid()).isFalse();
    assertThat(invented.invalidCitations()).containsExactly("[S9]");
  }

  @Test
  void rejectsSubstantivePassagesWithoutTheirOwnCitation() {
    CitationValidationResult result = validator.validate(
        "Vật chất tồn tại khách quan và độc lập với ý thức [S1]. "
            + "Ý thức có nguồn gốc tự nhiên và nguồn gốc xã hội.",
        "RAG_CONTEXT\n[S1]\nNội dung: ...\n[S2]\nNội dung: ...",
        true);

    assertThat(result.valid()).isFalse();
    assertThat(result.uncitedPassages())
        .containsExactly("Ý thức có nguồn gốc tự nhiên và nguồn gốc xã hội.");
  }
}
