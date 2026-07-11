package com.example.essentialrag.service.evaluation;

import java.util.List;

public record AnswerTestCase(
    String id,
    String question,
    String expectedSourceFile,
    String expectedChapter,
    List<String> mustMention,
    List<String> mustNotMention,
    Boolean mustHaveCitation,
    Boolean shouldRefuse,
    List<String> refusalMustMention) {
}
