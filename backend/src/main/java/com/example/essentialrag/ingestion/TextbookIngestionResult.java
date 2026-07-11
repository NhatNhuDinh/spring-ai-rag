package com.example.essentialrag.ingestion;

import com.example.essentialrag.ingestion.model.TextbookProfile;
import org.springframework.ai.document.Document;

import java.util.List;

public record TextbookIngestionResult(
    TextbookProfile profile,
    int pageCount,
    int blockCount,
    List<Document> childChunks,
    int skippedChunks) {
}
