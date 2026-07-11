package com.example.essentialrag.service;

import com.example.essentialrag.service.retrieval.HybridSearchRepository;
import com.example.essentialrag.service.retrieval.ParentContextExpander;
import com.example.essentialrag.service.retrieval.QueryTransformation;
import com.example.essentialrag.service.retrieval.QueryTransformer;
import com.example.essentialrag.service.retrieval.RetrievalFilter;
import com.example.essentialrag.service.retrieval.RetrievalIntent;
import com.example.essentialrag.service.retrieval.TextbookRetrievalResult;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TextbookRetrievalService {

  private final QueryTransformer queryTransformer;
  private final HybridSearchRepository hybridSearchRepository;
  private final ParentContextExpander parentContextExpander;
  private final int defaultTopK;
  private final double defaultSimilarityThreshold;
  private final int keywordCandidateLimit;
  private final double minVectorScoreForFilter;
  private final double minKeywordScoreForFilter;
  private final double minHybridScoreForFilter;

  public TextbookRetrievalService(
      QueryTransformer queryTransformer,
      HybridSearchRepository hybridSearchRepository,
      ParentContextExpander parentContextExpander,
      @Value("${rag.retrieval.top-k:8}") int defaultTopK,
      @Value("${rag.retrieval.similarity-threshold:0.0}") double defaultSimilarityThreshold,
      @Value("${rag.retrieval.hybrid.keyword-candidate-limit:40}") int keywordCandidateLimit,
      @Value("${rag.retrieval.hybrid.min-vector-score-for-filter:0.25}") double minVectorScoreForFilter,
      @Value("${rag.retrieval.hybrid.min-keyword-score-for-filter:0.01}") double minKeywordScoreForFilter,
      @Value("${rag.retrieval.hybrid.min-hybrid-score-for-filter:0.012}") double minHybridScoreForFilter) {

    this.queryTransformer = queryTransformer;
    this.hybridSearchRepository = hybridSearchRepository;
    this.parentContextExpander = parentContextExpander;
    this.defaultTopK = Math.max(1, defaultTopK);
    this.defaultSimilarityThreshold = defaultSimilarityThreshold;
    this.keywordCandidateLimit = Math.max(1, keywordCandidateLimit);
    this.minVectorScoreForFilter = minVectorScoreForFilter;
    this.minKeywordScoreForFilter = minKeywordScoreForFilter;
    this.minHybridScoreForFilter = minHybridScoreForFilter;
  }

  public TextbookRetrievalResult retrieve(String query, Integer topK, Double similarityThreshold) {
    List<Document> seeds = searchHybrid(query, topK, similarityThreshold);
    return new TextbookRetrievalResult(seeds, parentContextExpander.expand(seeds));
  }

  public List<Document> searchHybrid(String query, Integer topK, Double similarityThreshold) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    int resolvedTopK = resolveTopK(topK);
    double resolvedThreshold = resolveSimilarityThreshold(similarityThreshold);
    QueryTransformation transformation = queryTransformer.transform(query);
    return searchHybridWithFallback(transformation, resolvedTopK, resolvedThreshold);
  }

  private List<Document> searchHybridWithFallback(
      QueryTransformation transformation,
      int topK,
      double similarityThreshold) {

    int candidateK = Math.max(keywordCandidateLimit, topK);
    List<String> retrievalQueries = transformation.retrievalQueries();
    List<float[]> embeddings = hybridSearchRepository.embed(retrievalQueries);
    if (embeddings.size() != retrievalQueries.size()) {
      throw new IllegalStateException("Embedding count does not match retrieval query count.");
    }

    for (RetrievalFilter filter : retrievalFilters(transformation.intent())) {
      Map<String, HybridAggregate> merged = new LinkedHashMap<>();

      for (int queryIndex = 0; queryIndex < retrievalQueries.size(); queryIndex++) {
        String retrievalQuery = retrievalQueries.get(queryIndex);
        List<Document> documents = hybridSearchRepository.search(
            transformation,
            retrievalQuery,
            queryIndex + 1,
            embeddings.get(queryIndex),
            filter,
            candidateK,
            candidateK,
            similarityThreshold);

        for (int rank = 0; rank < documents.size(); rank++) {
          Document document = documents.get(rank);
          merged.computeIfAbsent(document.getId(), ignored -> new HybridAggregate())
              .add(document, queryIndex + 1, rank + 1);
        }
      }

      if (hasAcceptableHybridHits(merged.values())) {
        return merged.values().stream()
            .map(HybridAggregate::toDocument)
            .sorted(Comparator
                .comparingDouble((Document document) -> scoreOrZero(document)).reversed()
                .thenComparing(document -> integerOrMax(document.getMetadata(), "retrieval_query_index"))
                .thenComparing(document -> integerOrMax(document.getMetadata(), "retrieval_query_rank")))
            .limit(topK)
            .toList();
      }
    }

    return List.of();
  }

  private boolean hasAcceptableHybridHits(Iterable<HybridAggregate> aggregates) {
    double bestHybridScore = 0.0;
    double bestVectorScore = 0.0;
    double bestKeywordScore = 0.0;
    boolean hasAnyHit = false;

    for (HybridAggregate aggregate : aggregates) {
      hasAnyHit = true;
      bestHybridScore = Math.max(bestHybridScore, aggregate.scoreSum);
      bestVectorScore = Math.max(bestVectorScore, aggregate.maxVectorScore);
      bestKeywordScore = Math.max(bestKeywordScore, aggregate.maxKeywordScore);
    }

    return hasAnyHit
        && bestHybridScore >= minHybridScoreForFilter
        && (bestVectorScore >= minVectorScoreForFilter
            || bestKeywordScore >= minKeywordScoreForFilter);
  }

  private List<RetrievalFilter> retrievalFilters(RetrievalIntent intent) {
    Set<RetrievalFilter> filters = new LinkedHashSet<>();
    filters.add(new RetrievalFilter(intent.sourceFile(), intent.chapterNumber(), intent.blockType()));

    if (intent.hasBlockType() && intent.hasSourceFile()) {
      filters.add(new RetrievalFilter(intent.sourceFile(), null, intent.blockType()));
    }
    if (intent.hasSourceFile() && intent.hasChapterNumber()) {
      filters.add(new RetrievalFilter(intent.sourceFile(), intent.chapterNumber(), null));
    }
    if (intent.hasBlockType()) {
      filters.add(new RetrievalFilter(null, null, intent.blockType()));
    }
    if (intent.hasSourceFile()) {
      filters.add(new RetrievalFilter(intent.sourceFile(), null, null));
    }
    filters.add(new RetrievalFilter(null, null, null));

    return new ArrayList<>(filters);
  }

  private double scoreOrZero(Document document) {
    return document.getScore() == null ? 0.0 : document.getScore();
  }

  private int integerOrMax(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    return value == null ? Integer.MAX_VALUE : Integer.parseInt(value.toString());
  }

  private int resolveTopK(Integer topK) {
    if (topK == null) {
      return defaultTopK;
    }
    if (topK < 1 || topK > 30) {
      throw new IllegalArgumentException("topK must be between 1 and 30");
    }
    return topK;
  }

  private double resolveSimilarityThreshold(Double similarityThreshold) {
    if (similarityThreshold == null) {
      return defaultSimilarityThreshold;
    }
    if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
      throw new IllegalArgumentException("similarityThreshold must be between 0.0 and 1.0");
    }
    return similarityThreshold;
  }

  private static final class HybridAggregate {

    private Document bestDocument;
    private double scoreSum;
    private double bestSingleScore;
    private int bestQueryIndex = Integer.MAX_VALUE;
    private int bestQueryRank = Integer.MAX_VALUE;
    private int matchedQueryCount;
    private boolean vectorMatched;
    private boolean keywordMatched;
    private Integer bestVectorRank;
    private Integer bestKeywordRank;
    private double maxVectorScore;
    private double maxKeywordScore;
    private final List<Integer> matchedQueryIndexes = new ArrayList<>();
    private final List<String> matchedRetrievalQueries = new ArrayList<>();
    private final Set<String> keywordMatchTypes = new LinkedHashSet<>();

    void add(Document document, int queryIndex, int queryRank) {
      double score = document.getScore() == null ? 0.0 : document.getScore();
      scoreSum += score;
      matchedQueryCount++;
      matchedQueryIndexes.add(queryIndex);
      Object retrievalQuery = document.getMetadata().get("retrieval_query");
      if (retrievalQuery != null) {
        matchedRetrievalQueries.add(retrievalQuery.toString());
      }

      Integer vectorRank = integer(document.getMetadata(), "vector_rank");
      Integer keywordRank = integer(document.getMetadata(), "keyword_rank");
      Double vectorScore = decimal(document.getMetadata(), "vector_score");
      Double keywordScore = decimal(document.getMetadata(), "keyword_score");

      vectorMatched = vectorMatched || vectorRank != null;
      keywordMatched = keywordMatched || keywordRank != null;
      bestVectorRank = minRank(bestVectorRank, vectorRank);
      bestKeywordRank = minRank(bestKeywordRank, keywordRank);
      maxVectorScore = Math.max(maxVectorScore, vectorScore == null ? 0.0 : vectorScore);
      maxKeywordScore = Math.max(maxKeywordScore, keywordScore == null ? 0.0 : keywordScore);
      Object keywordMatchType = document.getMetadata().get("keyword_match_type");
      if (keywordMatchType != null && !keywordMatchType.toString().isBlank()) {
        keywordMatchTypes.add(keywordMatchType.toString());
      }

      if (bestDocument == null
          || score > bestSingleScore
          || (Double.compare(score, bestSingleScore) == 0 && queryRank < bestQueryRank)) {
        bestDocument = document;
        bestSingleScore = score;
        bestQueryIndex = queryIndex;
        bestQueryRank = queryRank;
      }
    }

    Document toDocument() {
      Map<String, Object> metadata = new LinkedHashMap<>(bestDocument.getMetadata());
      metadata.put("hybrid_score", scoreSum);
      metadata.put("hybrid_rrf_score", scoreSum);
      metadata.put("hybrid_score_strategy", "rrf_sum_across_queries");
      metadata.put("hybrid_query_count_matched", matchedQueryCount);
      metadata.put("hybrid_matched_query_indexes", matchedQueryIndexes);
      metadata.put("hybrid_matched_retrieval_queries", matchedRetrievalQueries);
      metadata.put("matched_by", matchedBy());
      metadata.put("retrieval_query_index", bestQueryIndex);
      metadata.put("retrieval_query_rank", bestQueryRank);
      putIfNotNull(metadata, "vector_rank", bestVectorRank);
      putIfNotNull(metadata, "keyword_rank", bestKeywordRank);
      putIfPositive(metadata, "vector_score", maxVectorScore);
      putIfPositive(metadata, "keyword_score", maxKeywordScore);
      if (!keywordMatchTypes.isEmpty()) {
        metadata.put("keyword_match_type", String.join(",", keywordMatchTypes));
      }

      return Document.builder()
          .id(bestDocument.getId())
          .text(bestDocument.getText())
          .metadata(metadata)
          .score(scoreSum)
          .build();
    }

    private String matchedBy() {
      if (vectorMatched && keywordMatched) {
        return "vector,keyword";
      }
      return vectorMatched ? "vector" : "keyword";
    }

    private static Integer minRank(Integer current, Integer candidate) {
      if (candidate == null) {
        return current;
      }
      return current == null ? candidate : Math.min(current, candidate);
    }

    private static Integer integer(Map<String, Object> metadata, String key) {
      Object value = metadata.get(key);
      if (value instanceof Number number) {
        return number.intValue();
      }
      return value == null ? null : Integer.parseInt(value.toString());
    }

    private static Double decimal(Map<String, Object> metadata, String key) {
      Object value = metadata.get(key);
      if (value instanceof Number number) {
        return number.doubleValue();
      }
      return value == null ? null : Double.parseDouble(value.toString());
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
      if (value != null) {
        metadata.put(key, value);
      }
    }

    private static void putIfPositive(Map<String, Object> metadata, String key, double value) {
      if (value > 0) {
        metadata.put(key, value);
      }
    }
  }
}
