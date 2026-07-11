package com.example.essentialrag.ingestion.model;

public record TextbookProfile(
    String sourceLocation,
    String sourceFile,
    String bookTitle,
    String subject) {
}
