package com.example.essentialrag.api.dto;

public record PackagedRetrievalContextResponse(
    String query,
    int topK,
    double similarityThreshold,
    int sourceCount,
    int contextLength,
    int contextCharacterLimit,
    String context) {
}
