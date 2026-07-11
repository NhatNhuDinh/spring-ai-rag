package com.example.essentialrag.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RagIngestionConfig {

  private static final Logger logger = LoggerFactory.getLogger(RagIngestionConfig.class);

  @Bean
  TextbookIngestionProperties textbookIngestionProperties(
      @Value("${rag.ingestion.enabled:true}") boolean enabled,
      @Value("${rag.documents:classpath:documentation/triet_1.pdf,classpath:documentation/triet_2.pdf}")
      String documentLocations,
      @Value("${rag.ingestion.chunk.max-tokens:850}") int maxChunkTokens,
      @Value("${rag.ingestion.chunk.min-tokens:45}") int minChunkTokens,
      @Value("${rag.ingestion.batch-size:64}") int batchSize) {

    return new TextbookIngestionProperties(
        enabled,
        splitLocations(documentLocations),
        maxChunkTokens,
        minChunkTokens,
        batchSize);
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  ApplicationRunner ingestTextbooks(
      VectorStore vectorStore,
      ResourceLoader resourceLoader,
      TextbookIngestionService ingestionService,
      TextbookIngestionProperties properties) {

    return args -> {
      if (!properties.enabled()) {
        logger.info("Textbook ingestion is disabled.");
        return;
      }

      List<Document> documents = new ArrayList<>();
      for (String location : properties.documentLocations()) {
        Resource resource = resourceLoader.getResource(location);
        TextbookIngestionResult result = ingestionService.ingest(location, resource, properties);
        documents.addAll(result.childChunks());

        logger.info(
            "Prepared textbook {}: pages={}, blocks={}, child_chunks={}, skipped_chunks={}",
            result.profile().sourceFile(),
            result.pageCount(),
            result.blockCount(),
            result.childChunks().size(),
            result.skippedChunks());
      }

      if (documents.isEmpty()) {
        logger.warn("No textbook chunks passed the ingestion quality gate.");
        return;
      }

      for (int from = 0; from < documents.size(); from += properties.batchSize()) {
        int to = Math.min(from + properties.batchSize(), documents.size());
        vectorStore.add(documents.subList(from, to));
        logger.info("Embedded and stored chunks {}-{} of {}.", from + 1, to, documents.size());
      }

      logger.info("Textbook ingestion complete. Stored {} child chunks.", documents.size());
    };
  }

  private List<String> splitLocations(String documentLocations) {
    List<String> result = new ArrayList<>();
    for (String location : documentLocations.split(",")) {
      String trimmed = location.trim();
      if (!trimmed.isBlank()) {
        result.add(trimmed);
      }
    }
    return result;
  }
}
