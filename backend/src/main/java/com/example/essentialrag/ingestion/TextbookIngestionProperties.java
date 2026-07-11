package com.example.essentialrag.ingestion;

import java.util.List;

public record TextbookIngestionProperties(
    boolean enabled,
    List<String> documentLocations,
    int maxChunkTokens,
    int minChunkTokens,
    int batchSize) {
}
