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
    List<SeedWindow> windows = new ArrayList<>();

    for (int index = 0; index < seedDocuments.size() && results.size() < properties.maxResults(); index++) {
      Document seed = seedDocuments.get(index);
      int seedRank = index + 1;
      addIfAbsent(results, tag(seed, "seed", seedRank, seed.getId(), seed.getScore(), seed.getMetadata()));

      String parentId = string(seed.getMetadata(), "parent_id");
      String sourceFile = string(seed.getMetadata(), "source_file");
      Integer chunkIndex = integer(seed.getMetadata(), "chunk_index");
      if (parentId != null && sourceFile != null && chunkIndex != null) {
        windows.add(new SeedWindow(
            seed,
            seedRank,
            sourceFile,
            parentId,
            Math.max(0, chunkIndex - properties.windowBefore()),
            chunkIndex + properties.windowAfter()));
      }
    }

    List<Document> siblings = findSiblingChunks(windows);
    for (SeedWindow window : windows) {
      for (Document sibling : siblings) {
        if (results.size() >= properties.maxResults()) {
          return new ArrayList<>(results.values());
        }
        if (sibling.getId().equals(window.seed().getId()) || !window.contains(sibling)) {
          continue;
        }
        addIfAbsent(results, tag(
            sibling,
            "parent_context",
            window.seedRank(),
            window.seed().getId(),
            window.seed().getScore(),
            window.seed().getMetadata()));
      }
    }

    return new ArrayList<>(results.values());
  }

  private List<Document> findSiblingChunks(List<SeedWindow> windows) {
    if (windows.isEmpty()) {
      return List.of();
    }

    StringBuilder conditions = new StringBuilder();
    List<Object> parameters = new ArrayList<>();
    for (SeedWindow window : windows) {
      if (!conditions.isEmpty()) {
        conditions.append(" or ");
      }
      conditions.append("(metadata->>'source_file' = ? and metadata->>'parent_id' = ? ")
          .append("and (metadata->>'chunk_index')::int between ? and ?)");
      parameters.add(window.sourceFile());
      parameters.add(window.parentId());
      parameters.add(window.fromChunkIndex());
      parameters.add(window.toChunkIndex());
    }

    String sql = """
        select id::text as id, content, metadata::text as metadata
        from %s
        where metadata->>'chunk_level' = 'child'
          and metadata->>'document_type' = 'textbook'
          and (%s)
        order by metadata->>'source_file', metadata->>'parent_id', (metadata->>'chunk_index')::int
        """.formatted(tableName, conditions);

    return jdbcTemplate.query(sql, (rs, rowNum) -> toDocument(rs), parameters.toArray());
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

    return Document.builder()
        .id(document.getId())
        .text(document.getText())
        .metadata(metadata)
        .score("seed".equals(retrievalRole) ? document.getScore() : null)
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
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  private Integer integer(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    return value == null || value.toString().isBlank() ? null : Integer.parseInt(value.toString());
  }

  private String validateTableName(String tableName) {
    if (tableName == null || !tableName.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("Invalid pgvector table name: " + tableName);
    }
    return tableName;
  }

  private record SeedWindow(
      Document seed,
      int seedRank,
      String sourceFile,
      String parentId,
      int fromChunkIndex,
      int toChunkIndex) {

    boolean contains(Document document) {
      Map<String, Object> metadata = document.getMetadata();
      Object source = metadata.get("source_file");
      Object parent = metadata.get("parent_id");
      Object index = metadata.get("chunk_index");
      if (source == null || parent == null || index == null) {
        return false;
      }
      int chunkIndex = index instanceof Number number
          ? number.intValue()
          : Integer.parseInt(index.toString());
      return sourceFile.equals(source.toString())
          && parentId.equals(parent.toString())
          && chunkIndex >= fromChunkIndex
          && chunkIndex <= toChunkIndex;
    }
  }
}
