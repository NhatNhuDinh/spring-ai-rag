# Backend API

The backend exposes only the endpoints used by the frontend and health checks.

Base URL:

```text
http://localhost:8080
```

## Health check

```http
GET /api/health
```

## Frontend chat stream

```http
POST /api/chat/ui-stream
Content-Type: application/json
Accept: text/event-stream
```

The request follows the AI SDK UI message format. The backend reads the latest
non-blank user message and uses `conversationId`, falling back to `threadId`, for
conversation memory.

```json
{
  "messages": [
    {
      "role": "user",
      "content": [{"type": "text", "text": "Giá trá» tháº·ng dÆ° là gì?"}]
    }
  ],
  "threadId": "thread-1"
}
```

The response is an AI SDK UI message SSE stream and includes this protocol
header:

```text
x-vercel-ai-ui-message-stream: v1
```

Streaming timeouts are configured with:

```properties
spring.mvc.async.request-timeout=180s
rag.streaming.heartbeat-interval=15s
rag.streaming.model-timeout=120s
```
