package com.example.essentialrag.service.streaming;

import com.example.essentialrag.api.dto.ChatSource;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

@Service
public class PhilosophyChatStreamingService {

  private static final Logger logger = LoggerFactory.getLogger(PhilosophyChatStreamingService.class);

  private final AnswerGenerationService answerGenerationService;
  private final ConversationMemoryService conversationMemoryService;
  private final TextbookRetrievalService retrievalService;
  private final RetrievalContextPackager contextPackager;
  private final AcademicQuestionDetector academicQuestionDetector;
  private final ContextQualityEvaluator contextQualityEvaluator;
  private final CitationValidator citationValidator;
  private final AnswerGuardrailService answerGuardrailService;
  private final Duration heartbeatInterval;
  private final Duration modelTimeout;

  public PhilosophyChatStreamingService(
      AnswerGenerationService answerGenerationService,
      ConversationMemoryService conversationMemoryService,
      TextbookRetrievalService retrievalService,
      RetrievalContextPackager contextPackager,
      AcademicQuestionDetector academicQuestionDetector,
      ContextQualityEvaluator contextQualityEvaluator,
      CitationValidator citationValidator,
      AnswerGuardrailService answerGuardrailService,
      @Value("${rag.streaming.heartbeat-interval:15s}") Duration heartbeatInterval,
      @Value("${rag.streaming.model-timeout:120s}") Duration modelTimeout) {

    this.answerGenerationService = answerGenerationService;
    this.conversationMemoryService = conversationMemoryService;
    this.retrievalService = retrievalService;
    this.contextPackager = contextPackager;
    this.academicQuestionDetector = academicQuestionDetector;
    this.contextQualityEvaluator = contextQualityEvaluator;
    this.citationValidator = citationValidator;
    this.answerGuardrailService = answerGuardrailService;
    this.heartbeatInterval = positiveDuration(heartbeatInterval, Duration.ofSeconds(15));
    this.modelTimeout = positiveDuration(modelTimeout, Duration.ofSeconds(120));
  }

  public Flux<ChatStreamEvent> stream(String message, String conversationId) {
    validateMessage(message);
    String effectiveConversationId = effectiveConversationId(conversationId);
    String messageId = UUID.randomUUID().toString();

    Flux<ChatStreamEvent> businessEvents = Flux.defer(() -> Flux.concat(
            Flux.just(
                ChatStreamEvent.start(effectiveConversationId, messageId),
                ChatStreamEvent.status(effectiveConversationId, messageId, "classifying")),
            academicQuestionDetector.isLikelyAcademicQuestion(message)
                ? streamAcademic(message, effectiveConversationId, messageId)
                : streamGeneral(message, effectiveConversationId, messageId)))
        .onErrorResume(error -> recover(error, effectiveConversationId, messageId))
        .doOnCancel(() -> logger.info(
            "Chat stream cancelled: conversationId={}, messageId={}",
            effectiveConversationId,
            messageId));

    return withHeartbeat(businessEvents, effectiveConversationId, messageId);
  }

  private Flux<ChatStreamEvent> streamGeneral(
      String message,
      String conversationId,
      String messageId) {

    return Flux.defer(() -> {
      List<Message> history = conversationMemoryService.history(conversationId);
      StringBuilder answer = new StringBuilder();
      Flux<ChatStreamEvent> deltas = answerGenerationService.streamGeneral(message, history)
          .timeout(modelTimeout)
          .doOnNext(answer::append)
          .map(chunk -> ChatStreamEvent.delta(conversationId, messageId, chunk, false));

      Flux<ChatStreamEvent> completion = Flux.defer(() -> {
        String finalAnswer = requireContent(answer);
        conversationMemoryService.saveTurn(conversationId, message, finalAnswer);
        return Flux.just(ChatStreamEvent.done(conversationId, messageId, null, false));
      });

      return Flux.concat(
          Flux.just(ChatStreamEvent.status(conversationId, messageId, "generating")),
          deltas,
          completion);
    });
  }

  private Flux<ChatStreamEvent> streamAcademic(
      String message,
      String conversationId,
      String messageId) {

    Mono<PreparedRagContext> preparation = Mono.fromCallable(() -> prepareRagContext(message))
        .subscribeOn(Schedulers.boundedElastic());

    return Flux.concat(
        Flux.just(ChatStreamEvent.status(conversationId, messageId, "retrieving")),
        preparation.flatMapMany(prepared -> {
          if (!prepared.quality().sufficient()) {
            String refusal = answerGuardrailService.refusalMessage(prepared.quality());
            conversationMemoryService.saveTurn(conversationId, message, refusal);
            return Flux.just(
                ChatStreamEvent.refusal(conversationId, messageId, refusal),
                ChatStreamEvent.done(conversationId, messageId, null, false));
          }
          return streamGroundedAnswer(message, conversationId, messageId, prepared);
        }));
  }

  private PreparedRagContext prepareRagContext(String message) {
    List<Document> documents = retrievalService.retrieveWithParentContext(message, null, null);
    String ragContext = contextPackager.packageContext(message, documents);
    ContextQualityResult quality = contextQualityEvaluator.evaluate(documents);
    return new PreparedRagContext(
        documents,
        ragContext,
        quality,
        toSources(documents, ragContext));
  }

  private Flux<ChatStreamEvent> streamGroundedAnswer(
      String message,
      String conversationId,
      String messageId,
      PreparedRagContext prepared) {

    return Flux.defer(() -> {
      List<Message> history = conversationMemoryService.history(conversationId);
      StringBuilder draft = new StringBuilder();
      String groundedSystemPrompt = answerGuardrailService.groundedSystemPrompt(prepared.ragContext());
      Flux<ChatStreamEvent> deltas = answerGenerationService
          .streamGrounded(message, groundedSystemPrompt, history)
          .timeout(modelTimeout)
          .doOnNext(draft::append)
          .map(chunk -> ChatStreamEvent.delta(conversationId, messageId, chunk, false));

      Flux<ChatStreamEvent> validation = Flux.defer(() -> validateDraft(
          message,
          conversationId,
          messageId,
          prepared.ragContext(),
          requireContent(draft)));

      return Flux.concat(
          Flux.just(
              ChatStreamEvent.sources(conversationId, messageId, prepared.sources()),
              ChatStreamEvent.status(conversationId, messageId, "generating")),
          deltas,
          Flux.just(ChatStreamEvent.status(conversationId, messageId, "validating")),
          validation);
    });
  }

  private Flux<ChatStreamEvent> validateDraft(
      String message,
      String conversationId,
      String messageId,
      String ragContext,
      String draft) {

    CitationValidationResult validation = citationValidator.validate(draft, ragContext, true);
    if (validation.valid()) {
      conversationMemoryService.saveTurn(conversationId, message, draft);
      return Flux.just(ChatStreamEvent.done(conversationId, messageId, true, false));
    }

    String correctionPrompt = answerGuardrailService.citationCorrectionPrompt(
        message,
        ragContext,
        draft,
        validation);
    return streamCorrection(message, conversationId, messageId, ragContext, correctionPrompt);
  }

  private Flux<ChatStreamEvent> streamCorrection(
      String message,
      String conversationId,
      String messageId,
      String ragContext,
      String correctionPrompt) {

    return Flux.defer(() -> {
      StringBuilder corrected = new StringBuilder();
      Flux<ChatStreamEvent> deltas = answerGenerationService.streamCorrection(correctionPrompt)
          .timeout(modelTimeout)
          .doOnNext(corrected::append)
          .map(chunk -> ChatStreamEvent.delta(conversationId, messageId, chunk, true));

      Flux<ChatStreamEvent> finalValidation = Flux.defer(() -> {
        String correctedAnswer = requireContent(corrected);
        CitationValidationResult result = citationValidator.validate(correctedAnswer, ragContext, true);
        if (result.valid()) {
          conversationMemoryService.saveTurn(conversationId, message, correctedAnswer);
          return Flux.just(ChatStreamEvent.done(conversationId, messageId, true, true));
        }

        String refusal = answerGuardrailService.citationFailureMessage();
        conversationMemoryService.saveTurn(conversationId, message, refusal);
        return Flux.just(
            ChatStreamEvent.reset(conversationId, messageId, "citation_correction_failed"),
            ChatStreamEvent.refusal(conversationId, messageId, refusal),
            ChatStreamEvent.done(conversationId, messageId, false, true));
      });

      return Flux.concat(
          Flux.just(
              ChatStreamEvent.reset(conversationId, messageId, "citation_validation_failed"),
              ChatStreamEvent.status(conversationId, messageId, "correcting")),
          deltas,
          Flux.just(ChatStreamEvent.status(conversationId, messageId, "validating")),
          finalValidation);
    });
  }

  private List<ChatSource> toSources(List<Document> documents, String ragContext) {
    List<ChatSource> sources = new ArrayList<>();
    for (int i = 0; i < documents.size(); i++) {
      String label = "[S" + (i + 1) + "]";
      if (!ragContext.contains(label)) {
        continue;
      }
      Document document = documents.get(i);
      Map<String, Object> metadata = document.getMetadata();
      sources.add(new ChatSource(
          label,
          string(metadata, "source_file"),
          string(metadata, "book_title"),
          string(metadata, "chapter_number"),
          string(metadata, "chapter_title"),
          string(metadata, "section_path"),
          integer(metadata, "page_start"),
          integer(metadata, "page_end"),
          string(metadata, "block_type"),
          string(metadata, "retrieval_role"),
          document.getScore()));
    }
    return sources;
  }

  private Flux<ChatStreamEvent> withHeartbeat(
      Flux<ChatStreamEvent> source,
      String conversationId,
      String messageId) {

    return Flux.defer(() -> {
      Sinks.Empty<Void> stopHeartbeat = Sinks.empty();
      Flux<ChatStreamEvent> monitored = source.doFinally(signal -> stopHeartbeat.tryEmitEmpty());
      Flux<ChatStreamEvent> heartbeats = Flux.interval(heartbeatInterval)
          .map(ignored -> ChatStreamEvent.heartbeat(conversationId, messageId))
          .takeUntilOther(stopHeartbeat.asMono());
      return Flux.merge(monitored, heartbeats);
    });
  }

  private Flux<ChatStreamEvent> recover(
      Throwable error,
      String conversationId,
      String messageId) {

    String code = error instanceof TimeoutException ? "MODEL_TIMEOUT" : "STREAM_FAILED";
    logger.error(
        "Chat stream failed: conversationId={}, messageId={}, code={}",
        conversationId,
        messageId,
        code,
        error);
    String message = error instanceof TimeoutException
        ? "Model phản hồi quá thời gian cho phép."
        : "Không thể hoàn thành câu trả lời streaming.";
    return Flux.just(ChatStreamEvent.error(conversationId, messageId, code, message));
  }

  private String requireContent(StringBuilder content) {
    return requireContent(content.toString());
  }

  private String requireContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("The chat model returned an empty answer.");
    }
    return content;
  }

  private void validateMessage(String message) {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
  }

  private String effectiveConversationId(String conversationId) {
    return conversationId == null || conversationId.isBlank()
        ? UUID.randomUUID().toString()
        : conversationId;
  }

  private Duration positiveDuration(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  private String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value == null ? null : value.toString();
  }

  private Integer integer(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    try {
      return Integer.parseInt(value.toString());
    }
    catch (NumberFormatException ignored) {
      return null;
    }
  }
}
