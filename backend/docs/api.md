# `tool-rag` Web API

For the full chatbot RAG pipeline, see `docs/chatbot-rag-pipeline.md`.

Run the app:

```powershell
cd D:\FPT_Curriculum\Semester_9\research\spring-ai-recipes\tool-rag
$env:JAVA_HOME='C:\Users\nhudi\.jdks\temurin-25.0.3'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:OPENAI_API_KEY='your-key'
.\gradlew.bat bootRun
```

Prerequisite: Postgres/pgvector must be running at the configured datasource:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5433/postgres
```

If Postgres is not listening on `localhost:5433`, the app fails at startup because `PgVectorStore` initializes its schema during bean creation.

Default base URL:

```text
http://localhost:8080
```

Health check:

```http
GET /api/health
```

If the vector store has already been ingested and you only want to run the chatbot API:

```powershell
.\gradlew.bat bootRun --args='--rag.ingestion.enabled=false'
```

## Chat

Endpoint:

```http
POST /api/chat
Content-Type: application/json
```

Request:

```json
{
  "message": "Giá trị thặng dư là gì?",
  "conversationId": "demo-user-1"
}
```

`conversationId` is optional. If omitted, the server generates one and returns it. Reuse the same `conversationId` to keep chat memory for a user/session.

Response:

```json
{
  "conversationId": "demo-user-1",
  "answer": "..."
}
```

## Streaming Chat

Use this endpoint for a ChatGPT-style incremental response:

```http
POST /api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

Request:

```json
{
  "message": "Giá trị thặng dư là gì?",
  "conversationId": "demo-user-1"
}
```

The response remains open and emits named SSE events. The normal academic flow is:

```text
start
-> status: classifying
-> status: retrieving
-> sources
-> status: generating
-> delta (repeated)
-> status: validating
-> done
```

Example events:

```text
event:start
data:{"type":"start","conversationId":"demo-user-1","messageId":"..."}

event:status
data:{"type":"status","phase":"retrieving",...}

event:sources
data:{"type":"sources","sources":[{"label":"[S1]","sourceFile":"triet_2.pdf",...}],...}

event:delta
data:{"type":"delta","text":"Giá trị thặng dư",...}

event:done
data:{"type":"done","citationValid":true,"corrected":false,...}
```

If the first draft has missing or invented citations, the server emits:

```text
reset
-> status: correcting
-> corrected delta events
-> status: validating
-> done
```

The client must clear the displayed draft when receiving `reset`. It should append each `delta.text` to the current assistant message. A `refusal` event contains the complete controlled-refusal text. An `error` event is terminal for that request.

Browser clients should use a streaming `fetch()` request because the endpoint accepts a POST body. Use `AbortController` to implement Stop generating.

Command-line test:

```powershell
curl.exe -N `
  -X POST http://localhost:8080/api/chat/stream `
  -H "Content-Type: application/json" `
  -H "Accept: text/event-stream" `
  -d '{"message":"Giá trị thặng dư là gì?","conversationId":"demo-user-1"}'
```

Streaming configuration:

```properties
spring.mvc.async.request-timeout=180s
rag.streaming.heartbeat-interval=15s
rag.streaming.model-timeout=120s
```

The endpoint also returns `Cache-Control: no-cache` and `X-Accel-Buffering: no` so reverse proxies do not buffer the generated text.

PowerShell:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/chat `
  -ContentType 'application/json' `
  -Body '{"message":"Giá trị thặng dư là gì?","conversationId":"demo-user-1"}'
```

## Retrieval Debug Search

Endpoint:

```http
POST /api/retrieval/search
Content-Type: application/json
```

This endpoint returns the raw child chunks found by vector search. Use it for retrieval diagnostics.

To inspect query transformation before retrieval, use:

```http
POST /api/retrieval/query-transform
Content-Type: application/json
```

To inspect multi-query transformed retrieval before parent context expansion, use:

```http
POST /api/retrieval/transformed
Content-Type: application/json
```

To inspect hybrid vector + keyword retrieval before parent context expansion, use:

```http
POST /api/retrieval/hybrid
Content-Type: application/json
```

For the chatbot-style retrieval pipeline with parent context expansion, use:

```http
POST /api/retrieval/context
Content-Type: application/json
```

`/api/retrieval/context` first finds matching child chunks, then adds nearby chunks with the same `parent_id`. The chatbot tool uses this expanded path.

To inspect the exact formatted context that is sent back by the chatbot tool, use:

```http
POST /api/retrieval/packaged
Content-Type: application/json
```

`/api/retrieval/packaged` returns a single `context` string with citation labels such as `[S1]`, `[S2]`.

The chatbot tool uses the transformed pipeline:

```text
user query
-> query transformation
-> pgvector vector retrieval
-> PostgreSQL full-text keyword retrieval
-> RRF hybrid fusion and scoring
-> parent context expansion
-> packaged context with citations
```

Request:

```json
{
  "query": "Tư bản bất biến và tư bản khả biến khác nhau như thế nào?",
  "topK": 8,
  "similarityThreshold": 0.0
}
```

`topK` and `similarityThreshold` are optional. If omitted, the app uses:

```properties
rag.retrieval.top-k=8
rag.retrieval.similarity-threshold=0.0
```

Response includes retrieved chunks, score, page trace, and metadata:

```json
{
  "query": "Tư bản bất biến và tư bản khả biến khác nhau như thế nào?",
  "topK": 8,
  "similarityThreshold": 0.0,
  "resultCount": 8,
  "results": [
    {
      "rank": 1,
      "id": "uuid",
      "score": 0.78,
      "sourceFile": "triet_2.pdf",
      "bookTitle": "Giáo trình Kinh tế chính trị Mác - Lênin",
      "chapterNumber": "3",
      "chapterTitle": "GIÁ TRỊ THẶNG DƯ",
      "sectionPath": "D) TƯ BẢN BẤT BIẾN VÀ TƯ BẢN KHẢ BIẾN",
      "blockType": "main_content",
      "parentId": "parent:triet-2-pdf:3:...",
      "chunkIndex": 91,
      "retrievalRole": "seed",
      "seedRank": 1,
      "retrievalMode": "hybrid",
      "matchedBy": "vector,keyword",
      "hybridScore": 0.0162,
      "vectorRank": 1,
      "keywordRank": 3,
      "vectorScore": 0.78,
      "keywordScore": 0.084,
      "pageStart": 82,
      "pageEnd": 83,
      "preview": "..."
    }
  ]
}
```

PowerShell:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/retrieval/search `
  -ContentType 'application/json' `
  -Body '{"query":"Tư bản bất biến và tư bản khả biến khác nhau như thế nào?","topK":8,"similarityThreshold":0.0}'
```

PowerShell with parent context:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/retrieval/context `
  -ContentType 'application/json' `
  -Body '{"query":"Tư bản bất biến và tư bản khả biến khác nhau như thế nào?","topK":8,"similarityThreshold":0.0}'
```

PowerShell with query transformation:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/retrieval/query-transform `
  -ContentType 'application/json' `
  -Body '{"query":"Vì sao công nhân bị bóc lột?","topK":8,"similarityThreshold":0.0}'
```

PowerShell with transformed retrieval:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/retrieval/transformed `
  -ContentType 'application/json' `
  -Body '{"query":"Vì sao công nhân bị bóc lột?","topK":8,"similarityThreshold":0.0}'
```

PowerShell with hybrid retrieval:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/retrieval/hybrid `
  -ContentType 'application/json' `
  -Body '{"query":"Vì sao công nhân bị bóc lột?","topK":8,"similarityThreshold":0.0}'
```

PowerShell with packaged context and citations:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/retrieval/packaged `
  -ContentType 'application/json' `
  -Body '{"query":"Tư bản bất biến và tư bản khả biến khác nhau như thế nào?","topK":8,"similarityThreshold":0.0}'
```

Use this endpoint while building the chatbot UI. It helps verify whether a bad answer comes from bad retrieval or bad generation.

## Answer Evaluation And Guardrails

List default answer-level test cases:

```http
GET /api/evaluation/answer-tests
```

Run the default answer test set from `src/main/resources/evaluation/answer-test-cases.json`:

```http
POST /api/evaluation/answer-tests/run
Content-Type: application/json
```

Run custom answer tests:

```http
POST /api/evaluation/answer-tests/run-custom
Content-Type: application/json
```

Request body:

```json
[
  {
    "id": "custom_surplus_value",
    "question": "Giá trị thặng dư là gì?",
    "expectedSourceFile": "triet_2.pdf",
    "expectedChapter": "3",
    "mustMention": ["giá trị thặng dư"],
    "mustNotMention": [],
    "mustHaveCitation": true,
    "shouldRefuse": false,
    "refusalMustMention": []
  }
]
```

Evaluation checks include:

- context quality
- expected source/chapter hit
- required citation exists and is not invented
- required terms appear in the final answer
- forbidden terms do not appear
- refusal answer contains the expected refusal wording

## Postman Collection

Import this file into Postman to run all retrieval test cases:

```text
docs/postman/tool-rag-retrieval-tests.postman_collection.json
```

Import this file into Postman to test the chat API and conversation memory:

```text
docs/postman/tool-rag-chat-tests.postman_collection.json
```

Import this file into Postman to test context packaging, citation labels, and chat citation behavior:

```text
docs/postman/tool-rag-context-packaging-tests.postman_collection.json
```

Import this file into Postman to test query transformation and transformed multi-query retrieval:

```text
docs/postman/tool-rag-query-transformation-tests.postman_collection.json
```

Import this file into Postman to test hybrid vector + keyword search:

```text
docs/postman/tool-rag-hybrid-search-tests.postman_collection.json
```

Import this file into Postman to run answer evaluation and guardrail tests:

```text
docs/postman/tool-rag-answer-evaluation-tests.postman_collection.json
```

Import this file to inspect the streaming SSE event sequence:

```text
docs/postman/tool-rag-streaming-chat.postman_collection.json
```

Collection variables:

```text
baseUrl = http://localhost:8080
topK = 8
similarityThreshold = 0.0
```
