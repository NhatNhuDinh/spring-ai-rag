package com.example.essentialrag.service.retrieval;

import com.example.essentialrag.ingestion.support.TextbookText;
import com.example.essentialrag.service.retrieval.KeywordBooster.KeywordBoost;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class HybridSearchRepository {

  private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
  };

  private final JdbcClient jdbcClient;
  private final EmbeddingModel embeddingModel;
  private final KeywordBooster keywordBooster;
  private final ObjectMapper objectMapper;
  private final String tableName;
  private final int rrfK;

  public HybridSearchRepository(
      JdbcClient jdbcClient,
      EmbeddingModel embeddingModel,
      KeywordBooster keywordBooster,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName,
      @Value("${rag.retrieval.hybrid.rrf-k:60}") int rrfK) {

    this.jdbcClient = jdbcClient;
    this.embeddingModel = embeddingModel;
    this.keywordBooster = keywordBooster;
    this.objectMapper = new ObjectMapper();
    this.tableName = validateSqlIdentifier(tableName);
    this.rrfK = Math.max(1, rrfK);
  }

  public List<Document> search(
      QueryTransformation transformation,
      String retrievalQuery,
      int retrievalQueryIndex,
      RetrievalFilter filter,
      int candidateK,
      int limit,
      double similarityThreshold) {

    String keyword = fullTextQuery(retrievalQuery);
    String normalizedKeyword = normalizedFullTextQuery(retrievalQuery);
    String embeddingLiteral = vectorLiteral(embeddingModel.embed(retrievalQuery));
    String sql = hybridSql(tableName, filter);

    JdbcClient.StatementSpec spec = jdbcClient.sql(sql)
        .param("embedding", embeddingLiteral)
        .param("keyword", keyword)
        .param("normalizedKeyword", normalizedKeyword)
        .param("candidateK", Math.max(1, candidateK))
        .param("rrfK", rrfK)
        .param("limit", Math.max(1, limit))
        .param("similarityThreshold", similarityThreshold);
    spec = bindFilter(spec, filter);

    return spec.query((rs, rowNum) -> toDocument(rs, transformation, retrievalQuery, retrievalQueryIndex))
        .list();
  }

  static String hybridSql(String tableName, RetrievalFilter filter) {
    String where = metadataWhereClause("d", filter);
    return """
        with q as (
          select
            websearch_to_tsquery('simple', :keyword) as query,
            websearch_to_tsquery('simple', :normalizedKeyword) as normalized_query
        ),
        vector_hits as (
          select
            d.id,
            row_number() over (
              order by d.embedding <=> cast(:embedding as vector)
            ) as vector_rank,
            1 - (d.embedding <=> cast(:embedding as vector)) as vector_score
          from %s d
          where %s
            and (:similarityThreshold <= 0
              or 1 - (d.embedding <=> cast(:embedding as vector)) >= :similarityThreshold)
          order by d.embedding <=> cast(:embedding as vector)
          limit :candidateK
        ),
        keyword_candidates as (
          select
            d.id,
            ts_rank_cd(d.search_vector, q.query) as raw_keyword_score,
            ts_rank_cd(d.search_vector_normalized, q.normalized_query) as normalized_keyword_score
          from %s d, q
          where %s
            and (
              d.search_vector @@ q.query
              or d.search_vector_normalized @@ q.normalized_query
            )
        ),
        keyword_hits as (
          select
            id,
            row_number() over (
              order by greatest(raw_keyword_score, normalized_keyword_score) desc
            ) as keyword_rank,
            greatest(raw_keyword_score, normalized_keyword_score) as keyword_score,
            case
              when raw_keyword_score > 0 and normalized_keyword_score > 0 then 'raw,normalized'
              when raw_keyword_score > 0 then 'raw'
              else 'normalized'
            end as keyword_match_type
          from keyword_candidates
          order by keyword_score desc
          limit :candidateK
        ),
        fused_hits as (
          select
            coalesce(v.id, k.id) as id,
            v.vector_rank,
            k.keyword_rank,
            v.vector_score,
            k.keyword_score,
            k.keyword_match_type,
            coalesce(1.0 / (:rrfK + v.vector_rank), 0) +
              coalesce(1.0 / (:rrfK + k.keyword_rank), 0) as hybrid_score
          from vector_hits v
          full outer join keyword_hits k on v.id = k.id
        )
        select
          c.id::text as id,
          c.content,
          c.metadata::text as metadata,
          f.hybrid_score,
          f.vector_rank,
          f.keyword_rank,
          f.vector_score,
          f.keyword_score,
          f.keyword_match_type
        from fused_hits f
        join %s c on c.id = f.id
        order by f.hybrid_score desc,
          f.vector_rank nulls last,
          f.keyword_rank nulls last
        limit :limit
        """.formatted(tableName, where, tableName, where, tableName);
  }

  static String keywordSql(String tableName, RetrievalFilter filter) {
    String where = metadataWhereClause("d", filter);
    return """
        with q as (
          select
            websearch_to_tsquery('simple', :keyword) as query,
            websearch_to_tsquery('simple', :normalizedKeyword) as normalized_query
        )
        select
          d.id::text as id,
          d.content,
          d.metadata::text as metadata,
          greatest(
            ts_rank_cd(d.search_vector, q.query),
            ts_rank_cd(d.search_vector_normalized, q.normalized_query)
          ) as keyword_score
        from %s d, q
        where %s
          and (
            d.search_vector @@ q.query
            or d.search_vector_normalized @@ q.normalized_query
          )
        order by keyword_score desc
        limit :limit
        """.formatted(tableName, where);
  }

  static String matchedBy(Integer vectorRank, Integer keywordRank) {
    if (vectorRank != null && keywordRank != null) {
      return "vector,keyword";
    }
    if (vectorRank != null) {
      return "vector";
    }
    return "keyword";
  }

  static String fullTextQuery(String query) {
    if (query == null) {
      return "";
    }
    return query
        .replaceAll("[^\\p{L}\\p{N}\\s]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  static String normalizedFullTextQuery(String query) {
    return TextbookText.normalizeForMatching(fullTextQuery(query)).toLowerCase(Locale.ROOT);
  }

  private static String metadataWhereClause(String alias, RetrievalFilter filter) {
    StringBuilder where = new StringBuilder("""
        %s.metadata->>'chunk_level' = 'child'
            and %s.metadata->>'document_type' = 'textbook'
        """.formatted(alias, alias));

    if (filter.hasSourceFile()) {
      where.append("\n    and ").append(alias).append(".metadata->>'source_file' = :sourceFile");
    }
    if (filter.hasChapterNumber()) {
      where.append("\n    and ").append(alias).append(".metadata->>'chapter_number' = :chapterNumber");
    }
    if (filter.hasBlockType()) {
      where.append("\n    and ").append(alias).append(".metadata->>'block_type' = :blockType");
    }
    return where.toString();
  }

  private JdbcClient.StatementSpec bindFilter(JdbcClient.StatementSpec spec, RetrievalFilter filter) {
    if (filter.hasSourceFile()) {
      spec = spec.param("sourceFile", filter.sourceFile());
    }
    if (filter.hasChapterNumber()) {
      spec = spec.param("chapterNumber", filter.chapterNumber());
    }
    if (filter.hasBlockType()) {
      spec = spec.param("blockType", filter.blockType());
    }
    return spec;
  }

  private Document toDocument(
      ResultSet rs,
      QueryTransformation transformation,
      String retrievalQuery,
      int retrievalQueryIndex) throws SQLException {

    Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
    Integer vectorRank = integer(rs, "vector_rank");
    Integer keywordRank = integer(rs, "keyword_rank");
    Double vectorScore = decimal(rs, "vector_score");
    Double keywordScore = decimal(rs, "keyword_score");
    String keywordMatchType = rs.getString("keyword_match_type");
    double hybridScore = rs.getDouble("hybrid_score");

    Document base = Document.builder()
        .id(rs.getString("id"))
        .text(rs.getString("content"))
        .metadata(metadata)
        .score(hybridScore)
        .build();
    KeywordBoost boost = keywordBooster.score(base, transformation);

    Map<String, Object> enriched = new LinkedHashMap<>(metadata);
    enriched.put("query_transformed", transformation.retrievalQueries().size() > 1);
    enriched.put("original_query", transformation.originalQuery());
    enriched.put("retrieval_query", retrievalQuery);
    enriched.put("retrieval_query_index", retrievalQueryIndex);
    enriched.put("retrieval_query_count", transformation.retrievalQueries().size());
    enriched.put("retrieval_mode", "hybrid");
    enriched.put("matched_by", matchedBy(vectorRank, keywordRank));
    putIfNotNull(enriched, "vector_rank", vectorRank);
    putIfNotNull(enriched, "keyword_rank", keywordRank);
    putIfNotNull(enriched, "vector_score", vectorScore);
    putIfNotNull(enriched, "keyword_score", keywordScore);
    putIfNotNull(enriched, "keyword_match_type", keywordMatchType);
    enriched.put("hybrid_score", hybridScore);
    enriched.put("hybrid_rrf_score", hybridScore);
    enriched.put("hybrid_rrf_k", rrfK);
    enriched.put("keyword_query", retrievalQuery);
    enriched.put("keyword_boost_score", boost.score());
    enriched.put("keyword_matched_terms", boost.matchedTerms());

    return Document.builder()
        .id(base.getId())
        .text(base.getText())
        .metadata(enriched)
        .score(hybridScore)
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

  private Integer integer(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : ((Number) value).intValue();
  }

  private Double decimal(ResultSet rs, String column) throws SQLException {
    Object value = rs.getObject(column);
    return value == null ? null : ((Number) value).doubleValue();
  }

  private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
    if (value != null) {
      metadata.put(key, value);
    }
  }

  private String vectorLiteral(float[] embedding) {
    StringBuilder vector = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        vector.append(',');
      }
      vector.append(String.format(Locale.ROOT, "%s", embedding[i]));
    }
    vector.append(']');
    return vector.toString();
  }

  private String validateSqlIdentifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_]+")) {
      throw new IllegalArgumentException("Invalid pgvector table name: " + value);
    }
    return value;
  }
}
