package com.example.essentialrag.api.dto;

public record RetrievalSearchRequest(
    String query,
    Integer topK,
    Double similarityThreshold) {
}
