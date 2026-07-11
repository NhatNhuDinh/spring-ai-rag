package com.example.essentialrag.service.streaming;

import com.example.essentialrag.api.dto.ChatStreamEvent;
import com.example.essentialrag.service.TextbookRetrievalService;
import com.example.essentialrag.service.generation.AnswerGenerationService;
import com.example.essentialrag.service.guardrail.AnswerGuardrailService;
import com.example.essentialrag.service.guardrail.CitationValidationResult;
import com.example.essentialrag.service.guardrail.CitationValidator;
import com.example.essentialrag.service.guardrail.ContextQualityEvaluator;
import com.example.essentialrag.service.guardrail.ContextQualityResult;
import com.example.essentialrag.service.guardrail.SmallTalkDetector;
import com.example.essentialrag.service.memory.ConversationMemoryService;
import com.example.essentialrag.service.retrieval.RetrievalContextPackager;
import com.example.essentialrag.service.retrieval.TextbookRetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhilosophyChatStreamingServiceTests {

  @Mock private AnswerGenerationService answerGenerationService;
  @Mock private ConversationMemoryService conversationMemoryService;
  @Mock private TextbookRetrievalService retrievalService;
  @Mock private RetrievalContextPackager contextPackager;
  @Mock private SmallTalkDetector smallTalkDetector;
  @Mock private ContextQualityEvaluator contextQualityEvaluator;
  @Mock private CitationValidator citationValidator;
  @Mock private AnswerGuardrailService answerGuardrailService;

  private PhilosophyChatStreamingService service;

  @BeforeEach
  void setUp() {
    service = service(Duration.ofSeconds(2));
  }

  @Test
  void streamsGroundedAnswerAndEvaluatesOnlySeedDocuments() {
    String question = "What is surplus value?";
    String context = "RAG_CONTEXT\n[S1]\nContent";
    Document seed = document("seed", "seed");
    Document sibling = document("sibling", "parent_context");
    stubRagPreparation(question, context, List.of(seed), List.of(seed, sibling), sufficientQuality());
    when(answerGuardrailService.groundedSystemPrompt(context)).thenReturn("grounded-system");
    when(answerGenerationService.streamGrounded(eq(question), eq("grounded-system"), anyList()))
        .thenReturn(Flux.just("Surplus value ", "is explained here. [S1]"));
    when(citationValidator.validate("Surplus value is explained here. [S1]", context, true))
        .thenReturn(validCitation());

    List<ChatStreamEvent> events = collect(service.stream(question, "conversation-1"));

    assertThat(events).extracting(ChatStreamEvent::type).containsExactly(
        "start", "status", "status", "sources", "status",
        "delta", "delta", "status", "done");
    verify(contextQualityEvaluator).evaluate(List.of(seed));
    verify(contextPackager).packageContext(question, List.of(seed, sibling));
  }

  @Test
  void resetsDraftAndStreamsCorrectionWhenCitationIsInvalid() {
    String question = "What is matter?";
    String context = "RAG_CONTEXT\n[S1]\nContent";
    Document seed = document("seed", "seed");
    stubRagPreparation(question, context, List.of(seed), List.of(seed), sufficientQuality());
    when(answerGuardrailService.groundedSystemPrompt(context)).thenReturn("grounded-system");
    when(answerGenerationService.streamGrounded(eq(question), eq("grounded-system"), anyList()))
        .thenReturn(Flux.just("Uncited draft answer"));

    CitationValidationResult invalid = new CitationValidationResult(
        false, true, Set.of(), Set.of("[S1]"), Set.of(), Set.of("Uncited draft answer"));
    when(citationValidator.validate("Uncited draft answer", context, true)).thenReturn(invalid);
    when(answerGuardrailService.citationCorrectionPrompt(
        question, context, "Uncited draft answer", invalid)).thenReturn("correction-prompt");
    when(answerGenerationService.streamCorrection("correction-prompt"))
        .thenReturn(Flux.just("Corrected answer [S1]"));
    when(citationValidator.validate("Corrected answer [S1]", context, true)).thenReturn(validCitation());

    List<ChatStreamEvent> events = collect(service.stream(question, "conversation-2"));

    assertThat(events).extracting(ChatStreamEvent::type).containsSubsequence(
        "delta", "status", "reset", "status", "delta", "status", "done");
    assertThat(events.get(events.size() - 1).corrected()).isTrue();
  }

  @Test
  void refusesBeforeCallingModelWhenSeedContextIsWeak() {
    String question = "Question outside the textbook";
    String context = "RAG_CONTEXT";
    ContextQualityResult weak = new ContextQualityResult(
        false, "no_retrieved_sources", 0, 0.0, 0.0, false);
    stubRagPreparation(question, context, List.of(), List.of(), weak);
    when(answerGuardrailService.refusalMessage(weak)).thenReturn("Insufficient context.");

    List<ChatStreamEvent> events = collect(service.stream(question, "conversation-3"));

    assertThat(events).extracting(ChatStreamEvent::type).containsExactly(
        "start", "status", "status", "refusal", "done");
    verify(answerGenerationService, never()).streamGrounded(any(), any(), anyList());
  }

  @Test
  void sendsOnlyExplicitSmallTalkToGeneralGeneration() {
    when(smallTalkDetector.isSmallTalk("xin chào")).thenReturn(true);
    when(answerGenerationService.streamGeneral(eq("xin chào"), anyList()))
        .thenReturn(Flux.just("Xin chào!"));

    List<ChatStreamEvent> events = collect(service.stream("xin chào", "conversation-4"));

    assertThat(events).extracting(ChatStreamEvent::type)
        .containsExactly("start", "status", "status", "delta", "done");
    verify(retrievalService, never()).retrieve(any(), any(), any());
  }

  @Test
  void convertsModelTimeoutToErrorEvent() {
    service = service(Duration.ofMillis(30));
    when(smallTalkDetector.isSmallTalk("xin chào")).thenReturn(true);
    when(answerGenerationService.streamGeneral(eq("xin chào"), anyList())).thenReturn(Flux.never());

    List<ChatStreamEvent> events = collect(service.stream("xin chào", "conversation-5"));

    assertThat(events.get(events.size() - 1).type()).isEqualTo("error");
    assertThat(events.get(events.size() - 1).errorCode()).isEqualTo("MODEL_TIMEOUT");
  }

  private PhilosophyChatStreamingService service(Duration timeout) {
    return new PhilosophyChatStreamingService(
        answerGenerationService,
        conversationMemoryService,
        retrievalService,
        contextPackager,
        smallTalkDetector,
        contextQualityEvaluator,
        citationValidator,
        answerGuardrailService,
        Duration.ofHours(1),
        timeout);
  }

  private void stubRagPreparation(
      String question,
      String context,
      List<Document> seeds,
      List<Document> contextDocuments,
      ContextQualityResult quality) {

    when(smallTalkDetector.isSmallTalk(question)).thenReturn(false);
    when(retrievalService.retrieve(question, null, null))
        .thenReturn(new TextbookRetrievalResult(seeds, contextDocuments));
    when(contextPackager.packageContext(question, contextDocuments)).thenReturn(context);
    when(contextQualityEvaluator.evaluate(seeds)).thenReturn(quality);
  }

  private List<ChatStreamEvent> collect(Flux<ChatStreamEvent> events) {
    return events.collectList().block(Duration.ofSeconds(3));
  }

  private ContextQualityResult sufficientQuality() {
    return new ContextQualityResult(true, "sufficient", 1, 0.02, 0.8, true);
  }

  private CitationValidationResult validCitation() {
    return new CitationValidationResult(
        true, false, Set.of("[S1]"), Set.of("[S1]"), Set.of(), Set.of());
  }

  private Document document(String id, String role) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source_file", "triet_2.pdf");
    metadata.put("book_title", "Political Economy Textbook");
    metadata.put("chapter_number", "3");
    metadata.put("chapter_title", "Surplus Value");
    metadata.put("section_path", "I > 1");
    metadata.put("page_start", 75);
    metadata.put("page_end", 76);
    metadata.put("block_type", "main_content");
    metadata.put("retrieval_role", role);
    return Document.builder()
        .id(id)
        .text("Content")
        .metadata(metadata)
        .score("seed".equals(role) ? 0.8 : null)
        .build();
  }
}
