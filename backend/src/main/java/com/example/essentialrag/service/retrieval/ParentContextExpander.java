package com.example.essentialrag.service.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ParentContextExpander {

  private static final Logger logger = LoggerFactory.getLogger(ParentContextExpander.class);
  private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
  };

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ParentContextProperties properties;
  private final String tableName;

  public ParentContextExpander(
      JdbcTemplate jdbcTemplate,
      ParentContextProperties properties,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {

    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = new ObjectMapper();
    this.properties = properties;
    this.tableName = validateTableName(tableName);
  }

  public List<Document> expand(List<Document> seedDocuments) {
    if (!properties.enabled() || seedDocuments.isEmpty()) {
      return seedDocuments;
    }

    try {
      return expandOrThrow(seedDocuments);
    }
    catch (DataAccessException | IllegalStateException ex) {
      logger.warn("Parent context expansion failed. Returning seed retrieval results only.", ex);
      return seedDocuments;
    }
  }

  private List<Document> expandOrThrow(List<Document> seedDocuments) {
    Map<String, Document> results = new LinkedHashMap<>();

    for (int i = 0; i < seedDocuments.size() && results.size() < properties.maxResults(); i++) {
      Document seed = seedDocuments.get(i);
      int seedRank = i + 1;
      addIfAbsent(results, tag(seed, "seed", seedRank, seed.getId(), seed.getScore(), seed.getMetadata()));

      String parentId = string(seed.getMetadata(), "parent_id");
      String sourceFile = string(seed.getMetadata(), "source_file");
      Integer chunkIndex = integer(seed.getMetadata(), "chunk_index");
      if (parentId == null || sourceFile == null || chunkIndex == null) {
        continue;
      }

      int from = Math.max(0, chunkIndex - properties.windowBefore());
      int to = chunkIndex + properties.windowAfter();
      for (Document context : findSiblingChunks(sourceFile, parentId, from, to)) {
        if (results.size() >= properties.maxResults()) {
          break;
        }
        if (context.getId().equals(seed.getId())) {
          continue;
        }
        addIfAbsent(results, tag(context, "parent_context", seedRank, seed.getId(), seed.getScore(), seed.getMetadata()));
      }
    }

    return new ArrayList<>(results.values());
  }

  private List<Document> findSiblingChunks(String sourceFile, String parentId, int fromChunkIndex, int toChunkIndex) {
    String sql = """
        select id::text as id, content, metadata::text as metadata
        from %s
        where metadata->>'chunk_level' = 'child'
          and metadata->>'document_type' = 'textbook'
          and metadata->>'source_file' = ?
          and metadata->>'parent_id' = ?
          and (metadata->>'chunk_index')::int between ? and ?
        order by (metadata->>'chunk_index')::int
        """.formatted(tableName);

    return jdbcTemplate.query(
        sql,
        (rs, rowNum) -> toDocument(rs),
        sourceFile,
        parentId,
        fromChunkIndex,
        toChunkIndex);
  }

  private Document toDocument(ResultSet rs) throws SQLException {
    Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
    return Document.builder()
        .id(rs.getString("id"))
        .text(rs.getString("content"))
        .metadata(metadata)
        .build();
  }

  private Map<String, Object> parseMetadata(String metadataJson) {
    try {
      return objectMapper.readValue(metadataJson, METADATA_TYPE);
    }
    catch (Exception ex) {
      throw new IllegalStateException("Failed to parse pgvector document metadata.", ex);
    }
  }

  private Document tag(
      Document document,
      String retrievalRole,
      int seedRank,
      String seedDocumentId,
      Double seedScore,
      Map<String, Object> seedMetadata) {

    Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
    metadata.put("retrieval_role", retrievalRole);
    metadata.put("seed_rank", seedRank);
    metadata.put("seed_document_id", seedDocumentId);
    metadata.put("seed_score", seedScore);
    metadata.put("parent_context_expanded", true);
    copyIfAbsent(metadata, seedMetadata, "query_transformed");
    copyIfAbsent(metadata, seedMetadata, "original_query");
    copyIfAbsent(metadata, seedMetadata, "retrieval_query");
    copyIfAbsent(metadata, seedMetadata, "retrieval_query_index");
    copyIfAbsent(metadata, seedMetadata, "retrieval_query_rank");
    copyIfAbsent(metadata, seedMetadata, "retrieval_query_count");
    copyIfAbsent(metadata, seedMetadata, "retrieval_mode");
    copyIfAbsent(metadata, seedMetadata, "matched_by");
    copyIfAbsent(metadata, seedMetadata, "vector_rank");
    copyIfAbsent(metadata, seedMetadata, "keyword_rank");
    copyIfAbsent(metadata, seedMetadata, "vector_score");
    copyIfAbsent(metadata, seedMetadata, "keyword_score");
    copyIfAbsent(metadata, seedMetadata, "hybrid_score");

    return Document.builder()
        .id(document.getId())
        .text(document.getText())
        .metadata(metadata)
        .score(document.getScore())
        .build();
  }

  private void addIfAbsent(Map<String, Document> documents, Document document) {
    documents.putIfAbsent(document.getId(), document);
  }

  private void copyIfAbsent(Map<String, Object> target, Map<String, Object> source, String key) {
    if (!target.containsKey(key) && source.containsKey(key)) {
      target.put(key, source.get(key));
    }
  }

  private String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    return value.toString();
  }

  private Integer integer(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null || value.toString().isBlank()) {
      return null;
    }
    return Integer.parseInt(value.toString());
  }

  private String validateTableName(String tableName) {
    if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("Invalid pgvector table name: " + tableName);
    }
    return tableName;
  }
}
