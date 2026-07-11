package com.example.essentialrag.api.dto;

import java.util.List;

public record RetrievalSearchResponse(
    String query,
    int topK,
    double similarityThreshold,
    int resultCount,
    List<RetrievedChunkResponse> results) {
}
