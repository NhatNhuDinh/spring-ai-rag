package com.example.essentialrag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatClientConfig {

  @Bean
  ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
        .maxMessages(500)
        .build();
  }

  @Bean
  @Primary
  ChatClient toolEnabledChatClient(ChatModel chatModel, RagTools ragTools) {
    return ChatClient.builder(chatModel)
        .defaultTemplateRenderer(new NoOpTemplateRenderer())
        .defaultSystem("""
            Bạn là trợ lý về Triết học Mác - Lênin và Kinh tế chính trị Mác - Lênin.
            Khi câu hỏi cần tra cứu giáo trình, hãy gọi tool triet-hoc-helper trước khi trả lời.
            Chỉ dùng nguồn do tool trả về cho các khẳng định học thuật và trích dẫn bằng [S1], [S2] tương ứng.
            Nếu nguồn không đủ, hãy nói rõ rằng ngữ cảnh giáo trình hiện có chưa đủ để kết luận.
            Với lời chào hoặc hội thoại thông thường, trả lời tự nhiên và không cần gọi tool.
            """)
        .defaultTools(ragTools)
        .build();
  }

  @Bean
  ChatClient groundedStreamingChatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultTemplateRenderer(new NoOpTemplateRenderer())
        .build();
  }

  @Bean
  ChatClient citationCorrectionChatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultTemplateRenderer(new NoOpTemplateRenderer())
        .build();
  }
}
