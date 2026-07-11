package com.example.essentialrag.service.retrieval;

import org.springframework.ai.document.Document;

import java.util.List;

public record TextbookRetrievalResult(
    List<Document> seedDocuments,
    List<Document> contextDocuments) {

  public TextbookRetrievalResult {
    seedDocuments = List.copyOf(seedDocuments);
    contextDocuments = List.copyOf(contextDocuments);
  }
}
