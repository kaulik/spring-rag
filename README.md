# spring-rag

Spring Boot implementation of Hybrid RAG + LLM Reranking using Ollama and Weaviate.

## Features

- **Sentence-based chunking** — 3 sentences per chunk, ~20-token overlap
- **Hybrid retrieval** — BM25 + vector search via Weaviate (top 10)
- **LLM rerank** — Ollama scores each retrieved chunk; top 5 kept
- **Answer generation** — Ollama generates a grounded answer from reranked context
- **Two endpoints** — query stored docs OR paste context text + question in one call
- **Static HTML frontend** — served by Spring Boot at `/`

## Requirements

- Java 17+
- Maven 3.8+
- Ollama running on `host.docker.internal:11434` (model: `llama3.1`)
- Weaviate running on `host.docker.internal:8383` (gRPC on `50051`)

## Run

```bash
cd spring-rag
./mvnw spring-boot:run
```

Then open: http://localhost:8080

## API

### POST `/api/query`
```json
{ "query": "What are the key risks?" }
```

### POST `/api/query-with-context`
```json
{
  "query":       "What are the key risks?",
  "contextText": "<paste your raw text here>",
  "source":      "my-document"
}
```

**Response (both endpoints):**
```json
{
  "answer": "...",
  "sources": [
    { "text": "...", "source": "my-document", "chunkId": "my-document-chunk-0" }
  ],
  "ingestedChunks": 7
}
```

## Configuration

Edit `src/main/resources/application.yml`:

| Key | Default | Description |
|-----|---------|-------------|
| `rag.ollama.base-url` | `http://host.docker.internal:11434` | Ollama host |
| `rag.ollama.model` | `llama3.1` | Model name |
| `rag.weaviate.base-url` | `http://host.docker.internal:8383` | Weaviate REST host |
| `rag.weaviate.collection` | `pyAgentRAGDocuments` | Collection name |
| `rag.retrieval.top-k` | `10` | Docs fetched from Weaviate |
| `rag.retrieval.hybrid-alpha` | `0.5` | 0 = pure vector, 1 = pure BM25 |
| `rag.retrieval.rerank-top-k` | `5` | Docs kept after rerank |
| `rag.chunking.sentences-per-chunk` | `3` | Sentences per chunk |
| `rag.chunking.token-overlap` | `20` | Token overlap between chunks |

## Project structure

```
spring-rag/
├── pom.xml
├── src/main/
│   ├── java/com/mycompany/orchestrator/
│   │   ├── Application.java
│   │   ├── config/RagProperties.java
│   │   ├── ollama/OllamaClient.java
│   │   ├── service/
│   │   │   ├── ChunkingService.java
│   │   │   └── RagService.java
│   │   ├── weaviate/WeaviateService.java
│   │   └── web/RagController.java
│   └── resources/
│       ├── application.yml
│       └── static/index.html
└── src/test/
    └── java/com/mycompany/orchestrator/ApplicationTests.java
```
