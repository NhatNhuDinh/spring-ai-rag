package com.example.essentialrag.service.streaming;

import com.example.essentialrag.api.dto.ChatSource;
import com.example.essentialrag.api.dto.ChatStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

@Component
public class AiSdkUiMessageStreamEncoder {

  public static final String PROTOCOL_HEADER = "x-vercel-ai-ui-message-stream";
  public static final String PROTOCOL_VERSION = "v1";

  public Flux<ServerSentEvent<Object>> encode(Flux<ChatStreamEvent> source) {
    return Flux.defer(() -> {
      EncodingState state = new EncodingState();
      Flux<ServerSentEvent<Object>> encoded = source
          .concatMap(event -> Flux.fromIterable(state.accept(event)))
          .onErrorResume(error -> Flux.fromIterable(state.fail(error)));

      return encoded.concatWith(Flux.defer(() ->
          Flux.fromIterable(state.completeUnexpectedly())));
    });
  }

  private static final class EncodingState {

    private String messageId;
    private String currentTextId;
    private int textSequence;
    private boolean started;
    private boolean textOpen;
    private boolean terminal;

    private List<ServerSentEvent<Object>> accept(ChatStreamEvent event) {
      if (terminal) {
        return List.of();
      }
      if ("heartbeat".equals(event.type())) {
        return List.of(comment("keepalive"));
      }

      List<ServerSentEvent<Object>> frames = new ArrayList<>();
      ensureStarted(frames, event.messageId());

      switch (event.type()) {
        case "start" -> {
          // ensureStarted emitted the protocol start frame.
        }
        case "status" -> addStatus(frames, event.phase());
        case "sources" -> addSources(frames, event.sources());
        case "delta" -> addTextDelta(frames, event.text());
        case "reset" -> resetText(frames, event.text());
        case "refusal" -> addTextDelta(frames, event.text());
        case "done" -> finish(frames, event);
        case "error" -> fail(frames, event.text());
        default -> throw new IllegalArgumentException(
            "Unsupported chat stream event type: " + event.type());
      }
      return List.copyOf(frames);
    }

    private void ensureStarted(
        List<ServerSentEvent<Object>> frames,
        String requestedMessageId) {

      if (started) {
        return;
      }
      messageId = requestedMessageId == null || requestedMessageId.isBlank()
          ? UUID.randomUUID().toString()
          : requestedMessageId;
      frames.add(data(fields(
          "type", "start",
          "messageId", messageId)));
      started = true;
    }

    private void addStatus(List<ServerSentEvent<Object>> frames, String phase) {
      if (phase == null || phase.isBlank()) {
        return;
      }
      frames.add(data(fields(
          "type", "data-status",
          "id", "status",
          "data", fields("phase", phase),
          "transient", true)));
    }

    private void addSources(
        List<ServerSentEvent<Object>> frames,
        List<ChatSource> sources) {

      for (int index = 0; index < sources.size(); index++) {
        ChatSource source = sources.get(index);
        String sourceId = source.label() == null || source.label().isBlank()
            ? "source-" + (index + 1)
            : source.label();

        frames.add(data(fields(
            "type", "source-document",
            "sourceId", sourceId,
            "mediaType", "application/pdf",
            "title", sourceTitle(source))));
        frames.add(data(fields(
            "type", "data-source-metadata",
            "id", "metadata-" + sourceId,
            "data", sourceMetadata(source))));
      }
    }

    private void addTextDelta(
        List<ServerSentEvent<Object>> frames,
        String text) {

      if (text == null || text.isEmpty()) {
        return;
      }
      openText(frames);
      frames.add(data(fields(
          "type", "text-delta",
          "id", currentTextId,
          "delta", text,
          "textDelta", text)));
    }

    private void openText(List<ServerSentEvent<Object>> frames) {
      if (textOpen) {
        return;
      }
      currentTextId = nextTextId();
      textOpen = true;
      frames.add(data(fields(
          "type", "text-start",
          "id", currentTextId)));
    }

    private void resetText(
        List<ServerSentEvent<Object>> frames,
        String reason) {

      String replacedTextId = currentTextId;
      closeText(frames);
      frames.add(data(fields(
          "type", "data-reset",
          "id", "reset-" + textSequence,
          "data", fields(
              "reason", reason,
              "replacedTextId", replacedTextId,
              "replacementTextId", peekNextTextId()),
          "transient", true)));
    }

    private void finish(
        List<ServerSentEvent<Object>> frames,
        ChatStreamEvent event) {

      closeText(frames);
      frames.add(data(fields(
          "type", "data-result",
          "id", "result",
          "data", fields(
              "citationValid", event.citationValid(),
              "corrected", event.corrected()))));
      frames.add(data(fields(
          "type", "finish",
          "finishReason", "stop",
          "usage", fields(
              "inputTokens", 0,
              "outputTokens", 0))));
      frames.add(data("[DONE]"));
      terminal = true;
    }

    private void fail(
        List<ServerSentEvent<Object>> frames,
        String errorText) {

      closeText(frames);
      frames.add(data(fields(
          "type", "error",
          "errorText", errorText == null || errorText.isBlank()
              ? "The assistant stream failed."
              : errorText)));
      frames.add(data("[DONE]"));
      terminal = true;
    }

    private List<ServerSentEvent<Object>> fail(Throwable error) {
      if (terminal) {
        return List.of();
      }
      List<ServerSentEvent<Object>> frames = new ArrayList<>();
      ensureStarted(frames, null);
      fail(frames, error.getMessage());
      return List.copyOf(frames);
    }

    private List<ServerSentEvent<Object>> completeUnexpectedly() {
      if (terminal) {
        return List.of();
      }
      List<ServerSentEvent<Object>> frames = new ArrayList<>();
      ensureStarted(frames, null);
      fail(frames, "The assistant stream ended before a terminal event.");
      return List.copyOf(frames);
    }

    private void closeText(List<ServerSentEvent<Object>> frames) {
      if (!textOpen) {
        return;
      }
      frames.add(data(fields(
          "type", "text-end",
          "id", currentTextId)));
      textOpen = false;
      currentTextId = null;
    }

    private String nextTextId() {
      textSequence++;
      return messageId + "-text-" + textSequence;
    }

    private String peekNextTextId() {
      return messageId + "-text-" + (textSequence + 1);
    }

    private static String sourceTitle(ChatSource source) {
      StringJoiner title = new StringJoiner(" - ");
      addTitlePart(title, source.bookTitle());
      addTitlePart(title, source.chapterTitle());

      if (source.pageStart() != null) {
        String pages = source.pageEnd() == null || source.pageEnd().equals(source.pageStart())
            ? "tr. " + source.pageStart()
            : "tr. " + source.pageStart() + "-" + source.pageEnd();
        title.add(pages);
      }
      if (title.length() == 0) {
        addTitlePart(title, source.sourceFile());
      }
      return title.length() == 0 ? "Textbook source" : title.toString();
    }

    private static void addTitlePart(StringJoiner title, String value) {
      if (value != null && !value.isBlank()) {
        title.add(value);
      }
    }

    private static Map<String, Object> sourceMetadata(ChatSource source) {
      return fields(
          "label", source.label(),
          "sourceFile", source.sourceFile(),
          "bookTitle", source.bookTitle(),
          "chapterNumber", source.chapterNumber(),
          "chapterTitle", source.chapterTitle(),
          "sectionPath", source.sectionPath(),
          "pageStart", source.pageStart(),
          "pageEnd", source.pageEnd(),
          "blockType", source.blockType(),
          "retrievalRole", source.retrievalRole(),
          "score", source.score());
    }
  }

  private static ServerSentEvent<Object> data(Object value) {
    return ServerSentEvent.<Object>builder(value).build();
  }

  private static ServerSentEvent<Object> comment(String value) {
    return ServerSentEvent.<Object>builder().comment(value).build();
  }

  private static Map<String, Object> fields(Object... keyValues) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      fields.put((String) keyValues[index], keyValues[index + 1]);
    }
    return fields;
  }
}
