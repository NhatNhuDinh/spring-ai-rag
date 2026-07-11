package com.example.essentialrag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatClientConfigTests {

  @Test
  void createsSeparatedChatClientsWithToolClientAsPrimary() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ChatModel.class, () -> mock(ChatModel.class));
      context.registerBean(RagTools.class, () -> mock(RagTools.class));
      context.register(ChatClientConfig.class);
      context.refresh();

      Map<String, ChatClient> clients = context.getBeansOfType(ChatClient.class);
      assertThat(clients).containsKeys(
          "toolEnabledChatClient",
          "groundedStreamingChatClient",
          "citationCorrectionChatClient");
      assertThat(context.getBean(ChatClient.class))
          .isSameAs(clients.get("toolEnabledChatClient"));
    }
  }
}
