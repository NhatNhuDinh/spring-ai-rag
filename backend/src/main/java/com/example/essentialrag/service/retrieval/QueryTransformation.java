package com.example.essentialrag.service.retrieval;

import java.util.List;

public record QueryTransformation(
    String originalQuery,
    List<String> retrievalQueries,
    RetrievalIntent intent) {
}
