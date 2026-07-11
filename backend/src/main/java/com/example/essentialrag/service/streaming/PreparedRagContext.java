package com.example.essentialrag.service.streaming;

import com.example.essentialrag.api.dto.ChatSource;
import com.example.essentialrag.service.guardrail.ContextQualityResult;
import org.springframework.ai.document.Document;

import java.util.List;

record PreparedRagContext(
    List<Document> documents,
    String ragContext,
    ContextQualityResult quality,
    List<ChatSource> sources) {

  PreparedRagContext {
    documents = List.copyOf(documents);
    sources = List.copyOf(sources);
  }
}
