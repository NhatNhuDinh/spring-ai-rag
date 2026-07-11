package com.example.essentialrag.service.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SearchVectorSchemaServiceTests {

  @Test
  void refreshSearchVectorsSqlUsesValidWithUpdateShapeAndFormatsAllIdentifiers() {
    SearchVectorSchemaService service = new SearchVectorSchemaService(
        mock(JdbcClient.class),
        "tool_rag_documents");

    String sql = service.refreshSearchVectorsSql();

    assertThat(sql)
        .startsWith("with prepared as")
        .contains("from tool_rag_documents")
        .contains("update tool_rag_documents target")
        .contains("search_text_normalized = tool_rag_documents_normalize_vi(prepared.searchable)")
        .contains("search_vector_normalized = to_tsvector('simple', tool_rag_documents_normalize_vi(prepared.searchable))")
        .doesNotContain("%s");
  }
}
