package com.example.essentialrag.api.dto;

import java.util.Map;

public record RetrievedChunkResponse(
    int rank,
    String id,
    Double score,
    String sourceFile,
    String bookTitle,
    String chapterNumber,
    String chapterTitle,
    String sectionPath,
    String blockType,
    String parentId,
    Integer chunkIndex,
    String retrievalRole,
    Integer seedRank,
    Boolean queryTransformed,
    String retrievalQuery,
    Integer retrievalQueryIndex,
    String retrievalMode,
    String matchedBy,
    Double hybridScore,
    Integer vectorRank,
    Integer keywordRank,
    Double vectorScore,
    Double keywordScore,
    Integer pageStart,
    Integer pageEnd,
    String preview,
    Map<String, Object> metadata) {
}
