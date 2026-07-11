package com.example.essentialrag.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSdkChatRequest(
    List<AiSdkMessage> messages,
    String threadId,
    String conversationId) {

  public AiSdkChatRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
  }

  public String latestUserText() {
    for (int index = messages.size() - 1; index >= 0; index--) {
      AiSdkMessage message = messages.get(index);
      if (message == null || !"user".equalsIgnoreCase(message.role())) {
        continue;
      }

      String text = contentText(message.content());
      if (!text.isBlank()) {
        return text;
      }
    }
    throw new IllegalArgumentException("messages must contain a non-blank user message");
  }

  public String effectiveConversationId() {
    if (conversationId != null && !conversationId.isBlank()) {
      return conversationId;
    }
    return threadId == null || threadId.isBlank() ? null : threadId;
  }

  private static String contentText(Object content) {
    StringBuilder text = new StringBuilder();
    appendText(content, text);
    return text.toString().trim();
  }

  private static void appendText(Object value, StringBuilder target) {
    if (value instanceof String text) {
      appendPart(target, text);
      return;
    }
    if (value instanceof List<?> parts) {
      parts.forEach(part -> appendText(part, target));
      return;
    }
    if (!(value instanceof Map<?, ?> part)) {
      return;
    }

    Object text = part.get("text");
    if (text instanceof String string) {
      appendPart(target, string);
      return;
    }
    appendText(part.get("content"), target);
  }

  private static void appendPart(StringBuilder target, String part) {
    if (part == null || part.isBlank()) {
      return;
    }
    if (!target.isEmpty()) {
      target.append('\n');
    }
    target.append(part.trim());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AiSdkMessage(String role, Object content) {
  }
}
