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

  private static final String GENERAL_SYSTEM_PROMPT = """
      Bạn là trợ lý học tập về Triết học Mác - Lênin và Kinh tế chính trị Mác - Lênin.
      Yêu cầu hiện tại chỉ là hội thoại xã giao, không cần tra cứu giáo trình.
      Trả lời tự nhiên, ngắn gọn bằng tiếng Việt và không nêu khẳng định học thuật.
      """;

  private static final String CORRECTION_SYSTEM_PROMPT = """
      Bạn là bộ sửa câu trả lời RAG.
      Chỉ sửa câu trả lời dựa trên RAG_CONTEXT và danh sách citation được cung cấp.
      Không gọi tool, không thêm kiến thức ngoài nguồn và không giải thích quy trình sửa.
      Chỉ trả về câu trả lời cuối cùng bằng tiếng Việt.
      """;

  private final ChatClient ragChatClient;

  public AnswerGenerationService(@Qualifier("ragChatClient") ChatClient ragChatClient) {
    this.ragChatClient = ragChatClient;
  }

  public Flux<String> streamGeneral(String question, List<Message> history) {
    return stream(GENERAL_SYSTEM_PROMPT, history, question);
  }

  public Flux<String> streamGrounded(
      String question,
      String groundedSystemPrompt,
      List<Message> history) {

    return stream(groundedSystemPrompt, history, question);
  }

  public Flux<String> streamCorrection(String correctionPrompt) {
    return stream(CORRECTION_SYSTEM_PROMPT, List.of(), correctionPrompt);
  }

  private Flux<String> stream(String systemPrompt, List<Message> history, String question) {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(systemPrompt));
    messages.addAll(history == null ? List.of() : history);
    messages.add(new UserMessage(question));

    return ragChatClient.prompt()
        .messages(messages)
        .stream()
        .content()
        .filter(Objects::nonNull)
        .filter(chunk -> !chunk.isEmpty());
  }
}
