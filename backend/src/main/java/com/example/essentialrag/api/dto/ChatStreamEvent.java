package com.example.essentialrag.api.dto;

import java.util.List;

public record ChatStreamEvent(
    String type,
    String conversationId,
    String messageId,
    String phase,
    String text,
    List<ChatSource> sources,
    Boolean citationValid,
    Boolean corrected,
    String errorCode) {

  public ChatStreamEvent {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }

  public static ChatStreamEvent start(String conversationId, String messageId) {
    return event("start", conversationId, messageId, null, null, List.of(), null, false, null);
  }

  public static ChatStreamEvent status(String conversationId, String messageId, String phase) {
    return event("status", conversationId, messageId, phase, null, List.of(), null, false, null);
  }

  public static ChatStreamEvent sources(
      String conversationId,
      String messageId,
      List<ChatSource> sources) {

    return event("sources", conversationId, messageId, null, null, sources, null, false, null);
  }

  public static ChatStreamEvent delta(
      String conversationId,
      String messageId,
      String text,
      boolean corrected) {

    return event("delta", conversationId, messageId, null, text, List.of(), null, corrected, null);
  }

  public static ChatStreamEvent reset(String conversationId, String messageId, String reason) {
    return event("reset", conversationId, messageId, null, reason, List.of(), null, true, null);
  }

  public static ChatStreamEvent refusal(String conversationId, String messageId, String text) {
    return event("refusal", conversationId, messageId, null, text, List.of(), null, false, null);
  }

  public static ChatStreamEvent done(
      String conversationId,
      String messageId,
      Boolean citationValid,
      boolean corrected) {

    return event("done", conversationId, messageId, null, null, List.of(), citationValid, corrected, null);
  }

  public static ChatStreamEvent error(
      String conversationId,
      String messageId,
      String errorCode,
      String message) {

    return event("error", conversationId, messageId, null, message, List.of(), null, false, errorCode);
  }

  public static ChatStreamEvent heartbeat(String conversationId, String messageId) {
    return event("heartbeat", conversationId, messageId, null, null, List.of(), null, false, null);
  }

  private static ChatStreamEvent event(
      String type,
      String conversationId,
      String messageId,
      String phase,
      String text,
      List<ChatSource> sources,
      Boolean citationValid,
      Boolean corrected,
      String errorCode) {

    return new ChatStreamEvent(
        type,
        conversationId,
        messageId,
        phase,
        text,
        sources,
        citationValid,
        corrected,
        errorCode);
  }
}
