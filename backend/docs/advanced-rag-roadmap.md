# Advanced RAG Roadmap For `tool-rag`

This module uses two Vietnamese Marxism-Leninism textbooks as the knowledge base:

- `triet_1.pdf`: Giáo trình Triết học Mác - Lênin
- `triet_2.pdf`: Giáo trình Kinh tế chính trị Mác - Lênin

For the current end-to-end chatbot flow, see `docs/chatbot-rag-pipeline.md`.

The project should evolve through these five steps. Do not jump to later steps until the earlier step has observable output and a small test set.

## 1. Retrieval Test Set And Diagnostics

Goal: verify whether retrieval finds the right textbook chunks before tuning prompts or models.

Create a set of test queries with expected retrieval targets:

- expected source file
- expected book
- expected chapter/section when known
- expected page range when known
- expected block type, for example `main_content`, `summary`, `keyword`, `review_question`
- expected evidence terms that should appear in retrieved chunks

This step answers: "When I ask this question, does vector search retrieve the right place in the textbook?"

Recommended metrics:

- `hit@k`: at least one of the top K chunks comes from the expected source/page/section.
- `mrr`: the first correct chunk appears near the top.
- `metadata_hit`: retrieved chunk has expected source/chapter/block type.
- `evidence_hit`: retrieved text contains one or more expected terms.

This is intentionally retrieval-only. It does not judge whether the LLM answer is good yet.

## 2. Retrieval Pipeline Optimization

Goal: improve the quality and controllability of retrieved context.

Implement a dedicated retrieval service instead of calling `vectorStore.similaritySearch(...)` directly from the tool.

First implementation in this project:

- classify simple textbook intent from the query: source file, chapter number, and requested block type
- apply metadata-aware filters before vector search
- keep fallback filters so overly strict metadata does not return empty results
- expose `/api/retrieval/search` for debugging the retrieved chunks before involving the chat model
- expand chatbot context with nearby child chunks from the same `parent_id`
- package chatbot context with source labels such as `[S1]`, page trace, chapter, section, and block type

Recommended pipeline:

```text
query
-> optional query classification
-> metadata-aware filtering
-> child chunk vector search
-> parent context expansion
-> context packaging with citations
```

For textbooks, parent-child retrieval is the most important upgrade:

```text
search child chunks
-> group by parent_id
-> expand with neighboring chunks or parent section
-> pass compact context to the model
```

## 3. Query Transformation

Goal: make user queries easier for retrieval to match.

First implementation in this project:

- rewrite common everyday questions into textbook-style retrieval queries
- generate up to 4 retrieval queries per user query
- enrich source/chapter intent for known textbook terms
- run transformed multi-query retrieval and deduplicate chunks by document id
- expose `/api/retrieval/query-transform` and `/api/retrieval/transformed` for debugging

Useful patterns:

- query rewrite: rewrite vague user query into textbook-style terms
- multi-query: generate 2-4 alternative search queries
- step-back query: retrieve general theory plus the specific query
- domain routing: decide whether the query belongs to Triết học or Kinh tế chính trị

Example:

```text
User: "giá trị thặng dư sinh ra từ đâu?"
Retrieval query: "nguồn gốc và bản chất của giá trị thặng dư trong lý luận của C. Mác"
```

## 4. Hybrid Search And Reranking

Goal: improve precision after the basic retriever works.

First hybrid implementation in this project:

- run transformed vector retrieval
- run keyword retrieval in PostgreSQL full-text search using `search_vector` and `search_vector_normalized`
- fuse vector and keyword candidates with Reciprocal Rank Fusion (RRF)
- compute final `hybrid_score` from vector and keyword ranks, summed across query variants
- expose `/api/retrieval/hybrid` for debugging
- route chatbot context through hybrid seeds before parent context expansion

Hybrid search combines:

- dense vector search for semantic similarity
- keyword/BM25 search for exact terms, names, formulas, years, and section titles

Reranking then reorders retrieved candidates by query-document relevance.

Do this after retrieval diagnostics exist; otherwise it is hard to know whether reranking helped.

## 5. Answer Evaluation And Guardrails

Goal: evaluate and control final answers, not just retrieved chunks.

Evaluate:

- faithfulness: answer is grounded in retrieved context
- answer relevancy: answer addresses the user question
- citation quality: answer cites the correct source/page
- refusal behavior: answer avoids hallucinating when retrieval is weak

Recommended answer rule for this module:

```text
If retrieved context is weak or unrelated, say that the textbook context is insufficient instead of inventing an answer.
```

## Current Status

Done:

- textbook-aware ingestion
- page-based PDF parsing
- cleaning
- structure-aware parsing
- parent-child child chunking
- metadata-first indexing
- retrieval test set and debug API
- metadata-aware retrieval filters
- parent context expansion for the chatbot tool and `/api/retrieval/context`
- context packaging with citations for the chatbot tool and `/api/retrieval/packaged`
- rule-based query transformation and transformed multi-query retrieval
- hybrid vector + keyword search
- answer guardrails: context quality, citation validation, controlled refusal
- answer evaluation API and default answer test set
- application-controlled SSE response streaming with citation correction and clean final-turn memory

Next:

- tune `topK`, threshold, metadata filters, and parent expansion based on test results
- run `/api/evaluation/answer-tests/run` after ingestion and tune failures
- add reranking after hybrid candidate generation
