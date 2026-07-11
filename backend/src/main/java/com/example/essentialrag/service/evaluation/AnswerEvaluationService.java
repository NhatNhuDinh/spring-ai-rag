package com.example.essentialrag.service.evaluation;

import com.example.essentialrag.ingestion.support.TextbookText;
import com.example.essentialrag.service.PhilosophyChatService;
import com.example.essentialrag.service.TextbookRetrievalService;
import com.example.essentialrag.service.guardrail.CitationValidationResult;
import com.example.essentialrag.service.guardrail.CitationValidator;
import com.example.essentialrag.service.guardrail.ContextQualityEvaluator;
import com.example.essentialrag.service.guardrail.ContextQualityResult;
import com.example.essentialrag.service.retrieval.RetrievalContextPackager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnswerEvaluationService {

  private static final TypeReference<List<AnswerTestCase>> TEST_CASE_LIST = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;
  private final Resource testCasesResource;
  private final TextbookRetrievalService retrievalService;
  private final RetrievalContextPackager contextPackager;
  private final PhilosophyChatService chatService;
  private final ContextQualityEvaluator contextQualityEvaluator;
  private final CitationValidator citationValidator;

  public AnswerEvaluationService(
      @Value("classpath:evaluation/answer-test-cases.json") Resource testCasesResource,
      TextbookRetrievalService retrievalService,
      RetrievalContextPackager contextPackager,
      PhilosophyChatService chatService,
      ContextQualityEvaluator contextQualityEvaluator,
      CitationValidator citationValidator) {

    this.objectMapper = new ObjectMapper().findAndRegisterModules();
    this.testCasesResource = testCasesResource;
    this.retrievalService = retrievalService;
    this.contextPackager = contextPackager;
    this.chatService = chatService;
    this.contextQualityEvaluator = contextQualityEvaluator;
    this.citationValidator = citationValidator;
  }

  public List<AnswerTestCase> loadDefaultTestCases() {
    try {
      return objectMapper.readValue(testCasesResource.getInputStream(), TEST_CASE_LIST);
    }
    catch (Exception ex) {
      throw new IllegalStateException("Failed to load answer evaluation test cases.", ex);
    }
  }

  public AnswerEvaluationSummary runDefaultTestCases() {
    return run(loadDefaultTestCases());
  }

  public AnswerEvaluationSummary run(List<AnswerTestCase> testCases) {
    List<AnswerEvaluationResult> results = new ArrayList<>();
    for (AnswerTestCase testCase : testCases) {
      results.add(runOne(testCase));
    }
    int passed = (int) results.stream().filter(AnswerEvaluationResult::passed).count();
    return new AnswerEvaluationSummary(
        results.size(),
        passed,
        results.size() - passed,
        results);
  }

  public AnswerEvaluationResult runOne(AnswerTestCase testCase) {
    List<Document> documents = retrievalService.retrieveWithParentContext(testCase.question(), null, null);
    String context = contextPackager.packageContext(testCase.question(), documents);
    ContextQualityResult quality = contextQualityEvaluator.evaluate(documents);
    PhilosophyChatService.ChatAnswer chatAnswer = chatService.chatWithPreparedContext(
        testCase.question(),
        "eval-" + testCase.id() + "-" + UUID.randomUUID(),
        documents,
        context);

    boolean citationRequired = Boolean.TRUE.equals(testCase.mustHaveCitation())
        && !Boolean.TRUE.equals(testCase.shouldRefuse());
    CitationValidationResult citationValidation = citationValidator.validate(
        chatAnswer.answer(),
        context,
        citationRequired);

    boolean expectedSourceHit = expectedMetadataHit(documents, "source_file", testCase.expectedSourceFile());
    boolean expectedChapterHit = expectedMetadataHit(documents, "chapter_number", testCase.expectedChapter());
    List<String> missingMustMention = missingTerms(chatAnswer.answer(), testCase.mustMention());
    List<String> unexpectedMentions = presentTerms(chatAnswer.answer(), testCase.mustNotMention());
    boolean refusalPassed = refusalPassed(chatAnswer.answer(), testCase);

    boolean passed = expectedSourceHit
        && expectedChapterHit
        && missingMustMention.isEmpty()
        && unexpectedMentions.isEmpty()
        && citationValidation.valid()
        && refusalPassed;

    return new AnswerEvaluationResult(
        testCase.id(),
        testCase.question(),
        passed,
        chatAnswer.answer(),
        documents.size(),
        quality,
        citationValidation,
        expectedSourceHit,
        expectedChapterHit,
        missingMustMention,
        unexpectedMentions,
        refusalPassed);
  }

  private boolean expectedMetadataHit(List<Document> documents, String metadataKey, String expectedValue) {
    if (expectedValue == null || expectedValue.isBlank()) {
      return true;
    }
    return documents.stream()
        .map(Document::getMetadata)
        .map(metadata -> string(metadata, metadataKey))
        .anyMatch(expectedValue::equals);
  }

  private List<String> missingTerms(String answer, List<String> terms) {
    if (terms == null || terms.isEmpty()) {
      return List.of();
    }
    String normalizedAnswer = TextbookText.normalizeForMatching(answer);
    return terms.stream()
        .filter(term -> !normalizedAnswer.contains(TextbookText.normalizeForMatching(term)))
        .toList();
  }

  private List<String> presentTerms(String answer, List<String> terms) {
    if (terms == null || terms.isEmpty()) {
      return List.of();
    }
    String normalizedAnswer = TextbookText.normalizeForMatching(answer);
    return terms.stream()
        .filter(term -> normalizedAnswer.contains(TextbookText.normalizeForMatching(term)))
        .toList();
  }

  private boolean refusalPassed(String answer, AnswerTestCase testCase) {
    if (!Boolean.TRUE.equals(testCase.shouldRefuse())) {
      return true;
    }
    List<String> refusalTerms = testCase.refusalMustMention();
    if (refusalTerms == null || refusalTerms.isEmpty()) {
      refusalTerms = List.of("ngu canh", "chua du");
    }
    return missingTerms(answer, refusalTerms).isEmpty();
  }

  private String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value == null ? null : value.toString();
  }
}
