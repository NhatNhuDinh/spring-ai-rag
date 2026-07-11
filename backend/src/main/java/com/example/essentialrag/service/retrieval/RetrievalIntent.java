package com.example.essentialrag.service.retrieval;

public record RetrievalIntent(
    String sourceFile,
    String chapterNumber,
    String blockType) {

  public boolean hasSourceFile() {
    return sourceFile != null && !sourceFile.isBlank();
  }

  public boolean hasChapterNumber() {
    return chapterNumber != null && !chapterNumber.isBlank();
  }

  public boolean hasBlockType() {
    return blockType != null && !blockType.isBlank();
  }

  public boolean hasAnyFilter() {
    return hasSourceFile() || hasChapterNumber() || hasBlockType();
  }
}
