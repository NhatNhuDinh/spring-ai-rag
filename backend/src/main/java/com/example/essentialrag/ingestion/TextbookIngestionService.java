package com.example.essentialrag.ingestion;

import com.example.essentialrag.ingestion.chunking.TextbookChunker;
import com.example.essentialrag.ingestion.cleaning.TextbookTextCleaner;
import com.example.essentialrag.ingestion.model.PageLines;
import com.example.essentialrag.ingestion.model.PageText;
import com.example.essentialrag.ingestion.model.TextbookBlock;
import com.example.essentialrag.ingestion.model.TextbookProfile;
import com.example.essentialrag.ingestion.parsing.TextbookStructureParser;
import com.example.essentialrag.ingestion.profile.TextbookProfileResolver;
import com.example.essentialrag.ingestion.reader.PdfPageReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class TextbookIngestionService {

  private final TextbookProfileResolver profileResolver;
  private final PdfPageReader pageReader;
  private final TextbookTextCleaner textCleaner;
  private final TextbookStructureParser structureParser;
  private final TextbookChunker chunker;

  public TextbookIngestionService(
      TextbookProfileResolver profileResolver,
      PdfPageReader pageReader,
      TextbookTextCleaner textCleaner,
      TextbookStructureParser structureParser,
      TextbookChunker chunker) {

    this.profileResolver = profileResolver;
    this.pageReader = pageReader;
    this.textCleaner = textCleaner;
    this.structureParser = structureParser;
    this.chunker = chunker;
  }

  public TextbookIngestionResult ingest(
      String location,
      Resource resource,
      TextbookIngestionProperties properties) throws IOException {

    TextbookProfile profile = profileResolver.resolve(location, resource);
    if (!resource.exists()) {
      throw new IllegalArgumentException("Textbook resource does not exist: " + profile.sourceLocation());
    }

    List<PageText> rawPages = pageReader.read(resource);
    List<PageLines> cleanPages = textCleaner.clean(rawPages);
    List<TextbookBlock> blocks = structureParser.parse(cleanPages, profile);
    ChunkingResult chunkingResult = chunker.chunk(blocks, profile, properties);

    return new TextbookIngestionResult(
        profile,
        rawPages.size(),
        blocks.size(),
        chunkingResult.documents(),
        chunkingResult.skippedChunks());
  }
}
