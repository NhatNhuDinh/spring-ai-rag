package com.example.essentialrag.api.dto;

public record ChatSource(
    String label,
    String sourceFile,
    String bookTitle,
    String chapterNumber,
    String chapterTitle,
    String sectionPath,
    Integer pageStart,
    Integer pageEnd,
    String blockType,
    String retrievalRole,
    Double score) {
}
