package com.example.essentialrag.service.generation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class AnswerGenerationService {

  private static final String CORRECTION_SYSTEM_PROMPT = """
      Bạn là bộ sửa câu trả lời RAG.
      Chỉ sửa câu trả lời dựa trên RAG_CONTEXT và danh sách citation được cung cấp.
      Không gọi tool, không thêm kiến thức ngoài nguồn và không giải thích quy trình sửa.
      Chỉ trả về câu trả lời cuối cùng bằng tiếng Việt.
      """;

  private final ChatClient toolEnabledChatClient;
  private final ChatClient groundedStreamingChatClient;
  private final ChatClient citationCorrectionChatClient;

  public AnswerGenerationService(
      @Qualifier("toolEnabledChatClient") ChatClient toolEnabledChatClient,
      @Qualifier("groundedStreamingChatClient") ChatClient groundedStreamingChatClient,
      @Qualifier("citationCorrectionChatClient") ChatClient citationCorrectionChatClient) {

    this.toolEnabledChatClient = toolEnabledChatClient;
    this.groundedStreamingChatClient = groundedStreamingChatClient;
    this.citationCorrectionChatClient = citationCorrectionChatClient;
  }

  public String generateGeneral(String question, List<Message> history) {
    return toolEnabledChatClient.prompt()
        .messages(withUserQuestion(history, question))
        .call()
        .content();
  }

  public String generateGrounded(
      String question,
      String groundedSystemPrompt,
      List<Message> history) {

    return groundedStreamingChatClient.prompt()
        .messages(withSystemAndQuestion(groundedSystemPrompt, history, question))
        .call()
        .content();
  }

  public String generateCorrection(String correctionPrompt) {
    return citationCorrectionChatClient.prompt()
        .messages(
            new SystemMessage(CORRECTION_SYSTEM_PROMPT),
            new UserMessage(correctionPrompt))
        .call()
        .content();
  }

  public Flux<String> streamGeneral(String question, List<Message> history) {
    return clean(toolEnabledChatClient.prompt()
        .messages(withUserQuestion(history, question))
        .stream()
        .content());
  }

  public Flux<String> streamGrounded(
      String question,
      String groundedSystemPrompt,
      List<Message> history) {

    return clean(groundedStreamingChatClient.prompt()
        .messages(withSystemAndQuestion(groundedSystemPrompt, history, question))
        .stream()
        .content());
  }

  public Flux<String> streamCorrection(String correctionPrompt) {
    return clean(citationCorrectionChatClient.prompt()
        .messages(
            new SystemMessage(CORRECTION_SYSTEM_PROMPT),
            new UserMessage(correctionPrompt))
        .stream()
        .content());
  }

  private List<Message> withUserQuestion(List<Message> history, String question) {
    List<Message> messages = new ArrayList<>(safeHistory(history));
    messages.add(new UserMessage(question));
    return messages;
  }

  private List<Message> withSystemAndQuestion(
      String systemPrompt,
      List<Message> history,
      String question) {

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    messages.addAll(safeHistory(history));
    messages.add(new UserMessage(question));
    return messages;
  }

  private List<Message> safeHistory(List<Message> history) {
    return history == null ? List.of() : history;
  }

  private Flux<String> clean(Flux<String> chunks) {
    return chunks
        .filter(Objects::nonNull)
        .filter(chunk -> !chunk.isEmpty());
  }
}
