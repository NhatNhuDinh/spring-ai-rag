# Chatbot RAG Pipeline

This document describes the current advanced RAG pipeline used by the `tool-rag` chatbot.

The pipeline has two major phases:

1. Offline ingestion: prepare textbook data and store vectors.
2. Runtime chatbot flow: transform a user question, retrieve context, package citations, and answer.

## 1. Offline Ingestion Pipeline

This phase runs before chat. It reads the textbooks and stores searchable child chunks in pgvector.

```text
PDF textbooks
-> page-based PDF reading
-> text cleaning
-> structure-aware parsing
-> parent-child chunking
-> metadata-first indexing
-> embedding
-> pgvector table: tool_rag_documents
-> search_text/search_vector and normalized search_vector backfill for PostgreSQL full-text search
```

Main classes:

- `TextbookIngestionService`
- `PdfPageReader`
- `TextbookTextCleaner`
- `TextbookStructureParser`
- `TextbookChunker`
- `RagIngestionConfig`
- `SearchVectorSchemaService`

Stored chunk metadata includes:

- `source_file`
- `book_title`
- `chapter_number`
- `chapter_title`
- `section_path`
- `block_type`
- `page_start`
- `page_end`
- `parent_id`
- `chunk_index`
- `chunk_level`

Important point: the chatbot does not embed raw PDF text directly. It embeds cleaned, structure-aware, metadata-rich child chunks.

## 2. Runtime Chatbot Pipeline

When a user calls:

```http
POST /api/chat
```

the request flows through this pipeline:

```text
User message
-> ChatController
-> PhilosophyChatService
-> AcademicQuestionDetector
-> if non-academic: ChatClient normal response
-> if academic: TextbookRetrievalService.retrieveWithParentContext(query)
-> ParentContextExpander
-> RetrievalContextPackager
-> ContextQualityEvaluator
-> controlled refusal if context is weak
-> ChatClient with pre-packaged RAG_CONTEXT
-> CitationValidator
-> optional one-shot correction prompt
-> final guarded answer
-> ChatResponse
```

## 3. Detailed Runtime Flow

### Step 1: User Calls Chat API

Endpoint:

```http
POST /api/chat
```

Example request:

```json
{
  "message": "Vì sao công nhân bị bóc lột?",
  "conversationId": "demo-user-1"
}
```

Classes:

- `ChatController`
- `PhilosophyChatService`

The `conversationId` is used by `MessageChatMemoryAdvisor` so follow-up questions can keep conversation context.

### Step 2: ChatClient Uses System Prompt And Tool

Class:

- `ChatClientConfig`

The system prompt tells the model:

- call `triet-hoc-helper` for textbook questions
- only answer academic questions from retrieved `RAG_CONTEXT`
- cite sources using labels such as `[S1]`, `[S2]`
- say the textbook context is insufficient if retrieved context is weak

Tool class:

- `RagTools`

Tool method:

```java
findPhilosophyContent(String query);
```

This tool no longer returns raw `Document` objects to the LLM. It returns a formatted context string.

## 4. Query Transformation

Class:

- `QueryTransformer`

Goal: convert everyday user wording into textbook-style retrieval queries.

Example:

```text
User query:
Vì sao công nhân bị bóc lột?

Retrieval queries:
1. Vì sao công nhân bị bóc lột?
2. nguồn gốc và bản chất của giá trị thặng dư trong lý luận của C. Mác
3. sự sản xuất giá trị thặng dư và hàng hóa sức lao động
4. nhà tư bản chiếm đoạt giá trị thặng dư của lao động làm thuê
```

The transformer also enriches intent:

```text
source_file = triet_2.pdf
chapter_number = 3
block_type = main_content
```

Current implementation is rule-based. It handles common textbook concepts such as:

- vấn đề cơ bản của triết học
- chủ nghĩa duy vật / chủ nghĩa duy tâm
- vật chất / ý thức
- biện chứng / siêu hình
- giá trị thặng dư
- hàng hóa sức lao động
- tư bản bất biến / tư bản khả biến
- tích lũy tư bản
- cấu tạo hữu cơ của tư bản

Debug endpoint:

```http
POST /api/retrieval/query-transform
```

## 5. Metadata-Aware Multi-Query Retrieval

Class:

- `TextbookRetrievalService`

The transformed retrieval flow:

```text
original user query
-> QueryTransformer generates retrieval queries
-> each retrieval query runs vector search
-> metadata filters are applied
-> results are merged and deduplicated by document id
-> highest-scoring chunks are kept
```

The metadata filters can include:

- `document_type == 'textbook'`
- `chunk_level == 'child'`
- `source_file == 'triet_1.pdf'` or `triet_2.pdf`
- `chapter_number == '1'`, `'2'`, `'3'`, etc.
- `block_type == 'main_content'`, `summary`, `keyword`, `review_question`

Debug endpoint for raw single-query retrieval:

```http
POST /api/retrieval/search
```

Debug endpoint for transformed multi-query retrieval:

```http
POST /api/retrieval/transformed
```

Each transformed result exposes metadata such as:

- `queryTransformed`
- `retrievalQuery`
- `retrievalQueryIndex`

## 6. Parent Context Expansion

## 6. Hybrid Search

Classes:

- `HybridSearchRepository`
- `TextbookRetrievalService`

Why this exists:

Vector retrieval is good at semantic similarity, but textbook questions often depend on exact terms such as `giá trị thặng dư`, `hàng hóa sức lao động`, `tư bản bất biến`, or `vấn đề cơ bản của triết học`.

Hybrid search runs two branches:

```text
transformed query
-> pgvector vector_hits branch
-> PostgreSQL full-text keyword_hits branch, raw and Vietnamese-normalized
-> RRF fusion inside each query variant
-> sum RRF scores across query variants
-> compute hybrid_score
-> keep top K seeds
```

The keyword branch runs in PostgreSQL full-text search, not by scanning a fixed number of rows in Java. It uses `search_vector`, `search_vector_normalized`, `websearch_to_tsquery('simple', ...)`, `ts_rank_cd`, and GIN indexes. The normalized branch lets queries without Vietnamese accents match textbook text with accents. The old phrase matching logic is kept only as a lightweight debug booster after database retrieval.

Default config:

```properties
rag.retrieval.hybrid.enabled=true
rag.retrieval.hybrid.keyword-candidate-limit=40
rag.retrieval.hybrid.rrf-k=60
rag.retrieval.hybrid.min-vector-score-for-filter=0.25
rag.retrieval.hybrid.min-hybrid-score-for-filter=0.0
```

Debug endpoint:

```http
POST /api/retrieval/hybrid
```

Hybrid metadata includes:

- `retrieval_mode = hybrid`
- `matched_by = vector`, `keyword`, or `vector,keyword`
- `vector_rank`
- `keyword_rank`
- `vector_score`
- `keyword_score`
- `hybrid_score`

## 7. Parent Context Expansion

Class:

- `ParentContextExpander`

Why this exists:

Vector search should find small child chunks, but the LLM often needs nearby context to answer properly.

Flow:

```text
seed child chunks from vector search
-> read parent_id and chunk_index
-> fetch nearby sibling chunks from pgvector
-> tag chunks as seed or parent_context
-> pass expanded context forward
```

Default config:

```properties
rag.retrieval.parent-context.enabled=true
rag.retrieval.parent-context.window-before=1
rag.retrieval.parent-context.window-after=1
rag.retrieval.parent-context.max-results=12
```

Debug endpoint:

```http
POST /api/retrieval/context
```

The response shows:

- `retrievalRole = seed`
- `retrievalRole = parent_context`
- `parentId`
- `chunkIndex`
- `seedRank`

## 8. Context Packaging With Citations

Class:

- `RetrievalContextPackager`

This is the final step before the tool returns data to the LLM.

It converts retrieved documents into a compact context string:

```text
RAG_CONTEXT
Câu hỏi: ...

Hướng dẫn trả lời:
- Chỉ dùng các nguồn bên dưới để trả lời câu hỏi.
- Khi nêu ý kiến/khái niệm, dẫn nguồn bằng nhãn [S1], [S2]...
- Nếu nguồn không đủ hoặc không liên quan, nói rõ là ngữ cảnh giáo trình chưa đủ.

Nguồn trích dẫn:

[S1]
- File: triet_2.pdf
- Giáo trình: Giáo trình Kinh tế chính trị Mác - Lênin
- Chương: 3 - ...
- Mục: ...
- Trang: 75-77
- Loại nội dung: main_content
- Vai trò retrieval: seed, seed_rank=1
- Retrieval query: nguồn gốc và bản chất của giá trị thặng dư trong lý luận của C. Mác
Nội dung:
...
```

Default config:

```properties
rag.retrieval.context-packaging.max-sources=12
rag.retrieval.context-packaging.max-characters=16000
```

Debug endpoint:

```http
POST /api/retrieval/packaged
```

This endpoint is the best way to see the exact context generated for the model.

## 9. Final Answer Generation

The runtime chat path applies answer guardrails before and after final answer generation.

Guardrail classes:

- `AcademicQuestionDetector`
- `ContextQualityEvaluator`
- `CitationValidator`
- `AnswerGuardrailService`

Flow:

```text
academic user question
-> retrieve parent-expanded hybrid context
-> package RAG_CONTEXT
-> check context quality
-> refuse early if context is weak
-> generate grounded answer from RAG_CONTEXT
-> validate citations
-> retry once with a correction prompt if citations are missing or invalid
-> return final answer
```

The expected answer behavior:

- answer in Vietnamese
- use textbook context only for academic claims
- cite source labels inline, for example `[S1]`
- avoid hallucinating when context is insufficient
- preserve conversation continuity through `conversationId`

Evaluation endpoints:

```text
GET  /api/evaluation/answer-tests
POST /api/evaluation/answer-tests/run
POST /api/evaluation/answer-tests/run-custom
```

Final response:

```json
{
  "conversationId": "demo-user-1",
  "answer": "..."
}
```

## Streaming Response Pipeline

The synchronous endpoint remains available:

```http
POST /api/chat
```

The ChatGPT-style endpoint is:

```http
POST /api/chat/stream
Accept: text/event-stream
```

Its runtime flow is:

```text
ChatController
-> PhilosophyChatStreamingService
-> emit start/classifying
-> run blocking JDBC retrieval on boundedElastic
-> emit sources
-> AnswerGenerationService.streamGrounded
-> ChatClient.stream().content()
-> emit delta events while accumulating the draft
-> validate complete citations
-> done when valid
-> reset + stream correction when invalid
-> save only the final user/assistant turn to ChatMemory
```

Chat clients are separated by responsibility:

```text
toolEnabledChatClient
-> optional model-driven RagTools path

groundedStreamingChatClient
-> application-controlled RAG answer, no tools

citationCorrectionChatClient
-> isolated citation repair, no tools and no conversation memory
```

The controller returns `Flux<ServerSentEvent<ChatStreamEvent>>`. JDBC repositories remain imperative; only their invocation boundary is scheduled on `Schedulers.boundedElastic()`.

## 10. Debugging Map

Use these endpoints to locate where a RAG problem happens:

```text
GET  /api/health
POST /api/retrieval/query-transform
POST /api/retrieval/search
POST /api/retrieval/transformed
POST /api/retrieval/hybrid
POST /api/retrieval/context
POST /api/retrieval/packaged
POST /api/chat
POST /api/chat/stream
GET  /api/evaluation/answer-tests
POST /api/evaluation/answer-tests/run
```

Recommended debugging order:

1. `/api/retrieval/query-transform`
   - Check whether user wording is rewritten into good textbook queries.

2. `/api/retrieval/transformed`
   - Check whether transformed retrieval finds the right chunks.

3. `/api/retrieval/hybrid`
   - Check whether keyword matches improve or hurt candidate ranking.

4. `/api/retrieval/context`
   - Check whether parent context expansion adds useful nearby chunks.

5. `/api/retrieval/packaged`
   - Check the exact context string sent back by the tool.

6. `/api/chat`
   - Check whether the LLM uses context and citations correctly.

7. `/api/evaluation/answer-tests/run`
   - Run answer-level evaluation: citation validity, must-mention checks, refusal behavior, and context quality.

## 11. Current Pipeline Status

Done:

- textbook-aware ingestion
- structure-aware parsing
- parent-child child chunking
- metadata-first indexing
- retrieval test set
- metadata-aware retrieval
- parent context expansion
- context packaging with citations
- rule-based query transformation
- transformed multi-query retrieval
- hybrid vector + keyword search
- Postman collections for retrieval, context packaging, query transformation, and chat
- SSE chat response streaming with status, source, delta, reset, refusal, done, and error events

Not done yet:

- reranking
- answer quality evaluation
- citation quality scoring
- guardrail tests for weak or unrelated retrieval

## 12. One-Line Summary

The current chatbot pipeline is:

```text
User question
-> query transformation
-> metadata-aware multi-query vector retrieval + keyword retrieval
-> hybrid scoring
-> parent context expansion
-> packaged citation context
-> LLM answer grounded in [S1], [S2], ...
```
