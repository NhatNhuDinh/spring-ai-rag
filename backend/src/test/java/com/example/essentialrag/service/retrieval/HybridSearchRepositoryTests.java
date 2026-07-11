package com.example.essentialrag.service.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearchRepositoryTests {

  @Test
  void hybridSqlUsesPgvectorFullTextSearchAndRrf() {
    String sql = HybridSearchRepository.hybridSql(
        "tool_rag_documents",
        new RetrievalFilter("triet_2.pdf", "3", "main_content"));

    assertThat(sql)
        .contains("vector_hits")
        .contains("keyword_hits")
        .contains("d.embedding <=> cast(:embedding as vector)")
        .contains("websearch_to_tsquery('simple', :keyword)")
        .contains("websearch_to_tsquery('simple', :normalizedKeyword)")
        .contains("ts_rank_cd(d.search_vector, q.query)")
        .contains("ts_rank_cd(d.search_vector_normalized, q.normalized_query)")
        .contains("d.search_vector @@ q.query")
        .contains("d.search_vector_normalized @@ q.normalized_query")
        .contains("1.0 / (:rrfK + v.vector_rank)")
        .contains("1.0 / (:rrfK + k.keyword_rank)")
        .contains("d.metadata->>'source_file' = :sourceFile")
        .contains("d.metadata->>'chapter_number' = :chapterNumber")
        .contains("d.metadata->>'block_type' = :blockType");
  }

  @Test
  void matchedByDistinguishesVectorKeywordAndOverlapHits() {
    assertThat(HybridSearchRepository.matchedBy(1, null)).isEqualTo("vector");
    assertThat(HybridSearchRepository.matchedBy(null, 1)).isEqualTo("keyword");
    assertThat(HybridSearchRepository.matchedBy(1, 2)).isEqualTo("vector,keyword");
  }

  @Test
  void fullTextQueryKeepsVietnameseAccentsButRemovesSearchSyntax() {
    assertThat(HybridSearchRepository.fullTextQuery("Giá trị thặng dư trong Mác - Lênin là gì?"))
        .isEqualTo("Giá trị thặng dư trong Mác Lênin là gì");
  }

  @Test
  void normalizedFullTextQueryRemovesVietnameseAccents() {
    assertThat(HybridSearchRepository.normalizedFullTextQuery("Giá trị thặng dư trong Mác - Lênin là gì?"))
        .isEqualTo("gia tri thang du trong mac lenin la gi");
  }
}
