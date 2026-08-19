# DocuSense — Build Specification

## 1. What we are building

**DocuSense** is an AI assistant that lets employees ask plain-English questions and get accurate, cited answers pulled from a company's internal document library (HR policies, product manuals, onboarding guides, SOPs).

This is a RAG (Retrieval-Augmented Generation) system. The core loop is: a user asks a question → the system finds the most relevant passages across all ingested documents → an LLM answers *using only those passages* → the answer is returned along with the exact source document, page number, and a confidence indicator, so the user can verify it themselves rather than blindly trusting the assistant.

The one architectural feature beyond a standard RAG pipeline that we are building in this phase is **semantic answer caching**: if a new question is a close paraphrase of a question already answered, and the underlying source documents haven't changed since, return the cached answer instantly instead of re-running the full retrieval + generation pipeline.
**Non-goals for this phase** (do not build these unless explicitly asked later): multimodal/image retrieval, structure-aware chunking beyond basic recursive splitting, hybrid dense+sparse search, cross-encoder reranking, query decomposition for multi-topic questions, CRAG-style retrieval grading, staleness/version-conflict detection. These are documented ideas for a future phase — keep the codebase simple and focused on what's specified below.

---

## 2. Tech stack (exact, do not substitute without asking)

| Layer | Choice | Notes |
|---|---|---|
| Backend framework | Spring Boot (Java 17+) | REST API |
| RAG orchestration | LangChain4j | `dev.langchain4j:langchain4j` + `dev.langchain4j:langchain4j-open-ai` |
| Embedding model | **all-MiniLM-L6-v2**, run in-process via ONNX | Maven artifact: `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2`. Class: `AllMiniLmL6V2EmbeddingModel`. Produces 384-dimensional vectors. Runs locally, no API key, no external call. |
| Answer generation | OpenAI (via LangChain4j's OpenAI integration) | Requires `OPENAI_API_KEY` as an environment variable — never hardcode it. Use a chat completion model (e.g. `gpt-4o-mini` for cost/speed unless told otherwise). |
| Vector database | **Weaviate** | Maven artifact: `dev.langchain4j:langchain4j-weaviate`, class `WeaviateEmbeddingStore`. Two object classes: `Documents` (the real content) and `QueryCache` (the semantic cache). Weaviate class names must start with an uppercase letter. |
| PDF parsing | Apache PDFBox | Extract raw text per page; keep page numbers attached to every extracted unit |
| Frontend | React (Vite) | Chat-style UI — see Section 6 for full spec |
| Local infrastructure | **Docker Compose** | Runs Weaviate as a container so no manual install is needed — see Section 3.5 |

If any of these libraries have breaking API changes at build time, note the discrepancy in the architecture doc (Section 8) rather than silently working around it.

---

## 3. Running Weaviate via Docker Compose

Do not require the developer to install Weaviate manually. Provide a `docker-compose.yml` in the project root so the whole stack starts with one command:

```yaml
version: "3.4"
services:
  weaviate:
    image: semitechnologies/weaviate:1.25.0
    ports:
      - "8080:8080"
      - "50051:50051"
    volumes:
      - ./weaviate_data:/var/lib/weaviate
    restart: on-failure:0
    environment:
      QUERY_DEFAULTS_LIMIT: 25
      AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED: "true"
      PERSISTENCE_DATA_PATH: "/var/lib/weaviate"
      DEFAULT_VECTORIZER_MODULE: "none"
      ENABLE_MODULES: ""
      CLUSTER_HOSTNAME: "node1"
```

Important: `DEFAULT_VECTORIZER_MODULE` must be set to `"none"`. DocuSense generates its own embeddings with `AllMiniLmL6V2EmbeddingModel` — it must not let Weaviate compute embeddings on its own, or the two systems will conflict. `ENABLE_MODULES` should stay empty for the same reason (no built-in vectorizer or generative modules needed for this project).

Backend `application.properties` (or `.yml`) should point at this local instance:

```
weaviate.host=localhost
weaviate.port=8080
weaviate.scheme=http
```

The developer should be able to run `docker compose up -d` once, then start the Spring Boot app — no separate manual Weaviate install or cloud account needed for local development. If a Spring Boot Docker Compose integration (`spring-boot-docker-compose` starter) is used instead, Spring Boot can even auto-start the container when the app runs — use this if it simplifies setup, and note the choice in the architecture doc.

Every chunk stored in the `Documents` class in Weaviate should carry, at minimum:

```json
{
  "sourceFileName": "Leave_Policy.pdf",
  "sourceFileId": "uuid-of-stored-file",
  "pageNumber": 2,
  "chunkIndex": 5,
  "chunkText": "Employees receive 18 paid leaves per year...",
  "ingestedAt": "2026-08-18T10:00:00Z"
}
```

Keep this payload minimal for now — no category/document-type field yet. That classification layer is a planned future addition, not part of this build.

The original PDF file itself is saved to local disk storage (e.g. `/data/uploads/{sourceFileId}.pdf`), not just parsed and discarded — this is required for the citation/source-viewing feature in Section 5.

---

## 4. Ingestion pipeline

1. User uploads a PDF via the frontend.
2. Backend saves the raw PDF file to disk, generates a `sourceFileId` (UUID).
3. Apache PDFBox extracts text page by page — keep every extracted text block associated with its page number.
4. Split each page's text into chunks using LangChain4j's recursive splitter: **500 characters, 50-character overlap** (standard default — this is intentionally simple for this phase).
5. Embed each chunk using `AllMiniLmL6V2EmbeddingModel`.
6. Upsert each chunk + its embedding + its metadata payload (Section 3) into the Weaviate `Documents` class using `WeaviateEmbeddingStore`.
7. Return an ingestion summary to the frontend: file name, number of chunks created.

---

## 5. Query pipeline (with citation, confidence, and semantic cache)

```
User question
     │
     ▼
Embed the question (AllMiniLmL6V2EmbeddingModel)
     │
     ▼
Check the `QueryCache` class in Weaviate: search for nearest cached question embedding
     │
     ├── similarity ≥ 0.92 AND cached answer's source chunks are still valid
     │        → return cached answer immediately, mark response as "servedFromCache: true"
     │
     └── no match / below threshold
              ▼
        Search the `Documents` class for the top-5 most similar chunks by
        vector similarity (via WeaviateEmbeddingStore / LangChain4j)
              ▼
        Build a prompt containing the question + the retrieved chunks (with their
        sourceFileName/pageNumber attached to each chunk in the prompt, so the
        model can reference them)
              ▼
        Call OpenAI chat model with a strict system prompt (see below)
              ▼
        Parse the model's answer + which chunks it actually used
              ▼
        Compute a confidence score (see below)
              ▼
        Store this question's embedding + answer + citations + confidence
        as a new object in the `QueryCache` class for future reuse
              ▼
        Return to frontend: answer, citations, confidence, servedFromCache: false
```

### System prompt for generation (starting point — refine as needed)

```
You are DocuSense, an internal document assistant. Answer the user's question
using ONLY the information in the provided document excerpts below. Do not use
any outside knowledge. If the excerpts do not contain enough information to
answer the question, say so clearly instead of guessing.

For every factual claim you make, note which excerpt it came from.

Document excerpts:
{{retrieved chunks, each labeled with its source file name and page number}}

Question: {{user question}}
```

### Confidence score — how to compute it (keep this honest and simple)

Do not invent a fake precision number. Use a transparent heuristic based on retrieval quality:

- Take the similarity/certainty value of the **top-ranked retrieved chunk**, returned by Weaviate as part of the search result — do not recompute this manually where Weaviate already provides it.
- Map it to a band:
  - similarity ≥ 0.75 → **High confidence**
  - 0.55 ≤ similarity < 0.75 → **Medium confidence**
  - similarity < 0.55 → **Low confidence** — and the UI should visually flag this as "verify this answer against the source" rather than presenting it as equally trustworthy.
- Return both the raw similarity-derived percentage AND the band label to the frontend. Document in the architecture notes (Section 8) that this is a retrieval-quality proxy, not a calibrated probability of correctness — this distinction matters and should not be glossed over.

### Citation payload returned to the frontend

For every chunk actually used in the answer, return:

```json
{
  "sourceFileName": "Leave_Policy.pdf",
  "sourceFileId": "uuid-of-stored-file",
  "pageNumber": 2,
  "snippet": "Employees receive 18 paid leaves per year...",
  "viewUrl": "/api/documents/{sourceFileId}#page=2"
}
```

### Serving the actual source document back to the user

Implement a `GET /api/documents/{sourceFileId}` endpoint that streams the stored PDF file back with `Content-Type: application/pdf`. The frontend opens this URL (with the `#page=N` fragment, which native browser PDF viewers respect) in a viewer panel or new tab, so the user can see the *actual original page* the answer came from — not just a text snippet they have to trust.

---

## 6. Frontend specification

**Layout: a chat interface, not a search-engine-style results page** — this matches the actual use case (conversational Q&A), per the "does the interface fit the context" evaluation criterion.

**Main panel (center):**
- Standard chat thread. User questions appear as right-aligned bubbles. Assistant answers appear as left-aligned cards, not plain bubbles, because each answer card needs to hold more than just text:
  - The answer text itself.
  - A **confidence badge** at the top of the card — colored pill (green = High, amber = Medium, red = Low) with the label and percentage.
  - A **"Sources" section** below the answer — one small citation card per source used, each showing: file name, page number, a short text snippet, and a **"View Source"** button.
  - Clicking "View Source" opens the actual PDF (via the `/api/documents/{id}#page=N` endpoint) in a side panel or modal — the user sees the real page, not just the extracted text.
  - A small, unobtrusive tag if the answer was served from cache (e.g. "⚡ instant answer" or similar) — useful for your demo to visibly show the cache working.

**Sidebar (left):**
- A simple document library list: shows all ingested files and an upload button to add new PDFs.

**Keep the visual design clean and uncluttered** — this tool will be demoed to a mentor and ideally operable by someone with zero technical background, per the mission's evaluation criteria. Avoid dense dashboards or unnecessary settings screens. Reference `/mnt/skills/public/frontend-design/SKILL.md` for styling conventions if building this as a proper React app rather than a rough prototype.

---

## 7. API endpoints (minimum set)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/documents/upload` | Upload a PDF, triggers ingestion pipeline |
| GET | `/api/documents` | List all ingested documents (file name, chunk count) |
| GET | `/api/documents/{id}` | Stream the raw PDF file back (for citation viewing) |
| POST | `/api/query` | Submit a question, returns `{answer, citations[], confidence, confidenceLabel, servedFromCache}` |

---

## 8. Deliverable: write an architecture document

Once built, create a file named `ARCHITECTURE.md` in the project root that documents, in your own words:
- The actual folder/module structure you created.
- How data flows through the ingestion pipeline and the query pipeline (you can reuse the diagrams above but describe what you actually implemented, including any deviations from this spec and why).
- The exact Maven dependencies and versions used, since LangChain4j artifact versions change frequently.
- How the semantic cache is invalidated (or note if invalidation isn't implemented in this phase — that's an acceptable limitation to state honestly rather than leave undocumented).
- Known limitations of the confidence score (that it reflects retrieval similarity, not verified correctness).
- Setup instructions: required environment variables (`OPENAI_API_KEY`, Weaviate host/port/scheme), how to bring up Weaviate via `docker compose up -d`, and how to start the backend and frontend.

This ARCHITECTURE.md is a required deliverable, not optional documentation — it's what will be reviewed alongside the working demo.
