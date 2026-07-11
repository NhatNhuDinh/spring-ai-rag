package com.example.essentialrag.api.dto;

import java.util.List;

public record QueryTransformResponse(
    String originalQuery,
    List<String> retrievalQueries,
    String sourceFile,
    String chapterNumber,
    String blockType) {
}
