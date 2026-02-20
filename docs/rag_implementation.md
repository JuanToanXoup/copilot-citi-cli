# RAG Implementation — Copilot Chat Plugin

Retrieval-Augmented Generation (RAG) for the Copilot Chat IntelliJ plugin. Automatically indexes project code into a local vector database, and for each chat message, retrieves the most relevant code snippets and injects them as context — giving the model deeper project understanding without manual file references.

## Architecture

```
                     ┌──────────────────────────────────────────────┐
                     │              Background Indexing              │
                     │                                              │
  Project Files ───► │  PsiChunker ──► CopilotEmbeddings ──► Qdrant │
                     │  (PSI-aware      (Copilot API           (local
                     │   splitting)      1536-dim vectors)      vector DB)
                     └──────────────────────────────────────────────┘

                     ┌──────────────────────────────────────────────┐
                     │               Query Pipeline                 │
                     │                                              │
  User Message ────► │  Embed query ──► Search Qdrant ──► Format    │
                     │                   (cosine sim)      as XML   │
                     │                                              │
                     │  ┌─────────────────────────────────────┐     │
                     │  │ <rag_context>                       │     │
                     │  │   <code_context file="Foo.kt" ...>  │     │
                     │  │     fun bar() { ... }               │     │
                     │  │   </code_context>                   │     │
                     │  │ </rag_context>                      │     │
                     │  └─────────────────────────────────────┘     │
                     │                    │                          │
                     └────────────────────┼─────────────────────────┘
                                          │
                                          ▼
                              Prepend to user message
                                          │
                                          ▼
                                Copilot Language Server
```

## Components

### 1. CopilotEmbeddings (`rag/CopilotEmbeddings.kt`)

Generates embedding vectors using the Copilot Internal Embeddings API — the same API used by GitHub Copilot Chat. This means zero additional setup: no API keys, no external services, no Ollama. It reuses the existing `ghu_` OAuth token from `~/.config/github-copilot/apps.json`.

**Token flow:**

```
ghu_ token (from apps.json)
    │
    ▼
POST https://api.github.com/copilot_internal/v2/token
    Authorization: token ghu_xxx
    │
    ▼
Session token (tid=..., expires ~30min, cached)
    │
    ▼
POST https://api.githubcopilot.com/embeddings
    Authorization: Bearer <session_token>
    Body: {"model": "copilot-text-embedding-ada-002", "input": [...]}
    │
    ▼
1536-dimension float vectors (one per input text)
```

**Key details:**

- **Model**: `copilot-text-embedding-ada-002` — same as OpenAI's `text-embedding-ada-002`, served through Copilot's API
- **Vector dimension**: 1536 floats per text
- **Batching**: Up to 50 texts per API call, with 200ms delay between batches to respect rate limits
- **Token caching**: Session token cached in memory, auto-refreshed 5 minutes before expiry
- **Proxy support**: Reads `proxyUrl` from `CopilotChatSettings` and configures `HttpClient` accordingly
- **Auth source**: Reads the `ghu_` token via `CopilotAuth.readAuth()` — the same token the rest of the plugin uses

The token exchange endpoint (`copilot_internal/v2/token`) returns a short-lived JWT that grants access to Copilot services. The embeddings endpoint accepts the same request format as OpenAI's embeddings API.

### 2. QdrantManager (`rag/QdrantManager.kt`)

Manages a bundled [Qdrant](https://qdrant.tech/) binary for local vector storage. Qdrant is a high-performance vector database written in Rust. We download a platform-specific binary and run it as a local process — no Docker, no external service.

**Lifecycle:**

```
ensureRunning()
    │
    ├── Is binary installed? (~/.copilot-chat/qdrant/qdrant)
    │       NO → downloadBinary()
    │              ├── Detect platform (darwin-arm64, darwin-x64, linux-x64, windows-x64)
    │              ├── Download from github.com/qdrant/qdrant/releases/v1.13.2
    │              ├── Extract tar.gz (unix) or zip (windows)
    │              └── chmod +x
    │
    ├── Is process running & healthy?
    │       NO → startProcess()
    │              ├── ProcessBuilder with --storage-path and --http-port 6333
    │              ├── Drain stdout in daemon thread
    │              └── waitForHealthy() — poll GET /healthz up to 30 times
    │
    └── Ready ✓
```

**REST API wrappers:**

All communication with Qdrant uses its REST API on `localhost:6333` via `java.net.http.HttpClient` (JDK 17, no extra dependencies):

| Operation | Qdrant Endpoint | Description |
|---|---|---|
| `ensureCollection(name, dim)` | `PUT /collections/{name}` | Create collection with cosine distance if not exists |
| `upsertPoints(collection, points)` | `PUT /collections/{name}/points` | Insert/update vectors with payloads |
| `search(collection, vector, topK)` | `POST /collections/{name}/points/search` | Cosine similarity search with score threshold |
| `deletePoints(collection, ids)` | `POST /collections/{name}/points/delete` | Remove points by ID |
| `scrollAll(collection)` | `POST /collections/{name}/points/scroll` | Paginated iteration over all points |

**Storage**: `~/.copilot-chat/qdrant/storage/` — persists across IDE restarts. Each project gets its own collection named `copilot-chat-{hash(projectName)}`.

**Design decision — why Qdrant over in-process alternatives:**
- Qdrant is a single static binary (~40MB), no runtime dependencies
- Survives IDE restarts (data on disk), no re-indexing needed
- Built-in HNSW index with proper cosine similarity
- REST API is trivial to call from JDK HttpClient
- Follows the same pattern as PlaywrightManager (download binary, manage process)

### 3. PsiChunker (`rag/PsiChunker.kt`)

Splits source files into semantically meaningful chunks using IntelliJ's PSI (Program Structure Interface). This is the key differentiator from naive line-based chunking — each chunk represents a logical code unit.

**Chunking strategy:**

```
Source File
    │
    ├── Has PSI StructureHandler? (Java, Kotlin, Python, JS, Go, Rust, PHP)
    │       YES → Structure-aware chunking
    │       NO  → Fallback to line-based (60 lines, 5-line overlap)
    │
    └── Structure-aware chunking:
            │
            ├── Method/Function → one chunk each
            │     Content: full method body
            │     Symbol: "ClassName.methodName"
            │
            ├── Small class (<30 lines) → one chunk
            │     Content: entire class body
            │     Symbol: "ClassName"
            │
            ├── Large class (>=30 lines) → split:
            │     ├── Header chunk (signature + fields)
            │     │     Symbol: "ClassName (header)"
            │     └── Individual method chunks
            │           Symbol: "ClassName.methodName"
            │
            └── Top-level declarations → individual chunks
                  Content: constants, type aliases, etc.
```

**Integration with existing PSI infrastructure:**

The plugin already has `LanguageHandlerRegistry` with `StructureHandler` implementations for 7+ languages. PsiChunker calls `getStructureHandler(psiFile)` and processes the returned `StructureNode` tree. Each `StructureNode` has:
- `name` — symbol name
- `kind` — CLASS, METHOD, FUNCTION, FIELD, etc.
- `line` — start line number
- `children` — nested structure nodes

**Chunk data class:**

```kotlin
data class CodeChunk(
    val filePath: String,     // absolute path
    val startLine: Int,       // 1-indexed
    val endLine: Int,         // 1-indexed, inclusive
    val content: String,      // raw source text (max 8000 chars)
    val symbolName: String?,  // e.g. "UserService.findById"
)
```

**Constraints**: Min 50 chars (skip trivial), max 8000 chars per chunk.

### 4. RagIndexer (`rag/RagIndexer.kt`)

Background indexing service that ties the chunker, embeddings, and Qdrant together. Runs as a project-level service.

**Full index flow:**

```
indexProject()
    │
    ├── Ensure Qdrant running (QdrantManager.ensureRunning())
    ├── Ensure collection exists (ensureCollection)
    ├── Scroll existing points → build filePath→contentHash map
    ├── Collect project files (ProjectFileIndex.iterateContent)
    │
    ├── For each file:
    │       ├── Read content, compute MD5 hash
    │       ├── Skip if hash matches existing → incremental!
    │       ├── Chunk via PsiChunker (ReadAction for thread safety)
    │       ├── Embed all chunks via CopilotEmbeddings.embedBatch()
    │       ├── Delete old points for this file (filter by filePath)
    │       └── Upsert new points (batches of 20)
    │
    └── Cleanup: delete points for files no longer in project
```

**Incremental indexing**: Each Qdrant point stores a `contentHash` (MD5) in its payload. On re-index, files with unchanged hashes are skipped entirely. This makes re-indexing fast after small edits.

**File filtering:**

- Uses `ProjectFileIndex.iterateContent()` to respect IntelliJ's project structure
- Excludes directories: `build/`, `out/`, `node_modules/`, `.gradle/`, `.idea/`, `.git/`, `dist/`, `vendor/`, `__pycache__/`, etc.
- Supports 40+ file extensions (kt, java, py, js, ts, go, rs, etc.)
- Max file size: 500KB (skip large generated files)

**Point payload** (stored in Qdrant alongside the vector):

```json
{
  "filePath": "/Users/.../src/main/kotlin/UserService.kt",
  "startLine": "15",
  "endLine": "32",
  "symbolName": "UserService.findById",
  "content": "suspend fun findById(id: String): User? { ... }",
  "contentHash": "a1b2c3d4e5f6..."
}
```

**Concurrency**: Runs in `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Cancellable via `cancelIndexing()`. Progress exposed via `isIndexing`, `indexedFiles`, `totalFiles` properties (polled by the Memory tab UI).

### 5. RagQueryService (`rag/RagQueryService.kt`)

Query pipeline invoked on every chat message when RAG is enabled.

**Flow:**

```
retrieve(query="how does auth work?", topK=5)
    │
    ├── Check Qdrant is running (return "" if not)
    ├── Embed query text → 1536-dim vector
    ├── Search Qdrant collection (cosine similarity, threshold 0.3)
    ├── Deduplicate overlapping chunks (same file + line range)
    ├── Format results as XML, respecting 4000 char budget
    └── Return formatted string (or "" on any failure)
```

**Output format:**

```xml
<rag_context>
<code_context file="src/main/kotlin/auth/AuthService.kt" lines="10-25" symbol="AuthService.validate">
suspend fun validate(token: String): Boolean {
    val decoded = jwtDecoder.decode(token)
    return decoded.expiresAt.isAfter(Instant.now())
}
</code_context>
<code_context file="src/main/kotlin/auth/TokenProvider.kt" lines="1-18" symbol="TokenProvider">
class TokenProvider(private val config: AuthConfig) {
    fun generate(user: User): String { ... }
}
</code_context>
</rag_context>
```

**Budget**: Results are added in order of relevance score until the 4000 character limit is reached. Lower-scored results that would exceed the budget are dropped.

**Error handling**: Every failure returns `""` — RAG is purely additive and must never break chat functionality.

## Integration Points

### ConversationManager (`conversation/ConversationManager.kt`)

The RAG context is injected in `sendMessage()`, before the message is sent to the Copilot Language Server:

```kotlin
// In sendMessage():
val settings = CopilotChatSettings.getInstance()
val lspText = if (settings.ragEnabled) {
    val ragContext = try {
        RagQueryService.getInstance(project).retrieve(text, settings.ragTopK)
    } catch (e: Exception) {
        log.debug("RAG retrieval failed, continuing without context: ${e.message}")
        ""
    }
    if (ragContext.isNotEmpty()) "$ragContext\n\n$text" else text
} else text
```

- `lspText` (with RAG context) is sent to the LSP in `conversation/create` and `conversation/turn` requests
- The original `text` (without RAG context) is stored in the UI message list — the user sees their original message, not the augmented one
- Wrapped in try/catch: if RAG fails for any reason, chat works exactly as before

### CopilotChatSettings (`config/CopilotChatSettings.kt`)

Three settings persisted to `CopilotChatSettings.xml`:

| Setting | Type | Default | Description |
|---|---|---|---|
| `ragEnabled` | Boolean | `false` | Master toggle — when off, no RAG processing occurs |
| `ragTopK` | Int | `5` | Number of code chunks to retrieve per query (1-20) |
| `ragAutoIndex` | Boolean | `false` | Auto-index project on open |

### Memory Tab (`ui/MemoryPanel.kt`)

UI tab in the Copilot Chat tool window (between Workers and Recorder):

- **RAG Settings section**: Enable/disable checkbox, auto-index checkbox, topK spinner
- **Project Index section**: "Index Project" button, progress bar with file count, cancel button
- **How It Works section**: Brief explanation of the pipeline

### plugin.xml

Three services registered:

```xml
<applicationService serviceImplementation="...rag.QdrantManager"/>
<projectService serviceImplementation="...rag.RagIndexer"/>
<projectService serviceImplementation="...rag.RagQueryService"/>
```

`QdrantManager` is application-level (one Qdrant process shared across projects). `RagIndexer` and `RagQueryService` are project-level (each project has its own collection and query scope).

## Dependencies

No additional dependencies were added. The implementation uses:

- `java.net.http.HttpClient` (JDK 17) — HTTP calls to Qdrant REST API and Copilot Embeddings API
- `kotlinx.serialization.json` (already in project) — JSON serialization/deserialization
- IntelliJ Platform SDK — PSI, `ProjectFileIndex`, `ReadAction`, services, UI components

## File Summary

| File | Type | Lines | Role |
|---|---|---|---|
| `rag/CopilotEmbeddings.kt` | New | ~140 | Copilot API embedding client with token exchange |
| `rag/QdrantManager.kt` | New | ~370 | Qdrant binary download, process lifecycle, REST API |
| `rag/PsiChunker.kt` | New | ~200 | PSI-aware code splitting into semantic chunks |
| `rag/RagIndexer.kt` | New | ~280 | Background project indexing with incremental hashing |
| `rag/RagQueryService.kt` | New | ~110 | Query embed → search → format pipeline |
| `ui/MemoryPanel.kt` | New | ~210 | Memory tab UI for settings and indexing controls |
| `config/CopilotChatSettings.kt` | Modified | +18 | Added ragEnabled, ragTopK, ragAutoIndex |
| `conversation/ConversationManager.kt` | Modified | +14 | RAG context injection in sendMessage() |
| `ui/ChatToolWindowFactory.kt` | Modified | +7 | Register Memory tab |
| `META-INF/plugin.xml` | Modified | +9 | Register RAG services |
