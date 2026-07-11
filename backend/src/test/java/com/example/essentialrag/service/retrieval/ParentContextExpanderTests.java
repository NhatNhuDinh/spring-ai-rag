package com.example.essentialrag.service.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentContextExpanderTests {

  @Test
  @SuppressWarnings("unchecked")
  void loadsSiblingWindowsWithOneDatabaseQuery() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    ParentContextExpander expander = new ParentContextExpander(
        jdbcTemplate,
        new ParentContextProperties(true, 1, 1, 12),
        "tool_rag_documents");

    List<Document> expanded = expander.expand(List.of(
        seed("seed-1", "parent-1", 3),
        seed("seed-2", "parent-2", 7)));

    assertThat(expanded).hasSize(2);
    verify(jdbcTemplate, times(1))
        .query(anyString(), any(RowMapper.class), any(Object[].class));
  }

  private Document seed(String id, String parentId, int chunkIndex) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source_file", "triet_1.pdf");
    metadata.put("parent_id", parentId);
    metadata.put("chunk_index", chunkIndex);
    return Document.builder()
        .id(id)
        .text("Seed content")
        .metadata(metadata)
        .score(0.8)
        .build();
  }
}
