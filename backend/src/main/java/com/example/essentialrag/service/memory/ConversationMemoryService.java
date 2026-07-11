package com.example.essentialrag.service.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConversationMemoryService {

  private final ChatMemory chatMemory;

  public ConversationMemoryService(ChatMemory chatMemory) {
    this.chatMemory = chatMemory;
  }

  public List<Message> history(String conversationId) {
    List<Message> messages = chatMemory.get(conversationId);
    return messages == null ? List.of() : List.copyOf(messages);
  }

  public void saveTurn(String conversationId, String userMessage, String assistantMessage) {
    if (assistantMessage == null || assistantMessage.isBlank()) {
      return;
    }
    chatMemory.add(conversationId, List.of(
        new UserMessage(userMessage),
        new AssistantMessage(assistantMessage)));
  }
}
