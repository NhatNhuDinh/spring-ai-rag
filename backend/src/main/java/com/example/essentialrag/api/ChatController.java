package com.example.essentialrag.api;

import com.example.essentialrag.api.dto.ChatRequest;
import com.example.essentialrag.api.dto.ChatResponse;
import com.example.essentialrag.api.dto.ChatStreamEvent;
import com.example.essentialrag.api.dto.AiSdkChatRequest;
import com.example.essentialrag.service.PhilosophyChatService;
import com.example.essentialrag.service.streaming.AiSdkUiMessageStreamEncoder;
import com.example.essentialrag.service.streaming.PhilosophyChatStreamingService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(exposedHeaders = AiSdkUiMessageStreamEncoder.PROTOCOL_HEADER)
public class ChatController {

  private final PhilosophyChatService chatService;
  private final PhilosophyChatStreamingService streamingService;
  private final AiSdkUiMessageStreamEncoder aiSdkEncoder;

  public ChatController(
      PhilosophyChatService chatService,
      PhilosophyChatStreamingService streamingService,
      AiSdkUiMessageStreamEncoder aiSdkEncoder) {

    this.chatService = chatService;
    this.streamingService = streamingService;
    this.aiSdkEncoder = aiSdkEncoder;
  }

  @PostMapping
  public ChatResponse chat(@RequestBody ChatRequest request) {
    PhilosophyChatService.ChatAnswer answer = chatService.chat(
        request.message(),
        request.conversationId());

    return new ChatResponse(answer.conversationId(), answer.answer());
  }

  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> stream(
      @RequestBody ChatRequest request) {

    Flux<ServerSentEvent<ChatStreamEvent>> events = streamingService
        .stream(request.message(), request.conversationId())
        .index()
        .map(indexed -> {
          ChatStreamEvent event = indexed.getT2();
          return ServerSentEvent.<ChatStreamEvent>builder(event)
              .id(event.messageId() + ":" + indexed.getT1())
              .event(event.type())
              .build();
        });

    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .header("X-Accel-Buffering", "no")
        .body(events);
  }

  @PostMapping(value = "/ui-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<Flux<ServerSentEvent<Object>>> uiStream(
      @RequestBody AiSdkChatRequest request) {

    String message;
    try {
      message = request.latestUserText();
    }
    catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }

    Flux<ServerSentEvent<Object>> events = aiSdkEncoder.encode(
        streamingService.stream(message, request.effectiveConversationId()));

    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
        .header(AiSdkUiMessageStreamEncoder.PROTOCOL_HEADER,
            AiSdkUiMessageStreamEncoder.PROTOCOL_VERSION)
        .header("X-Accel-Buffering", "no")
        .body(events);
  }
}
