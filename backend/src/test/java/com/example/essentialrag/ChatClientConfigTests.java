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
  void createsOneToolFreeChatClientForAllGenerationModes() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ChatModel.class, () -> mock(ChatModel.class));
      context.register(ChatClientConfig.class);
      context.refresh();

      Map<String, ChatClient> clients = context.getBeansOfType(ChatClient.class);
      assertThat(clients).containsOnlyKeys("ragChatClient");
    }
  }
}
