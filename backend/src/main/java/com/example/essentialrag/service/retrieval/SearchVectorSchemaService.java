package com.example.essentialrag.service.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SearchVectorSchemaService {

  private static final Logger logger = LoggerFactory.getLogger(SearchVectorSchemaService.class);

  private final JdbcClient jdbcClient;
  private final String tableName;
  private final String indexName;
  private final String normalizedIndexName;
  private final String normalizeFunctionName;

  public SearchVectorSchemaService(
      JdbcClient jdbcClient,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {

    this.jdbcClient = jdbcClient;
    this.tableName = validateSqlIdentifier(tableName, "pgvector table name");
    this.indexName = validateSqlIdentifier(tableName + "_search_vector_idx", "search vector index name");
    this.normalizedIndexName = validateSqlIdentifier(
        tableName + "_search_vector_normalized_idx",
        "normalized search vector index name");
    this.normalizeFunctionName = validateSqlIdentifier(
        tableName + "_normalize_vi",
        "Vietnamese normalization function name");
  }

  public void ensureSchemaAndRefresh() {
    ensureSchema();
    refreshSearchVectors();
  }

  void ensureSchema() {
    jdbcClient.sql("""
        create or replace function %s(input text)
        returns text
        language sql
        immutable
        as $$
          select lower(translate(coalesce(input, ''),
            'àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬÈÉẺẼẸÊỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢÙÚỦŨỤƯỪỨỬỮỰỲÝỶỸỴĐ',
            'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyydAAAAAAAAAAAAAAAAAEEEEEEEEEEEIIIIIOOOOOOOOOOOOOOOOOUUUUUUUUUUUYYYYYD'))
        $$
        """.formatted(normalizeFunctionName))
        .update();
    jdbcClient.sql("alter table %s add column if not exists search_text text".formatted(tableName))
        .update();
    jdbcClient.sql("alter table %s add column if not exists search_vector tsvector".formatted(tableName))
        .update();
    jdbcClient.sql("alter table %s add column if not exists search_text_normalized text".formatted(tableName))
        .update();
    jdbcClient.sql("alter table %s add column if not exists search_vector_normalized tsvector".formatted(tableName))
        .update();
    jdbcClient.sql("""
        create index if not exists %s
        on %s using gin(search_vector)
        """.formatted(indexName, tableName))
        .update();
    jdbcClient.sql("""
        create index if not exists %s
        on %s using gin(search_vector_normalized)
        """.formatted(normalizedIndexName, tableName))
        .update();
  }

  void refreshSearchVectors() {
    int updated = jdbcClient.sql(refreshSearchVectorsSql())
        .update();
    logger.info("Refreshed PostgreSQL full-text search vectors for {} textbook chunks.", updated);
  }

  String refreshSearchVectorsSql() {
    return """
        with prepared as (
          select
            id,
            concat_ws(' ',
              content,
              metadata->>'book_title',
              metadata->>'chapter_title',
              metadata->>'section_path',
              metadata->>'section_title',
              metadata->>'block_type'
            ) as searchable
          from %s
          where metadata->>'chunk_level' = 'child'
            and metadata->>'document_type' = 'textbook'
        )
        update %s target
        set search_text = prepared.searchable,
            search_vector = to_tsvector('simple', prepared.searchable),
            search_text_normalized = %s(prepared.searchable),
            search_vector_normalized = to_tsvector('simple', %s(prepared.searchable))
        from prepared
        where target.id = prepared.id
        """.formatted(tableName, tableName, normalizeFunctionName, normalizeFunctionName)
        .stripIndent();
  }

  private String validateSqlIdentifier(String value, String label) {
    if (value == null || !value.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("Invalid " + label + ": " + value);
    }
    return value;
  }

  @Configuration
  static class SearchVectorSchemaRunnerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    ApplicationRunner refreshSearchVectorsOnStartup(SearchVectorSchemaService schemaService) {
      return args -> schemaService.ensureSchemaAndRefresh();
    }
  }
}
