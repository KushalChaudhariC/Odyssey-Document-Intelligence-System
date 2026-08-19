# DocuSense

DocuSense is an internal RAG (Retrieval-Augmented Generation) assistant. Employees ask
plain-English questions and get answers pulled *only* from your company's own documents
(HR policies, SOPs, product manuals, onboarding guides) — with the exact source file,
page number, and a confidence indicator attached to every answer, so nothing has to be
taken on faith.

It also does **semantic answer caching**: if someone asks a question that's a close
paraphrase of one already answered — and the document library hasn't changed since —
the cached answer is returned instantly instead of re-running retrieval + generation.

For the full pipeline breakdown, design decisions, and known limitations, see
[`ARCHITECTURE.md`](./ARCHITECTURE.md). This README is about getting it running.

---

## How it works

```
Upload a PDF ──▶ extract text per page (PDFBox) ──▶ split into 500-char chunks
                                                              │
                                                              ▼
                                            embed locally (all-MiniLM-L6-v2, ONNX)
                                                              │
                                                              ▼
                                                  store in Weaviate ("Documents")

Ask a question ──▶ embed the question ──▶ check semantic cache (Weaviate "QueryCache")
                                                  │
                                     cache hit ───┼─── cache miss
                                        │                    │
                                        ▼                    ▼
                              return instantly      retrieve top-5 chunks
                                                              │
                                                              ▼
                                                  ask gpt-4o-mini, using ONLY
                                                  the retrieved excerpts
                                                              │
                                                              ▼
                                          return answer + citations + confidence,
                                          cache it for next time
```

## Features

- 📄 Upload PDFs, get them chunked, embedded, and indexed automatically
- 💬 Chat-style Q&A over your document library — not a search results page
- 🔗 Every answer cites the source file + page number, with a **View Source** button
  that opens the real PDF at the right page
- 🎯 Honest confidence indicator (High / Medium / Low) based on retrieval similarity —
  not a made-up "accuracy" score
- ⚡ Semantic cache — near-duplicate questions get instant answers
- 🧱 Clear client-vs-server error separation: bad input from the user comes back as a
  4xx with a plain-English reason, not a generic crash

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Spring Boot 4.1 (Java 21) |
| RAG orchestration | LangChain4j |
| Embeddings | `all-MiniLM-L6-v2`, running locally via ONNX (no API key, no network call) |
| Answer generation | OpenAI `gpt-4o-mini` |
| Vector database | Weaviate (via Docker Compose) |
| PDF parsing | Apache PDFBox |
| Frontend | React (Vite) + Bootstrap + Axios |

---

## Prerequisites

- **Java 21+** and Maven (the `mvnw` wrapper is included, no separate Maven install needed)
- **Docker** and Docker Compose (to run Weaviate locally)
- **Node.js 20+** (the frontend uses Vite 8, which requires Node ≥ 20)
- An **OpenAI API key** — required only for asking questions; uploading/browsing
  documents works without it

## Setup

### 1. Clone and configure environment variables

```bash
git clone <this-repo>
cd "Document Intelligence System"

cp docusense_backend/.env.example docusense_backend/.env
cp docusense_frontend/.env.example docusense_frontend/.env
```

Edit `docusense_backend/.env` and set your key:

```
OPENAI_API_KEY=sk-...
```

`docusense_frontend/.env` only needs `VITE_API_BASE_URL` if your backend isn't on
`http://localhost:8081`.

> The backend reads `OPENAI_API_KEY` as a real environment variable (via
> `application.properties`'s `${OPENAI_API_KEY:}`), not by loading `.env` itself — export
> it in your shell, your IDE run config, or your process manager before starting it.

### 2. Start Weaviate

```bash
docker compose up -d
```

This brings up Weaviate on `localhost:8080` (REST) and `localhost:50051` (gRPC), with a
persistent volume at `./weaviate_data`. Vectorization is disabled on purpose
(`DEFAULT_VECTORIZER_MODULE=none`) — DocuSense computes its own embeddings.

### 3. Start the backend

```bash
cd docusense_backend
export OPENAI_API_KEY=sk-...   # if not already exported
./mvnw spring-boot:run
```

Runs on `http://localhost:8081`. On first boot it creates `./data/uploads` for storing
raw PDFs and their catalog metadata.

### 4. Start the frontend

```bash
cd docusense_frontend
npm install
npm run dev
```

Runs on `http://localhost:5173`. Open it, upload a PDF from the sidebar, and start
asking questions.

---

## API endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/documents/upload` | Upload a PDF (multipart `file` field), triggers ingestion |
| `GET` | `/api/documents` | List ingested documents (name, chunk count, ingested date) |
| `GET` | `/api/documents/{id}` | Stream the original PDF (used by the source viewer, supports `#page=N`) |
| `POST` | `/api/query` | `{"question": "..."}` → `{answer, citations[], confidence, confidenceLabel, servedFromCache}` |

All errors come back as:

```json
{
  "timestamp": "...",
  "status": 400,
  "error": "Bad Request",
  "message": "question: question must not be blank",
  "path": "/api/query"
}
```

Client mistakes (bad/missing body, wrong file type, unknown document id) map to
4xx with a specific reason. Failures in OpenAI, Weaviate, or disk I/O map to `502` with
a message naming what failed — never a bare, unexplained `500`.

## Project structure

```
Document Intelligence System/
├── docker-compose.yml
├── README.md                  # you are here
├── ARCHITECTURE.md            # implementation deep-dive
├── docusense_backend/         # Spring Boot API
│   ├── .env.example
│   └── src/main/java/com/calfuslearning/docusense_backend/
│       ├── config/            # RAG beans (embedding/chat models, Weaviate stores), CORS
│       ├── controller/        # DocumentController, QueryController
│       ├── service/           # ingestion, query, PDF storage, document catalog
│       ├── dto/                # request/response records
│       └── exception/          # typed exceptions + global error handler
└── docusense_frontend/        # React chat UI
    ├── .env.example
    └── src/
        ├── api/client.js       # axios instance + API calls
        └── components/         # Sidebar, ChatThread, AnswerCard, ConfidenceBadge, ...
```

## Troubleshooting

- **`/api/query` returns 502 mentioning `OPENAI_API_KEY`** — the key isn't set, or isn't
  exported in the shell/process that started the backend. Ingestion and browsing still
  work without it.
- **Uploads fail with a connection error** — make sure `docker compose up -d` succeeded
  and `curl http://localhost:8080/v1/.well-known/ready` returns a 200.
- **Frontend can't reach the API** — check `VITE_API_BASE_URL` in
  `docusense_frontend/.env` matches where the backend is actually running, and that the
  backend's CORS `docusense.cors.allowed-origin` property matches the frontend's origin.
- **`npm install` / `npm run dev` fails on engine warnings** — the frontend needs Node
  20+; older Node versions will emit `EBADENGINE` warnings and Vite may fail to start.

## Known limitations

See [`ARCHITECTURE.md`](./ARCHITECTURE.md) for the full list, in short:

- Semantic cache invalidation is corpus-wide (coarse), not per-document.
- Confidence reflects retrieval similarity, not verified factual correctness.
- Citations list all retrieved excerpts given to the model, not a parsed "actually
  quoted" subset.
