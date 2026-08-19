# DocuSense — Architecture

## 1. Folder structure

```
Document Intelligence System/
├── docker-compose.yml            # Weaviate (vector DB), single command startup
├── ARCHITECTURE.md               # this file
├── docusense_backend/            # Spring Boot (Java 21) REST API
│   └── src/main/java/com/calfuslearning/docusense_backend/
│       ├── config/
│       │   ├── RagBeansConfig.java   # embedding model, chat model, 2 Weaviate stores
│       │   └── WebConfig.java        # CORS for the frontend dev origin
│       ├── controller/
│       │   ├── DocumentController.java  # upload / list / stream PDF
│       │   └── QueryController.java     # ask a question
│       ├── service/
│       │   ├── PdfStorageService.java      # save/load raw PDFs on disk
│       │   ├── DocumentCatalogService.java # in-memory + sidecar-JSON catalog of ingested docs
│       │   ├── IngestionService.java       # PDF -> pages -> chunks -> embeddings -> Weaviate
│       │   └── QueryService.java           # cache check -> retrieval -> generation -> confidence
│       ├── dto/                  # request/response records
│       └── exception/            # typed exceptions + a single @RestControllerAdvice handler
└── docusense_frontend/           # React (Vite) + Bootstrap + Axios chat UI
    └── src/
        ├── api/client.js         # axios instance, one function per endpoint
        └── components/          # Sidebar, ChatThread, AnswerCard, ConfidenceBadge,
                                   # QuestionComposer, SourceViewerModal
```

No database was added beyond Weaviate. The document catalog (file name, chunk count,
ingested-at) is intentionally kept as small JSON sidecar files next to each stored PDF
(`/data/uploads/{id}.json`), loaded into memory on startup. This avoids adding a second
persistence technology for what is effectively a small, low-write lookup table.

## 2. Ingestion pipeline (as implemented)

1. Frontend uploads a PDF (`POST /api/documents/upload`, multipart).
2. Backend validates: file present, non-empty, `.pdf` extension/content-type — otherwise
   a 400 or 415 is returned immediately, before anything touches disk.
3. A `sourceFileId` (UUID) is generated and the raw PDF is saved to `./data/uploads/{id}.pdf`.
4. Apache PDFBox (`PDFTextStripper`, page-scoped via `setStartPage`/`setEndPage`) extracts
   text **one page at a time**, so every chunk can carry its true page number.
5. Each page's text is split with LangChain4j's `DocumentSplitters.recursive(500, 50)`.
6. Each chunk is embedded locally with `AllMiniLmL6V2EmbeddingModel` (no network call) and
   upserted into the `Documents` Weaviate class with metadata: `sourceFileName`,
   `sourceFileId`, `pageNumber`, `chunkIndex`, `ingestedAt`.
7. The catalog service records `{sourceFileId, fileName, chunkCount, ingestedAt}` and an
   `UploadResponse` is returned to the frontend.

## 3. Query pipeline (as implemented)

1. `POST /api/query` with `{"question": "..."}` — a blank/missing question is rejected
   with 400 before any model is called.
2. The question is embedded once (same local embedding model).
3. **Semantic cache check**: search the `QueryCache` Weaviate class for the nearest cached
   question, with the similarity threshold (0.92, configurable) passed directly as
   Weaviate's `certainty` filter on the vector search itself — so a search returning zero
   matches *is* a cache miss, no extra comparison needed.
4. If a candidate is found, its stored `corpusVersion` is compared against the corpus's
   current version (see §4). If they differ, the hit is treated as stale and discarded.
5. On a cache miss: the top 5 chunks are retrieved from `Documents` by vector similarity.
   If none are found, a plain "nothing relevant found" answer is returned (confidence Low,
   no citations, no LLM call).
6. Otherwise a prompt is built from the system template in the spec, with every excerpt
   labeled by source file name and page number, and sent to `gpt-4o-mini` via
   `langchain4j-open-ai`.
7. Confidence is the retrieved top chunk's Weaviate `certainty` (not recomputed), banded
   into High (≥0.75) / Medium (≥0.55) / Low. **This is a retrieval-quality proxy — how
   well the question matched the stored text — not a calibrated probability that the
   answer is factually correct.** A well-matched retrieval can still be misread by the
   model, and a poorly-matched retrieval can occasionally still yield a fine answer.
8. The answer + citations + confidence are stored back into `QueryCache` (question
   embedding, answer, confidence, citations as JSON, current `corpusVersion`) for future
   reuse, and returned to the frontend with `servedFromCache: false`.

### Deviation from spec: which chunks were "actually used"

The spec (§5) describes parsing the model's answer to determine which retrieved chunks
it actually cited. This build returns **all retrieved top-5 chunks as citations**,
rather than parsing model output to detect per-claim usage — that would require
structured/function-calling output and materially more complexity for a first phase.
The system prompt still instructs the model to note the source excerpt for every claim
in the answer text itself, so the transparency goal is preserved; the citations panel
is simply "what was in front of the model" rather than "what it definitely quoted."

## 4. Semantic cache invalidation

No per-document or per-chunk invalidation is implemented in this phase. Instead, a
coarse **corpus version** is used: the sum of `chunkCount` across every ingested document
in the catalog. Every cache entry stores the corpus version at the time it was created;
on lookup, if the current corpus version doesn't match, the cached answer is discarded
and the full pipeline re-runs. In practice this means: **any** new ingestion invalidates
**every** cached answer, not just the ones related to the new document. This is a known,
intentionally simple limitation — precise invalidation (e.g. only invalidating cache
entries whose citations point at a changed/deleted document) is a natural next step but
was out of scope here.

## 5. Error handling

A single `@RestControllerAdvice` (`GlobalExceptionHandler`) maps every failure to a
JSON body of `{timestamp, status, error, message, path}` — never a bare 500 with no
explanation:

| Situation | Status |
|---|---|
| Missing/blank question, missing/empty file, malformed JSON body, missing multipart file | 400 |
| Non-PDF upload | 415 |
| Upload exceeds 25MB | 413 |
| Unknown document id | 404 |
| OpenAI call fails, Weaviate call fails, disk I/O fails | 502, with a message naming the failing dependency |
| Anything truly unexpected | 500, generic safe message to the client, full stack trace logged server-side |

## 6. Exact dependency versions used

LangChain4j moves quickly; the spec's example artifact names were valid but the module
versions differ from module to module in this generation (the Weaviate and local-embedding
connectors trail the core library by design — they ship as `-betaN` releases pinned to
core release numbers). Versions actually used:

- `dev.langchain4j:langchain4j:1.19.0`
- `dev.langchain4j:langchain4j-open-ai:1.19.0`
- `dev.langchain4j:langchain4j-weaviate:1.19.0-beta29`
- `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.19.0-beta29`
- `org.apache.pdfbox:pdfbox:3.0.3`
- Spring Boot `4.1.0` (Java 21), which also meant two adjustments from older Spring Boot
  tutorials still circulating: `AutoConfigureMockMvc` now lives under
  `org.springframework.boot.webmvc.test.autoconfigure`, and Spring Boot 4's Jackson
  auto-configuration wires a `tools.jackson.databind.ObjectMapper` bean (Jackson 3.x),
  not the classic `com.fasterxml.jackson.databind.ObjectMapper`.

## 7. Setup

Environment variables:

```
OPENAI_API_KEY=sk-...        # required for /api/query to actually generate answers
```

Steps:

```bash
# 1. Start Weaviate
docker compose up -d

# 2. Start the backend (port 8081)
cd docusense_backend
export OPENAI_API_KEY=sk-...
./mvnw spring-boot:run

# 3. Start the frontend (port 5173)
cd docusense_frontend
npm install
npm run dev
```

Weaviate connection settings live in `docusense_backend/src/main/resources/application.properties`
(`weaviate.host`, `weaviate.port`, `weaviate.scheme`) and already point at the local
Docker Compose instance. The frontend's backend URL is set via `VITE_API_BASE_URL` in
`docusense_frontend/.env` (defaults to `http://localhost:8081`).

Without `OPENAI_API_KEY` set, ingestion and retrieval work fully (embedding is local),
but `/api/query` will return a 502 with a message pointing at the missing key, rather
than crashing or hanging.
