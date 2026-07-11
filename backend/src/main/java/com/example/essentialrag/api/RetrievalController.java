package com.example.essentialrag.api;

import com.example.essentialrag.api.dto.PackagedRetrievalContextResponse;
import com.example.essentialrag.api.dto.QueryTransformResponse;
import com.example.essentialrag.api.dto.RetrievalSearchRequest;
import com.example.essentialrag.api.dto.RetrievalSearchResponse;
import com.example.essentialrag.api.dto.RetrievedChunkResponse;
import com.example.essentialrag.service.TextbookRetrievalService;
import com.example.essentialrag.service.retrieval.QueryTransformation;
import com.example.essentialrag.service.retrieval.RetrievalContextPackager;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retrieval")
@CrossOrigin
public class RetrievalController {

  private final TextbookRetrievalService retrievalService;
  private final RetrievalContextPackager contextPackager;

  public RetrievalController(
      TextbookRetrievalService retrievalService,
      RetrievalContextPackager contextPackager) {

    this.retrievalService = retrievalService;
    this.contextPackager = contextPackager;
  }

  @PostMapping("/search")
  public RetrievalSearchResponse search(@RequestBody RetrievalSearchRequest request) {
    return search(request, false);
  }

  @PostMapping("/query-transform")
  public QueryTransformResponse transformQuery(@RequestBody RetrievalSearchRequest request) {
    QueryTransformation transformation = retrievalService.transformQuery(request.query());
    return new QueryTransformResponse(
        transformation.originalQuery(),
        transformation.retrievalQueries(),
        transformation.intent().sourceFile(),
        transformation.intent().chapterNumber(),
        transformation.intent().blockType());
  }

  @PostMapping("/transformed")
  public RetrievalSearchResponse transformedSearch(@RequestBody RetrievalSearchRequest request) {
    int topK = retrievalService.resolveTopK(request.topK());
    double threshold = retrievalService.resolveSimilarityThreshold(request.similarityThreshold());
    List<Document> documents = retrievalService.searchTransformed(request.query(), topK, threshold);

    List<RetrievedChunkResponse> results = new ArrayList<>();
    for (int i = 0; i < documents.size(); i++) {
      results.add(toResponse(i + 1, documents.get(i)));
    }

    return new RetrievalSearchResponse(
        request.query(),
        topK,
        threshold,
        results.size(),
        results);
  }

  @PostMapping("/hybrid")
  public RetrievalSearchResponse hybridSearch(@RequestBody RetrievalSearchRequest request) {
    int topK = retrievalService.resolveTopK(request.topK());
    double threshold = retrievalService.resolveSimilarityThreshold(request.similarityThreshold());
    List<Document> documents = retrievalService.searchHybrid(request.query(), topK, threshold);

    List<RetrievedChunkResponse> results = new ArrayList<>();
    for (int i = 0; i < documents.size(); i++) {
      results.add(toResponse(i + 1, documents.get(i)));
    }

    return new RetrievalSearchResponse(
        request.query(),
        topK,
        threshold,
        results.size(),
        results);
  }

  @PostMapping("/context")
  public RetrievalSearchResponse searchWithParentContext(@RequestBody RetrievalSearchRequest request) {
    return search(request, true);
  }

  @PostMapping("/packaged")
  public PackagedRetrievalContextResponse packagedContext(@RequestBody RetrievalSearchRequest request) {
    int topK = retrievalService.resolveTopK(request.topK());
    double threshold = retrievalService.resolveSimilarityThreshold(request.similarityThreshold());
    List<Document> documents = retrievalService.retrieveWithParentContext(request.query(), topK, threshold);
    String context = contextPackager.packageContext(request.query(), documents);

    return new PackagedRetrievalContextResponse(
        request.query(),
        topK,
        threshold,
        documents.size(),
        context.length(),
        contextPackager.maxCharacters(),
        context);
  }

  private RetrievalSearchResponse search(RetrievalSearchRequest request, boolean expandParentContext) {
    int topK = retrievalService.resolveTopK(request.topK());
    double threshold = retrievalService.resolveSimilarityThreshold(request.similarityThreshold());
    List<Document> documents = expandParentContext
        ? retrievalService.retrieveWithParentContext(request.query(), topK, threshold)
        : retrievalService.search(request.query(), topK, threshold);

    List<RetrievedChunkResponse> results = new ArrayList<>();
    for (int i = 0; i < documents.size(); i++) {
      results.add(toResponse(i + 1, documents.get(i)));
    }

    return new RetrievalSearchResponse(
        request.query(),
        topK,
        threshold,
        results.size(),
        results);
  }

  private RetrievedChunkResponse toResponse(int rank, Document document) {
    Map<String, Object> metadata = document.getMetadata();
    return new RetrievedChunkResponse(
        rank,
        document.getId(),
        document.getScore(),
        string(metadata, "source_file"),
        string(metadata, "book_title"),
        string(metadata, "chapter_number"),
        string(metadata, "chapter_title"),
        string(metadata, "section_path"),
        string(metadata, "block_type"),
        string(metadata, "parent_id"),
        integer(metadata, "chunk_index"),
        string(metadata, "retrieval_role"),
        integer(metadata, "seed_rank"),
        bool(metadata, "query_transformed"),
        string(metadata, "retrieval_query"),
        integer(metadata, "retrieval_query_index"),
        string(metadata, "retrieval_mode"),
        string(metadata, "matched_by"),
        doubleValue(metadata, "hybrid_score"),
        integer(metadata, "vector_rank"),
        integer(metadata, "keyword_rank"),
        doubleValue(metadata, "vector_score"),
        doubleValue(metadata, "keyword_score"),
        integer(metadata, "page_start"),
        integer(metadata, "page_end"),
        preview(document.getText()),
        metadata);
  }

  private String preview(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    String normalized = text.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 420 ? normalized : normalized.substring(0, 420) + "...";
  }

  private String string(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    return value == null ? null : value.toString();
  }

  private Integer integer(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return null;
    }
    return Integer.parseInt(value.toString());
  }

  private Boolean bool(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value == null) {
      return null;
    }
    return Boolean.parseBoolean(value.toString());
  }

  private Double doubleValue(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value == null) {
      return null;
    }
    return Double.parseDouble(value.toString());
  }
}
