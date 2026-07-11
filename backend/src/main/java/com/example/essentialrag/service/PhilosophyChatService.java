package com.example.essentialrag.service;

import com.example.essentialrag.service.generation.AnswerGenerationService;
import com.example.essentialrag.service.guardrail.AcademicQuestionDetector;
import com.example.essentialrag.service.guardrail.AnswerGuardrailService;
import com.example.essentialrag.service.guardrail.CitationValidationResult;
import com.example.essentialrag.service.guardrail.CitationValidator;
import com.example.essentialrag.service.guardrail.ContextQualityEvaluator;
import com.example.essentialrag.service.guardrail.ContextQualityResult;
import com.example.essentialrag.service.memory.ConversationMemoryService;
import com.example.essentialrag.service.retrieval.RetrievalContextPackager;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PhilosophyChatService {

  private final AnswerGenerationService answerGenerationService;
  private final ConversationMemoryService conversationMemoryService;
  private final TextbookRetrievalService retrievalService;
  private final RetrievalContextPackager contextPackager;
  private final AcademicQuestionDetector academicQuestionDetector;
  private final ContextQualityEvaluator contextQualityEvaluator;
  private final CitationValidator citationValidator;
  private final AnswerGuardrailService answerGuardrailService;

  public PhilosophyChatService(
      AnswerGenerationService answerGenerationService,
      ConversationMemoryService conversationMemoryService,
      TextbookRetrievalService retrievalService,
      RetrievalContextPackager contextPackager,
      AcademicQuestionDetector academicQuestionDetector,
      ContextQualityEvaluator contextQualityEvaluator,
      CitationValidator citationValidator,
      AnswerGuardrailService answerGuardrailService) {

    this.answerGenerationService = answerGenerationService;
    this.conversationMemoryService = conversationMemoryService;
    this.retrievalService = retrievalService;
    this.contextPackager = contextPackager;
    this.academicQuestionDetector = academicQuestionDetector;
    this.contextQualityEvaluator = contextQualityEvaluator;
    this.citationValidator = citationValidator;
    this.answerGuardrailService = answerGuardrailService;
  }

  public ChatAnswer chat(String message, String conversationId) {
    validateMessage(message);
    String effectiveConversationId = effectiveConversationId(conversationId);

    if (!academicQuestionDetector.isLikelyAcademicQuestion(message)) {
      List<Message> history = conversationMemoryService.history(effectiveConversationId);
      String answer = requireContent(answerGenerationService.generateGeneral(message, history));
      conversationMemoryService.saveTurn(effectiveConversationId, message, answer);
      return new ChatAnswer(effectiveConversationId, answer);
    }

    List<Document> documents = retrievalService.retrieveWithParentContext(message, null, null);
    String ragContext = contextPackager.packageContext(message, documents);
    return chatWithPreparedContext(message, effectiveConversationId, documents, ragContext);
  }

  public ChatAnswer chatWithPreparedContext(
      String message,
      String conversationId,
      List<Document> documents,
      String ragContext) {

    validateMessage(message);
    String effectiveConversationId = effectiveConversationId(conversationId);
    ContextQualityResult quality = contextQualityEvaluator.evaluate(documents);
    if (!quality.sufficient()) {
      String refusal = answerGuardrailService.refusalMessage(quality);
      conversationMemoryService.saveTurn(effectiveConversationId, message, refusal);
      return new ChatAnswer(effectiveConversationId, refusal);
    }

    List<Message> history = conversationMemoryService.history(effectiveConversationId);
    String groundedSystemPrompt = answerGuardrailService.groundedSystemPrompt(ragContext);
    String answer = requireContent(answerGenerationService.generateGrounded(
        message,
        groundedSystemPrompt,
        history));

    CitationValidationResult validation = citationValidator.validate(answer, ragContext, true);
    if (validation.valid()) {
      conversationMemoryService.saveTurn(effectiveConversationId, message, answer);
      return new ChatAnswer(effectiveConversationId, answer);
    }

    String correctionPrompt = answerGuardrailService.citationCorrectionPrompt(
        message,
        ragContext,
        answer,
        validation);
    String correctedAnswer = requireContent(answerGenerationService.generateCorrection(correctionPrompt));
    CitationValidationResult correctedValidation = citationValidator.validate(correctedAnswer, ragContext, true);
    String finalAnswer = correctedValidation.valid()
        ? correctedAnswer
        : answerGuardrailService.citationFailureMessage();

    conversationMemoryService.saveTurn(effectiveConversationId, message, finalAnswer);
    return new ChatAnswer(effectiveConversationId, finalAnswer);
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

  private String requireContent(String answer) {
    if (answer == null || answer.isBlank()) {
      throw new IllegalStateException("The chat model returned an empty answer.");
    }
    return answer;
  }

  public record ChatAnswer(String conversationId, String answer) {
  }
}
