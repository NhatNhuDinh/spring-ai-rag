package com.example.essentialrag.ingestion.chunking;

import com.example.essentialrag.ingestion.ChunkingResult;
import com.example.essentialrag.ingestion.TextbookIngestionProperties;
import com.example.essentialrag.ingestion.model.TextbookBlock;
import com.example.essentialrag.ingestion.model.TextbookProfile;
import com.example.essentialrag.ingestion.support.TextbookText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TextbookChunker {

  private static final Logger logger = LoggerFactory.getLogger(TextbookChunker.class);

  public ChunkingResult chunk(
      List<TextbookBlock> blocks,
      TextbookProfile profile,
      TextbookIngestionProperties properties) {

    List<Document> documents = new ArrayList<>();
    ChunkBuffer current = new ChunkBuffer(profile);
    int chunkIndex = 0;
    int skipped = 0;

    for (TextbookBlock block : blocks) {
      if (!current.isEmpty() && !current.canAppend(block, properties.maxChunkTokens())) {
        ChunkQuality quality = current.quality(properties.minChunkTokens());
        if (quality.passed()) {
          documents.add(current.toDocument(chunkIndex++));
        }
        else {
          skipped++;
          logger.debug("Skipped chunk before embedding: {}", quality.reason());
        }
        current.clear();
      }

      if (TextbookText.tokenCount(block.text()) > properties.maxChunkTokens()) {
        for (TextbookBlock splitBlock : splitLargeBlock(block, properties.maxChunkTokens())) {
          if (!current.isEmpty() && !current.canAppend(splitBlock, properties.maxChunkTokens())) {
            ChunkQuality quality = current.quality(properties.minChunkTokens());
            if (quality.passed()) {
              documents.add(current.toDocument(chunkIndex++));
            }
            else {
              skipped++;
              logger.debug("Skipped chunk before embedding: {}", quality.reason());
            }
            current.clear();
          }
          current.append(splitBlock);
        }
      }
      else {
        current.append(block);
      }
    }

    if (!current.isEmpty()) {
      ChunkQuality quality = current.quality(properties.minChunkTokens());
      if (quality.passed()) {
        documents.add(current.toDocument(chunkIndex));
      }
      else {
        skipped++;
        logger.debug("Skipped chunk before embedding: {}", quality.reason());
      }
    }

    return new ChunkingResult(documents, skipped);
  }

  private List<TextbookBlock> splitLargeBlock(TextbookBlock block, int maxChunkTokens) {
    List<TextbookBlock> blocks = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (String sentence : TextbookText.splitSentences(block.text())) {
      if (!current.isEmpty()
          && TextbookText.tokenCount(current + " " + sentence) > maxChunkTokens) {
        blocks.add(block.withText(current.toString().trim()));
        current.setLength(0);
      }
      if (!current.isEmpty()) {
        current.append(' ');
      }
      current.append(sentence);
    }

    if (!current.isEmpty()) {
      blocks.add(block.withText(current.toString().trim()));
    }

    return blocks;
  }

  private record ChunkQuality(boolean passed, String reason) {
  }

  private static final class ChunkBuffer {

    private final TextbookProfile profile;
    private final List<TextbookBlock> blocks = new ArrayList<>();
    private int tokenCount;

    private ChunkBuffer(TextbookProfile profile) {
      this.profile = profile;
    }

    boolean isEmpty() {
      return blocks.isEmpty();
    }

    boolean canAppend(TextbookBlock block, int maxTokens) {
      TextbookBlock first = blocks.get(0);
      boolean sameParent = first.parentId().equals(block.parentId());
      boolean sameBlockType = first.blockType().equals(block.blockType());
      return sameParent
          && sameBlockType
          && tokenCount + TextbookText.tokenCount(block.text()) <= maxTokens;
    }

    void append(TextbookBlock block) {
      blocks.add(block);
      tokenCount += TextbookText.tokenCount(block.text());
    }

    void clear() {
      blocks.clear();
      tokenCount = 0;
    }

    ChunkQuality quality(int minTokens) {
      if (blocks.isEmpty()) {
        return new ChunkQuality(false, "empty chunk");
      }

      String text = bodyText();
      int tokens = TextbookText.tokenCount(text);
      String blockType = blocks.get(0).blockType();
      int effectiveMinTokens = switch (blockType) {
        case "keyword", "review_question", "discussion_question" -> Math.min(18, minTokens);
        default -> minTokens;
      };

      if (tokens < effectiveMinTokens) {
        return new ChunkQuality(false, "too short: " + tokens + " tokens");
      }
      if (letterRatio(text) < 0.45) {
        return new ChunkQuality(false, "too little readable text");
      }
      if (blocks.get(0).pageStart() <= 0) {
        return new ChunkQuality(false, "missing page trace");
      }
      return new ChunkQuality(true, "");
    }

    Document toDocument(int chunkIndex) {
      TextbookBlock first = blocks.get(0);
      String body = bodyText();
      int pageStart = blocks.stream().mapToInt(TextbookBlock::pageStart).min().orElse(first.pageStart());
      int pageEnd = blocks.stream().mapToInt(TextbookBlock::pageEnd).max().orElse(first.pageEnd());
      String contentHash = TextbookText.sha256(body);
      String readableChunkId = "child:%s:%05d:%s".formatted(
          TextbookText.safeIdPart(profile.sourceFile()),
          chunkIndex,
          contentHash.substring(0, 12));
      String id = UUID.nameUUIDFromBytes(readableChunkId.getBytes(StandardCharsets.UTF_8)).toString();

      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("readable_chunk_id", readableChunkId);
      metadata.put("document_type", "textbook");
      metadata.put("domain", "marxism_leninism");
      metadata.put("language", "vi");
      metadata.put("source_type", "pdf_textbook");
      metadata.put("source_file", profile.sourceFile());
      metadata.put("source_location", profile.sourceLocation());
      metadata.put("book_title", profile.bookTitle());
      metadata.put("subject", profile.subject());
      metadata.put("chapter_number", first.chapterNumber());
      metadata.put("chapter_title", first.chapterTitle());
      metadata.put("section_path", first.sectionPath());
      metadata.put("section_title", first.sectionTitle());
      metadata.put("page_start", pageStart);
      metadata.put("page_end", pageEnd);
      metadata.put("block_type", first.blockType());
      metadata.put("chunk_level", "child");
      metadata.put("parent_id", first.parentId());
      metadata.put("chunk_index", chunkIndex);
      metadata.put("content_hash", contentHash);
      metadata.put("ingestion_pattern", "canonical_textbook");

      return new Document(id, embeddingText(first, pageStart, pageEnd, body), metadata);
    }

    private String bodyText() {
      StringBuilder body = new StringBuilder();
      for (TextbookBlock block : blocks) {
        if (!body.isEmpty()) {
          body.append("\n\n");
        }
        body.append(block.text());
      }
      return body.toString();
    }

    private String embeddingText(TextbookBlock first, int pageStart, int pageEnd, String body) {
      String chapter = first.chapterNumber().equals("unknown")
          ? first.chapterTitle()
          : "Chương " + first.chapterNumber() + ": " + first.chapterTitle();
      return """
          Giáo trình: %s
          Môn học: %s
          Chương: %s
          Mục: %s
          Loại nội dung: %s
          Trang: %d-%d

          Nội dung:
          %s
          """.formatted(
          profile.bookTitle(),
          profile.subject(),
          chapter.strip(),
          first.sectionPath(),
          first.blockType(),
          pageStart,
          pageEnd,
          body);
    }

    private double letterRatio(String text) {
      long nonWhitespace = text.chars().filter(ch -> !Character.isWhitespace(ch)).count();
      if (nonWhitespace == 0) {
        return 0;
      }
      long letters = text.chars().filter(Character::isLetter).count();
      return (double) letters / nonWhitespace;
    }
  }
}
