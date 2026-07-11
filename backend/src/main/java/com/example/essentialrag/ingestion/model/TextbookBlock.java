package com.example.essentialrag.ingestion.model;

public record TextbookBlock(
    String text,
    String blockType,
    int pageStart,
    int pageEnd,
    String chapterNumber,
    String chapterTitle,
    String sectionPath,
    String sectionTitle,
    String parentId) {

  public TextbookBlock withText(String newText) {
    return new TextbookBlock(
        newText,
        blockType,
        pageStart,
        pageEnd,
        chapterNumber,
        chapterTitle,
        sectionPath,
        sectionTitle,
        parentId);
  }
}
