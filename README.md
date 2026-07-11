# Spring AI Advanced RAG

Full-stack RAG chatbot for Vietnamese Marxist-Leninist philosophy textbooks.

## Project structure

```text
spring-ai-rag/
|- backend/   Spring Boot + Spring AI + PostgreSQL/pgvector
`- frontend/  Next.js + assistant-ui Claude-style template
```

The frontend streams responses from the backend using the AI SDK UI Message
Stream protocol.

## Requirements

- Java 25
- Node.js 22+
- PostgreSQL with the `pgvector` extension, available on port `5433`
- An OpenAI API key

## Environment

Set the required variables before starting the backend:

```powershell
$env:OPENAI_API_KEY="your-openai-api-key"
$env:POSTGRES_PASSWORD="your-postgres-password"
```

The backend configuration is in
`backend/src/main/resources/application.properties`. Use
`backend/.env.example` as the variable reference; Spring Boot reads the shell
environment directly.

## Run the backend

```powershell
cd backend
.\gradlew.bat bootRun
```

The API starts at `http://localhost:8080`.

To ingest the bundled textbooks, temporarily set
`rag.ingestion.enabled=true`, start the backend once, and set it back to
`false` after ingestion completes.

## Run the frontend

```powershell
cd frontend
npm install
npm run dev
```

The UI starts at `http://localhost:3000` and proxies chat requests to the
backend. Set `RAG_BACKEND_URL` before starting Next.js when the backend uses a
different address.

## Verification

```powershell
cd backend
.\gradlew.bat test

cd ..\frontend
npm run lint
npm run build
```
