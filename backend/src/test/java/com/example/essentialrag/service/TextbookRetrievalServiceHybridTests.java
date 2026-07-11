package com.example.essentialrag.service;

import com.example.essentialrag.service.retrieval.HybridSearchRepository;
import com.example.essentialrag.service.retrieval.ParentContextExpander;
import com.example.essentialrag.service.retrieval.QueryIntentDetector;
import com.example.essentialrag.service.retrieval.QueryTransformation;
import com.example.essentialrag.service.retrieval.QueryTransformer;
import com.example.essentialrag.service.retrieval.RetrievalFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextbookRetrievalServiceHybridTests {

  @Test
  void hybridSearchReturnsVectorKeywordAndOverlapHitsByRrfScore() {
    HybridSearchRepository repository = mock(HybridSearchRepository.class);
    TextbookRetrievalService service = service(repository);

    when(repository.search(any(), any(), eq(1), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of(
            document("keyword", 0.015, "keyword", null, 1),
            document("vector", 0.016, "vector", 1, null),
            document("both", 0.032, "vector,keyword", 1, 2)));
    when(repository.search(any(), any(), eq(2), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of());
    when(repository.search(any(), any(), eq(3), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of());
    when(repository.search(any(), any(), eq(4), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of());

    List<Document> results = service.searchHybrid("Vì sao công nhân bị bóc lột?", 3, 0.0);

    assertThat(results).extracting(Document::getId)
        .containsExactly("both", "vector", "keyword");
    assertThat(results).extracting(document -> document.getMetadata().get("matched_by"))
        .containsExactly("vector,keyword", "vector", "keyword");
  }

  @Test
  void hybridSearchFallsBackAfterFilteredSearchProducesNoHits() {
    HybridSearchRepository repository = mock(HybridSearchRepository.class);
    TextbookRetrievalService service = service(repository);

    when(repository.search(any(), any(), anyInt(), any(), anyInt(), anyInt(), anyDouble()))
        .thenAnswer(invocation -> {
          RetrievalFilter filter = invocation.getArgument(3);
          if ("triet_2.pdf".equals(filter.sourceFile())
              && "3".equals(filter.chapterNumber())
              && filter.blockType() == null) {
            return List.of(document("wide-filter-hit", 0.02, "keyword", null, 1));
          }
          return List.of();
        });

    List<Document> results = service.searchHybrid(
        "Câu hỏi ôn tập chương 3 Kinh tế chính trị gồm những gì?",
        4,
        0.0);

    assertThat(results).extracting(Document::getId)
        .containsExactly("wide-filter-hit");

    ArgumentCaptor<RetrievalFilter> filters = ArgumentCaptor.forClass(RetrievalFilter.class);
    verify(repository, atLeastOnce()).search(
        any(QueryTransformation.class),
        any(),
        anyInt(),
        filters.capture(),
        anyInt(),
        anyInt(),
        anyDouble());

    assertThat(filters.getAllValues())
        .anySatisfy(filter -> assertThat(filter.blockType()).isEqualTo("review_question"))
        .anySatisfy(filter -> {
          assertThat(filter.sourceFile()).isEqualTo("triet_2.pdf");
          assertThat(filter.chapterNumber()).isEqualTo("3");
          assertThat(filter.blockType()).isNull();
        });
  }

  @Test
  void hybridSearchSumsRrfScoresAcrossQueryVariants() {
    HybridSearchRepository repository = mock(HybridSearchRepository.class);
    TextbookRetrievalService service = service(repository);

    when(repository.search(any(), any(), eq(1), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of(
            document("single-query-hit", 0.018, "keyword", null, 1),
            document("multi-query-hit", 0.012, "keyword", null, 2)));
    when(repository.search(any(), any(), eq(2), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of(document("multi-query-hit", 0.012, "keyword", null, 1)));
    when(repository.search(any(), any(), eq(3), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of());
    when(repository.search(any(), any(), eq(4), any(), anyInt(), anyInt(), anyDouble()))
        .thenReturn(List.of());

    List<Document> results = service.searchHybrid("Vì sao công nhân bị bóc lột?", 2, 0.0);

    assertThat(results).extracting(Document::getId)
        .containsExactly("multi-query-hit", "single-query-hit");
    assertThat(results.get(0).getMetadata())
        .containsEntry("hybrid_score_strategy", "rrf_sum_across_queries")
        .containsEntry("hybrid_query_count_matched", 2);
  }

  @Test
  void hybridSearchFallsBackWhenNarrowFilterOnlyHasWeakVectorHits() {
    HybridSearchRepository repository = mock(HybridSearchRepository.class);
    TextbookRetrievalService service = service(repository);

    when(repository.search(any(), any(), anyInt(), any(), anyInt(), anyInt(), anyDouble()))
        .thenAnswer(invocation -> {
          RetrievalFilter filter = invocation.getArgument(3);
          if ("review_question".equals(filter.blockType())) {
            return List.of(document(
                "weak-vector-only-hit",
                0.01,
                "vector",
                1,
                null,
                0.10,
                0.0));
          }
          if ("triet_2.pdf".equals(filter.sourceFile())
              && "3".equals(filter.chapterNumber())
              && filter.blockType() == null) {
            return List.of(document("wide-keyword-hit", 0.02, "keyword", null, 1));
          }
          return List.of();
        });

    List<Document> results = service.searchHybrid(
        "Câu hỏi ôn tập chương 3 Kinh tế chính trị gồm những gì?",
        4,
        0.0);

    assertThat(results).extracting(Document::getId)
        .containsExactly("wide-keyword-hit");
  }

  private TextbookRetrievalService service(HybridSearchRepository repository) {
    QueryIntentDetector detector = new QueryIntentDetector();
    return new TextbookRetrievalService(
        mock(VectorStore.class),
        detector,
        new QueryTransformer(detector, true, 4),
        repository,
        mock(ParentContextExpander.class),
        8,
        0.0,
        true,
        40,
        0.25,
        0.0);
  }

  private Document document(
      String id,
      double score,
      String matchedBy,
      Integer vectorRank,
      Integer keywordRank) {

    return document(id, score, matchedBy, vectorRank, keywordRank, 0.82, 0.12);
  }

  private Document document(
      String id,
      double score,
      String matchedBy,
      Integer vectorRank,
      Integer keywordRank,
      double vectorScore,
      double keywordScore) {

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("source_file", "triet_2.pdf");
    metadata.put("chapter_number", "3");
    metadata.put("section_path", "I > 1");
    metadata.put("page_start", 75);
    metadata.put("page_end", 76);
    metadata.put("retrieval_mode", "hybrid");
    metadata.put("matched_by", matchedBy);
    metadata.put("hybrid_score", score);
    if (vectorRank != null) {
      metadata.put("vector_rank", vectorRank);
      metadata.put("vector_score", vectorScore);
    }
    if (keywordRank != null) {
      metadata.put("keyword_rank", keywordRank);
      metadata.put("keyword_score", keywordScore);
    }

    return Document.builder()
        .id(id)
        .text("Nội dung kiểm thử hybrid search")
        .metadata(metadata)
        .score(score)
        .build();
  }
}
