package com.example.essentialrag.api;

import com.example.essentialrag.api.dto.AiSdkChatRequest;
import com.example.essentialrag.api.dto.ChatRequest;
import com.example.essentialrag.api.dto.ChatStreamEvent;
import com.example.essentialrag.service.PhilosophyChatService;
import com.example.essentialrag.service.streaming.AiSdkUiMessageStreamEncoder;
import com.example.essentialrag.service.streaming.PhilosophyChatStreamingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerStreamingTests {

  @Test
  void mapsDomainEventsToNamedServerSentEvents() {
    PhilosophyChatService chatService = mock(PhilosophyChatService.class);
    PhilosophyChatStreamingService streamingService = mock(PhilosophyChatStreamingService.class);
    ChatStreamEvent start = ChatStreamEvent.start("conversation-1", "message-1");
    when(streamingService.stream("hello", "conversation-1")).thenReturn(Flux.just(start));
    ChatController controller = new ChatController(
        chatService,
        streamingService,
        new AiSdkUiMessageStreamEncoder());

    ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> response = controller.stream(
        new ChatRequest("hello", "conversation-1"));
    List<ServerSentEvent<ChatStreamEvent>> events = response.getBody()
        .collectList()
        .block(Duration.ofSeconds(1));

    assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    assertThat(events).singleElement().satisfies(event -> {
      assertThat(event.event()).isEqualTo("start");
      assertThat(event.id()).isEqualTo("message-1:0");
      assertThat(event.data()).isEqualTo(start);
    });
  }

  @Test
  void exposesAiSdkUiMessageStreamProtocol() {
    PhilosophyChatService chatService = mock(PhilosophyChatService.class);
    PhilosophyChatStreamingService streamingService = mock(PhilosophyChatStreamingService.class);
    when(streamingService.stream("hello", "thread-1")).thenReturn(Flux.just(
        ChatStreamEvent.start("thread-1", "message-1"),
        ChatStreamEvent.delta("thread-1", "message-1", "Hello", false),
        ChatStreamEvent.done("thread-1", "message-1", null, false)));
    ChatController controller = new ChatController(
        chatService,
        streamingService,
        new AiSdkUiMessageStreamEncoder());
    AiSdkChatRequest request = new AiSdkChatRequest(
        List.of(new AiSdkChatRequest.AiSdkMessage(
            "user",
            List.of(Map.of("type", "text", "text", "hello")))),
        "thread-1",
        null);

    ResponseEntity<Flux<ServerSentEvent<Object>>> response = controller.uiStream(request);
    List<ServerSentEvent<Object>> events = response.getBody()
        .collectList()
        .block(Duration.ofSeconds(1));

    assertThat(response.getHeaders().getFirst(AiSdkUiMessageStreamEncoder.PROTOCOL_HEADER))
        .isEqualTo(AiSdkUiMessageStreamEncoder.PROTOCOL_VERSION);
    assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    assertThat(events).extracting(ChatControllerStreamingTests::frameType)
        .containsExactly(
            "start",
            "text-start",
            "text-delta",
            "text-end",
            "data-result",
            "finish",
            "[DONE]");
    verify(streamingService).stream("hello", "thread-1");
  }

  @Test
  void serializesUiMessageStreamAsRawSseFrames() throws Exception {
    PhilosophyChatService chatService = mock(PhilosophyChatService.class);
    PhilosophyChatStreamingService streamingService = mock(PhilosophyChatStreamingService.class);
    when(streamingService.stream("hello", "thread-1")).thenReturn(Flux.just(
        ChatStreamEvent.start("thread-1", "message-1"),
        ChatStreamEvent.delta("thread-1", "message-1", "Hello", false),
        ChatStreamEvent.done("thread-1", "message-1", null, false)));
    ChatController controller = new ChatController(
        chatService,
        streamingService,
        new AiSdkUiMessageStreamEncoder());
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    MvcResult pending = mockMvc.perform(MockMvcRequestBuilders.post("/api/chat/ui-stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("""
                {
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "hello"}],
                      "providerOptions": {"ignored": true}
                    },
                    {"role": "assistant", "content": []}
                  ],
                  "tools": {},
                  "system": null,
                  "threadId": "thread-1",
                  "runConfig": {"ignored": true}
                }
                """))
        .andExpect(status().isOk())
        .andReturn();

    MvcResult completed = mockMvc.perform(asyncDispatch(pending))
        .andExpect(status().isOk())
        .andExpect(header().string(
            AiSdkUiMessageStreamEncoder.PROTOCOL_HEADER,
            AiSdkUiMessageStreamEncoder.PROTOCOL_VERSION))
        .andReturn();
    String body = completed.getResponse().getContentAsString();

    assertThat(body)
        .contains("data:{\"type\":\"start\",\"messageId\":\"message-1\"}")
        .contains("\"type\":\"text-delta\"")
        .contains("\"delta\":\"Hello\"")
        .contains("\"textDelta\":\"Hello\"")
        .contains("data:[DONE]")
        .doesNotContain("event:delta");
  }

  private static String frameType(ServerSentEvent<Object> event) {
    if (event.data() instanceof Map<?, ?> data) {
      return String.valueOf(data.get("type"));
    }
    return String.valueOf(event.data());
  }
}
