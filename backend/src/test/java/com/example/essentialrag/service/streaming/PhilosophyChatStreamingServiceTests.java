package com.example.essentialrag.service.streaming;

import com.example.essentialrag.api.dto.ChatStreamEvent;
import com.example.essentialrag.service.TextbookRetrievalService;
import com.example.essentialrag.service.generation.AnswerGenerationService;
import com.example.essentialrag.service.guardrail.AcademicQuestionDetector;
import com.example.essentialrag.service.guardrail.AnswerGuardrailService;
import com.example.essentialrag.service.guardrail.CitationValidationResult;
import com.example.essentialrag.service.guardrail.CitationValidator;
import com.example.essentialrag.service.guardrail.ContextQualityEvaluator;
import com.example.essentialrag.service.guardrail.ContextQualityResult;
import com.example.essentialrag.service.memory.ConversationMemoryService;
import com.example.essentialrag.service.retrieval.RetrievalContextPackager;
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

  @Mock
  private AnswerGenerationService answerGenerationService;
  @Mock
  private ConversationMemoryService conversationMemoryService;
  @Mock
  private TextbookRetrievalService retrievalService;
  @Mock
  private RetrievalContextPackager contextPackager;
  @Mock
  private AcademicQuestionDetector academicQuestionDetector;
  @Mock
  private ContextQualityEvaluator contextQualityEvaluator;
  @Mock
  private CitationValidator citationValidator;
  @Mock
  private AnswerGuardrailService answerGuardrailService;

  private PhilosophyChatStreamingService service;

  @BeforeEach
  void setUp() {
    service = new PhilosophyChatStreamingService(
        answerGenerationService,
        conversationMemoryService,
        retrievalService,
        contextPackager,
        academicQuestionDetector,
        contextQualityEvaluator,
        citationValidator,
        answerGuardrailService,
        Duration.ofHours(1),
        Duration.ofSeconds(2));
  }

  @Test
  void streamsGroundedAnswerAndSavesOnlyFinalTurn() {
    String question = "Giá trị thặng dư là gì?";
    String context = "RAG_CONTEXT\n[S1]\nNội dung";
    List<Document> documents = List.of(document());
    stubAcademicPreparation(question, context, documents, sufficientQuality());
    when(answerGuardrailService.groundedSystemPrompt(context)).thenReturn("grounded-system");
    when(answerGenerationService.streamGrounded(eq(question), eq("grounded-system"), anyList()))
        .thenReturn(Flux.just("Giá trị thặng dư ", "là... [S1]"));
    when(citationValidator.validate("Giá trị thặng dư là... [S1]", context, true))
        .thenReturn(validCitation());

    List<ChatStreamEvent> events = collect(service.stream(question, "conversation-1"));

    assertThat(events).extracting(ChatStreamEvent::type).containsExactly(
        "start",
        "status",
        "status",
        "sources",
        "status",
        "delta",
        "delta",
        "status",
        "done");
    assertThat(events.stream().filter(event -> event.type().equals("sources")).findFirst().orElseThrow()
        .sources()).singleElement().satisfies(source -> assertThat(source.label()).isEqualTo("[S1]"));
    assertThat(events.get(events.size() - 1).citationValid()).isTrue();
    verify(conversationMemoryService).saveTurn(
        "conversation-1",
        question,
        "Giá trị thặng dư là... [S1]");
  }

  @Test
  void resetsDraftAndStreamsCorrectionWhenCitationIsInvalid() {
    String question = "Vật chất là gì?";
    String context = "RAG_CONTEXT\n[S1]\nNội dung";
    List<Document> documents = List.of(document());
    stubAcademicPreparation(question, context, documents, sufficientQuality());
    when(answerGuardrailService.groundedSystemPrompt(context)).thenReturn("grounded-system");
    when(answerGenerationService.streamGrounded(eq(question), eq("grounded-system"), anyList()))
        .thenReturn(Flux.just("Bản nháp không nguồn"));

    CitationValidationResult invalid = new CitationValidationResult(
        false,
        true,
        Set.of(),
        Set.of("[S1]"),
        Set.of());
    when(citationValidator.validate("Bản nháp không nguồn", context, true)).thenReturn(invalid);
    when(answerGuardrailService.citationCorrectionPrompt(
        question,
        context,
        "Bản nháp không nguồn",
        invalid)).thenReturn("correction-prompt");
    when(answerGenerationService.streamCorrection("correction-prompt"))
        .thenReturn(Flux.just("Bản sửa [S1]"));
    when(citationValidator.validate("Bản sửa [S1]", context, true)).thenReturn(validCitation());

    List<ChatStreamEvent> events = collect(service.stream(question, "conversation-2"));

    assertThat(events).extracting(ChatStreamEvent::type).containsSubsequence(
        "delta",
        "status",
        "reset",
        "status",
        "delta",
        "status",
        "done");
    assertThat(events.stream()
        .filter(event -> event.type().equals("delta") && Boolean.TRUE.equals(event.corrected()))
        .map(ChatStreamEvent::text))
        .containsExactly("Bản sửa [S1]");
    assertThat(events.get(events.size() - 1).corrected()).isTrue();
    verify(conversationMemoryService).saveTurn("conversation-2", question, "Bản sửa [S1]");
    verify(conversationMemoryService, never()).saveTurn("conversation-2", question, "Bản nháp không nguồn");
  }

  @Test
  void refusesBeforeCallingModelWhenContextIsWeak() {
    String question = "Một câu hỏi ngoài dữ liệu";
    String context = "RAG_CONTEXT";
    List<Document> documents = List.of();
    ContextQualityResult weak = new ContextQualityResult(
        false,
        "no_retrieved_sources",
        0,
        0.0,
        0.0,
        false);
    stubAcademicPreparation(question, context, documents, weak);
    when(answerGuardrailService.refusalMessage(weak)).thenReturn("Không đủ ngữ cảnh.");

    List<ChatStreamEvent> events = collect(service.stream(question, "conversation-3"));

    assertThat(events).extracting(ChatStreamEvent::type).containsExactly(
        "start",
        "status",
        "status",
        "refusal",
        "done");
    verify(answerGenerationService, never()).streamGrounded(any(), any(), anyList());
    verify(conversationMemoryService).saveTurn(
        "conversation-3",
        question,
        "Không đủ ngữ cảnh.");
  }

  @Test
  void convertsModelTimeoutToErrorEvent() {
    service = new PhilosophyChatStreamingService(
        answerGenerationService,
        conversationMemoryService,
        retrievalService,
        contextPackager,
        academicQuestionDetector,
        contextQualityEvaluator,
        citationValidator,
        answerGuardrailService,
        Duration.ofHours(1),
        Duration.ofMillis(30));
    when(academicQuestionDetector.isLikelyAcademicQuestion("xin chào")).thenReturn(false);
    when(answerGenerationService.streamGeneral(eq("xin chào"), anyList())).thenReturn(Flux.never());

    List<ChatStreamEvent> events = collect(service.stream("xin chào", "conversation-4"));

    assertThat(events.get(events.size() - 1).type()).isEqualTo("error");
    assertThat(events.get(events.size() - 1).errorCode()).isEqualTo("MODEL_TIMEOUT");
    verify(conversationMemoryService, never()).saveTurn(any(), any(), any());
  }

  private void stubAcademicPreparation(
      String question,
      String context,
      List<Document> documents,
      ContextQualityResult quality) {

    when(academicQuestionDetector.isLikelyAcademicQuestion(question)).thenReturn(true);
    when(retrievalService.retrieveWithParentContext(question, null, null)).thenReturn(documents);
    when(contextPackager.packageContext(question, documents)).thenReturn(context);
    when(contextQualityEvaluator.evaluate(documents)).thenReturn(quality);
  }

  private List<ChatStreamEvent> collect(Flux<ChatStreamEvent> events) {
    return events.collectList().block(Duration.ofSeconds(3));
  }

  private ContextQualityResult sufficientQuality() {
    return new ContextQualityResult(true, "sufficient", 1, 0.02, 0.8, true);
  }

  private CitationValidationResult validCitation() {
    return new CitationValidationResult(
        true,
        false,
        Set.of("[S1]"),
        Set.of("[S1]"),
        Set.of());
  }

  private Document document() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source_file", "triet_2.pdf");
    metadata.put("book_title", "Giáo trình Kinh tế chính trị Mác - Lênin");
    metadata.put("chapter_number", "3");
    metadata.put("chapter_title", "Giá trị thặng dư");
    metadata.put("section_path", "I > 1");
    metadata.put("page_start", 75);
    metadata.put("page_end", 76);
    metadata.put("block_type", "main_content");
    metadata.put("retrieval_role", "seed");
    return Document.builder()
        .id("00000000-0000-0000-0000-000000000001")
        .text("Nội dung")
        .metadata(metadata)
        .score(0.8)
        .build();
  }
}
