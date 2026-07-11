package com.example.essentialrag.ingestion;

import org.springframework.ai.document.Document;

import java.util.List;

public record ChunkingResult(List<Document> documents, int skippedChunks) {
}
