package com.example.essentialrag.service.streaming;

import com.example.essentialrag.api.dto.ChatSource;
import com.example.essentialrag.api.dto.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiSdkUiMessageStreamEncoderTests {

  private final AiSdkUiMessageStreamEncoder encoder = new AiSdkUiMessageStreamEncoder();

  @Test
  void encodesTextSourcesAndTerminalFramesInProtocolOrder() {
    ChatSource source = new ChatSource(
        "[S1]",
        "triet_1.pdf",
        "Giáo trình Triết học Mác - Lênin",
        "1",
        "Triết học và vấn đề cơ bản",
        "I > 1",
        25,
        26,
        "main_content",
        "seed",
        0.82);

    List<ServerSentEvent<Object>> frames = collect(encoder.encode(Flux.just(
        ChatStreamEvent.start("conversation-1", "message-1"),
        ChatStreamEvent.status("conversation-1", "message-1", "retrieving"),
        ChatStreamEvent.sources("conversation-1", "message-1", List.of(source)),
        ChatStreamEvent.delta("conversation-1", "message-1", "Xin", false),
        ChatStreamEvent.delta("conversation-1", "message-1", " chào", false),
        ChatStreamEvent.done("conversation-1", "message-1", true, false))));

    assertThat(frames).extracting(AiSdkUiMessageStreamEncoderTests::frameType)
        .containsExactly(
            "start",
            "data-status",
            "source-document",
            "data-source-metadata",
            "text-start",
            "text-delta",
            "text-delta",
            "text-end",
            "data-result",
            "finish",
            "[DONE]");

    Map<String, Object> textStart = frameData(frames.get(4));
    Map<String, Object> firstDelta = frameData(frames.get(5));
    Map<String, Object> secondDelta = frameData(frames.get(6));
    Map<String, Object> textEnd = frameData(frames.get(7));
    assertThat(firstDelta.get("id")).isEqualTo(textStart.get("id"));
    assertThat(secondDelta.get("id")).isEqualTo(textStart.get("id"));
    assertThat(textEnd.get("id")).isEqualTo(textStart.get("id"));
    assertThat(frameData(frames.get(2)))
        .containsEntry("mediaType", "application/pdf")
        .containsEntry("sourceId", "[S1]");
  }

  @Test
  void closesOldTextBlockAndStartsReplacementAfterReset() {
    List<ServerSentEvent<Object>> frames = collect(encoder.encode(Flux.just(
        ChatStreamEvent.start("conversation-1", "message-1"),
        ChatStreamEvent.delta("conversation-1", "message-1", "Draft", false),
        ChatStreamEvent.reset("conversation-1", "message-1", "citation_validation_failed"),
        ChatStreamEvent.delta("conversation-1", "message-1", "Corrected", true),
        ChatStreamEvent.done("conversation-1", "message-1", true, true))));

    assertThat(frames).extracting(AiSdkUiMessageStreamEncoderTests::frameType)
        .containsExactly(
            "start",
            "text-start",
            "text-delta",
            "text-end",
            "data-reset",
            "text-start",
            "text-delta",
            "text-end",
            "data-result",
            "finish",
            "[DONE]");

    String oldTextId = String.valueOf(frameData(frames.get(1)).get("id"));
    String replacementTextId = String.valueOf(frameData(frames.get(5)).get("id"));
    assertThat(replacementTextId).isNotEqualTo(oldTextId);
    Map<String, Object> resetData = nestedData(frames.get(4));
    assertThat(resetData)
        .containsEntry("replacedTextId", oldTextId)
        .containsEntry("replacementTextId", replacementTextId);
  }

  @Test
  void convertsHeartbeatToSseComment() {
    List<ServerSentEvent<Object>> frames = collect(encoder.encode(Flux.just(
        ChatStreamEvent.start("conversation-1", "message-1"),
        ChatStreamEvent.heartbeat("conversation-1", "message-1"),
        ChatStreamEvent.done("conversation-1", "message-1", null, false))));

    assertThat(frames.get(1).comment()).isEqualTo("keepalive");
    assertThat(frames.get(1).data()).isNull();
    assertThat(frameType(frames.get(frames.size() - 1))).isEqualTo("[DONE]");
  }

  @Test
  void terminatesProtocolAfterDomainError() {
    List<ServerSentEvent<Object>> frames = collect(encoder.encode(Flux.just(
        ChatStreamEvent.start("conversation-1", "message-1"),
        ChatStreamEvent.error("conversation-1", "message-1", "STREAM_FAILED", "Failed"))));

    assertThat(frames).extracting(AiSdkUiMessageStreamEncoderTests::frameType)
        .containsExactly("start", "error", "[DONE]");
    assertThat(frameData(frames.get(1))).containsEntry("errorText", "Failed");
  }

  private List<ServerSentEvent<Object>> collect(Flux<ServerSentEvent<Object>> frames) {
    return frames.collectList().block(Duration.ofSeconds(1));
  }

  private static String frameType(ServerSentEvent<Object> frame) {
    if (frame.data() instanceof Map<?, ?> data) {
      return String.valueOf(data.get("type"));
    }
    return String.valueOf(frame.data());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> frameData(ServerSentEvent<Object> frame) {
    return (Map<String, Object>) frame.data();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nestedData(ServerSentEvent<Object> frame) {
    return (Map<String, Object>) frameData(frame).get("data");
  }
}
