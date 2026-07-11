package com.example.essentialrag.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSdkChatRequestTests {

  @Test
  void extractsLatestUserTextFromAiSdkContentParts() {
    AiSdkChatRequest request = new AiSdkChatRequest(
        List.of(
            new AiSdkChatRequest.AiSdkMessage("user", "older question"),
            new AiSdkChatRequest.AiSdkMessage("assistant", "older answer"),
            new AiSdkChatRequest.AiSdkMessage(
                "user",
                List.of(
                    Map.of("type", "text", "text", "latest"),
                    Map.of("type", "text", "text", "question"))),
            new AiSdkChatRequest.AiSdkMessage("assistant", List.of())),
        "thread-1",
        null);

    assertThat(request.latestUserText()).isEqualTo("latest\nquestion");
    assertThat(request.effectiveConversationId()).isEqualTo("thread-1");
  }

  @Test
  void explicitConversationIdOverridesThreadId() {
    AiSdkChatRequest request = new AiSdkChatRequest(
        List.of(new AiSdkChatRequest.AiSdkMessage("user", "hello")),
        "thread-1",
        "conversation-1");

    assertThat(request.latestUserText()).isEqualTo("hello");
    assertThat(request.effectiveConversationId()).isEqualTo("conversation-1");
  }

  @Test
  void rejectsRequestWithoutUserText() {
    AiSdkChatRequest request = new AiSdkChatRequest(
        List.of(new AiSdkChatRequest.AiSdkMessage("assistant", "hello")),
        null,
        null);

    assertThatThrownBy(request::latestUserText)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user message");
  }
}
