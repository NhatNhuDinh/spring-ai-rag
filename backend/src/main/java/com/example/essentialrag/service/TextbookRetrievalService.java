package com.example.essentialrag.service;

import com.example.essentialrag.service.retrieval.HybridSearchRepository;
import com.example.essentialrag.service.retrieval.QueryIntentDetector;
import com.example.essentialrag.service.retrieval.ParentContextExpander;
import com.example.essentialrag.service.retrieval.QueryTransformation;
import com.example.essentialrag.service.retrieval.QueryTransformer;
import com.example.essentialrag.service.retrieval.RetrievalFilter;
import com.example.essentialrag.service.retrieval.RetrievalIntent;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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

  private static final String TEXTBOOK_CHILD_FILTER =
      "chunk_level == 'child' && document_type == 'textbook'";

  private final VectorStore vectorStore;
  private final QueryIntentDetector intentDetector;
  private final QueryTransformer queryTransformer;
  private final HybridSearchRepository hybridSearchRepository;
  private final ParentContextExpander parentContextExpander;
  private final int defaultTopK;
  private final double defaultSimilarityThreshold;
  private final boolean hybridEnabled;
  private final int keywordCandidateLimit;
  private final double minVectorScoreForFilter;
  private final double minHybridScoreForFilter;

  public TextbookRetrievalService(
      VectorStore vectorStore,
      QueryIntentDetector intentDetector,
      QueryTransformer queryTransformer,
      HybridSearchRepository hybridSearchRepository,
      ParentContextExpander parentContextExpander,
      @Value("${rag.retrieval.top-k:8}") int defaultTopK,
      @Value("${rag.retrieval.similarity-threshold:0.0}") double defaultSimilarityThreshold,
      @Value("${rag.retrieval.hybrid.enabled:true}") boolean hybridEnabled,
      @Value("${rag.retrieval.hybrid.keyword-candidate-limit:40}") int keywordCandidateLimit,
      @Value("${rag.retrieval.hybrid.min-vector-score-for-filter:0.25}") double minVectorScoreForFilter,
      @Value("${rag.retrieval.hybrid.min-hybrid-score-for-filter:0.0}") double minHybridScoreForFilter) {

    this.vectorStore = vectorStore;
    this.intentDetector = intentDetector;
    this.queryTransformer = queryTransformer;
    this.hybridSearchRepository = hybridSearchRepository;
    this.parentContextExpander = parentContextExpander;
    this.defaultTopK = defaultTopK;
    this.defaultSimilarityThreshold = defaultSimilarityThreshold;
    this.hybridEnabled = hybridEnabled;
    this.keywordCandidateLimit = Math.max(1, keywordCandidateLimit);
    this.minVectorScoreForFilter = minVectorScoreForFilter;
    this.minHybridScoreForFilter = minHybridScoreForFilter;
  }

  public List<Document> retrieveWithParentContext(String query, Integer topK, Double similarityThreshold) {
    return parentContextExpander.expand(searchHybrid(query, topK, similarityThreshold));
  }

  public QueryTransformation transformQuery(String query) {
    return queryTransformer.transform(query);
  }

  public List<Document> searchTransformed(String query, Integer topK, Double similarityThreshold) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    int resolvedTopK = resolveTopK(topK);
    double resolvedThreshold = resolveSimilarityThreshold(similarityThreshold);
    QueryTransformation transformation = queryTransformer.transform(query);
    return mergeTransformedResults(transformation, resolvedTopK, resolvedThreshold);
  }

  public List<Document> searchHybrid(String query, Integer topK, Double similarityThreshold) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    int resolvedTopK = resolveTopK(topK);
    double resolvedThreshold = resolveSimilarityThreshold(similarityThreshold);
    QueryTransformation transformation = queryTransformer.transform(query);

    if (!hybridEnabled) {
      return mergeTransformedResults(transformation, resolvedTopK, resolvedThreshold);
    }

    return searchHybridWithFallback(transformation, resolvedTopK, resolvedThreshold);
  }

  public List<Document> search(String query, Integer topK, Double similarityThreshold) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    int resolvedTopK = resolveTopK(topK);
    double resolvedThreshold = resolveSimilarityThreshold(similarityThreshold);
    RetrievalIntent intent = intentDetector.detect(query);

    for (String filter : filterCandidates(intent)) {
      List<Document> documents = search(query, resolvedTopK, resolvedThreshold, filter);
      if (!documents.isEmpty()) {
        return documents;
      }
    }

    return List.of();
  }

  private List<Document> search(String query, int topK, double similarityThreshold, RetrievalIntent intent) {
    for (String filter : filterCandidates(intent)) {
      List<Document> documents = search(query, topK, similarityThreshold, filter);
      if (!documents.isEmpty()) {
        return documents;
      }
    }
    return List.of();
  }

  private List<Document> mergeTransformedResults(
      QueryTransformation transformation,
      int topK,
      double similarityThreshold) {

    Map<String, RankedDocument> merged = new LinkedHashMap<>();
    List<String> retrievalQueries = transformation.retrievalQueries();

    for (int queryIndex = 0; queryIndex < retrievalQueries.size(); queryIndex++) {
      String retrievalQuery = retrievalQueries.get(queryIndex);
      List<Document> documents = search(retrievalQuery, topK, similarityThreshold, transformation.intent());

      for (int rank = 0; rank < documents.size(); rank++) {
        Document tagged = tagTransformedDocument(
            documents.get(rank),
            transformation,
            retrievalQuery,
            queryIndex + 1,
            rank + 1);

        RankedDocument existing = merged.get(tagged.getId());
        if (existing == null || compare(tagged, existing.document()) < 0) {
          merged.put(tagged.getId(), new RankedDocument(tagged, queryIndex + 1, rank + 1));
        }
      }
    }

    return merged.values().stream()
        .sorted(Comparator
            .comparingDouble((RankedDocument ranked) -> scoreOrZero(ranked.document())).reversed()
            .thenComparingInt(RankedDocument::queryIndex)
            .thenComparingInt(RankedDocument::rank))
        .limit(topK)
        .map(RankedDocument::document)
        .toList();
  }

  private List<Document> searchHybridWithFallback(
      QueryTransformation transformation,
      int topK,
      double similarityThreshold) {

    int candidateK = Math.max(keywordCandidateLimit, topK);

    for (RetrievalFilter filter : retrievalFilters(transformation.intent())) {
      Map<String, HybridAggregate> merged = new LinkedHashMap<>();
      List<String> retrievalQueries = transformation.retrievalQueries();

      for (int queryIndex = 0; queryIndex < retrievalQueries.size(); queryIndex++) {
        String retrievalQuery = retrievalQueries.get(queryIndex);
        List<Document> documents = hybridSearchRepository.search(
            transformation,
            retrievalQuery,
            queryIndex + 1,
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
    boolean hasKeywordHit = false;
    boolean hasAnyHit = false;

    for (HybridAggregate aggregate : aggregates) {
      hasAnyHit = true;
      bestHybridScore = Math.max(bestHybridScore, aggregate.scoreSum);
      bestVectorScore = Math.max(bestVectorScore, aggregate.maxVectorScore);
      hasKeywordHit = hasKeywordHit || aggregate.keywordMatched;
    }

    if (!hasAnyHit || bestHybridScore < minHybridScoreForFilter) {
      return false;
    }
    if (hasKeywordHit) {
      return true;
    }
    return bestVectorScore >= minVectorScoreForFilter;
  }

  private int compare(Document candidate, Document existing) {
    return Double.compare(scoreOrZero(existing), scoreOrZero(candidate));
  }

  private Document tagTransformedDocument(
      Document document,
      QueryTransformation transformation,
      String retrievalQuery,
      int queryIndex,
      int rank) {

    Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
    metadata.put("query_transformed", transformation.retrievalQueries().size() > 1);
    metadata.put("original_query", transformation.originalQuery());
    metadata.put("retrieval_query", retrievalQuery);
    metadata.put("retrieval_query_index", queryIndex);
    metadata.put("retrieval_query_rank", rank);
    metadata.put("retrieval_query_count", transformation.retrievalQueries().size());

    return Document.builder()
        .id(document.getId())
        .text(document.getText())
        .metadata(metadata)
        .score(document.getScore())
        .build();
  }

  private double scoreOrZero(Document document) {
    return document.getScore() == null ? 0.0 : document.getScore();
  }

  private int integerOrMax(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return Integer.MAX_VALUE;
    }
    return Integer.parseInt(value.toString());
  }

  private List<Document> search(String query, int topK, double similarityThreshold, String filterExpression) {
    return vectorStore.similaritySearch(
        SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(similarityThreshold)
            .filterExpression(filterExpression)
            .build());
  }

  private List<String> filterCandidates(RetrievalIntent intent) {
    Set<String> filters = new LinkedHashSet<>();

    if (intent.hasAnyFilter()) {
      filters.add(buildFilter(intent.sourceFile(), intent.chapterNumber(), intent.blockType()));

      if (intent.hasBlockType() && intent.hasSourceFile()) {
        filters.add(buildFilter(intent.sourceFile(), null, intent.blockType()));
      }
      if (intent.hasSourceFile() && intent.hasChapterNumber()) {
        filters.add(buildFilter(intent.sourceFile(), intent.chapterNumber(), null));
      }
      if (intent.hasBlockType()) {
        filters.add(buildFilter(null, null, intent.blockType()));
      }
      if (intent.hasSourceFile()) {
        filters.add(buildFilter(intent.sourceFile(), null, null));
      }
    }

    filters.add(TEXTBOOK_CHILD_FILTER);
    return new ArrayList<>(filters);
  }

  private List<RetrievalFilter> retrievalFilters(RetrievalIntent intent) {
    List<RetrievalFilter> filters = new ArrayList<>();
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

    return filters;
  }

  private String buildFilter(String sourceFile, String chapterNumber, String blockType) {
    List<String> clauses = new ArrayList<>();
    clauses.add(TEXTBOOK_CHILD_FILTER);
    if (sourceFile != null && !sourceFile.isBlank()) {
      clauses.add("source_file == '" + escape(sourceFile) + "'");
    }
    if (chapterNumber != null && !chapterNumber.isBlank()) {
      clauses.add("chapter_number == '" + escape(chapterNumber) + "'");
    }
    if (blockType != null && !blockType.isBlank()) {
      clauses.add("block_type == '" + escape(blockType) + "'");
    }
    return String.join(" && ", clauses);
  }

  private String escape(String value) {
    return value.replace("'", "\\'");
  }

  private record RankedDocument(Document document, int queryIndex, int rank) {
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
      if (maxVectorScore > 0) {
        metadata.put("vector_score", maxVectorScore);
      }
      if (maxKeywordScore > 0) {
        metadata.put("keyword_score", maxKeywordScore);
      }
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
      if (vectorMatched) {
        return "vector";
      }
      return "keyword";
    }

    private Integer minRank(Integer current, Integer candidate) {
      if (candidate == null) {
        return current;
      }
      if (current == null) {
        return candidate;
      }
      return Math.min(current, candidate);
    }

    private static Integer integer(Map<String, Object> metadata, String key) {
      Object value = metadata.get(key);
      if (value instanceof Number number) {
        return number.intValue();
      }
      if (value == null) {
        return null;
      }
      return Integer.parseInt(value.toString());
    }

    private static Double decimal(Map<String, Object> metadata, String key) {
      Object value = metadata.get(key);
      if (value instanceof Number number) {
        return number.doubleValue();
      }
      if (value == null) {
        return null;
      }
      return Double.parseDouble(value.toString());
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
      if (value != null) {
        metadata.put(key, value);
      }
    }
  }

  public int resolveTopK(Integer topK) {
    if (topK == null) {
      return defaultTopK;
    }
    if (topK < 1 || topK > 30) {
      throw new IllegalArgumentException("topK must be between 1 and 30");
    }
    return topK;
  }

  public double resolveSimilarityThreshold(Double similarityThreshold) {
    if (similarityThreshold == null) {
      return defaultSimilarityThreshold;
    }
    if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
      throw new IllegalArgumentException("similarityThreshold must be between 0.0 and 1.0");
    }
    return similarityThreshold;
  }
}
