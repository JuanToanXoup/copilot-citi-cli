# Agent Memory Architecture: Long-Term Domain Knowledge

## Why Raw Conversation Vectors Are Poor Memory

If you embed every chat message and search by similarity, you get:
- "how do I fix the build?" matches **every** past debugging session
- Critical fixes are buried among casual back-and-forth
- No distinction between a failed attempt and the actual solution
- Context is lost — a message only makes sense within its conversation

## What Actually Works: Layered Memory

The best agent memory systems separate knowledge into **types**, each stored and retrieved differently.

### 1. Semantic Memory — "What is true about this project"

Facts about architecture, patterns, conventions, dependencies.

```
- Auth uses JWT tokens validated by AuthMiddleware
- All REST endpoints are in /src/api/handlers/
- Database migrations use Flyway, run on startup
- The team uses Kotlin coroutines, not RxJava
```

**Storage**: Structured entries (not vectors). A simple markdown or JSON file works. Searched by keyword + category. Small — a project rarely has more than 50-200 facts.

### 2. Episodic Memory — "What happened and what worked"

Summaries of past sessions with **outcomes**, not transcripts.

```
- 2026-02-15: Fixed NPE in UserService.login(). Root cause: upstream
  API changed response format, session token field renamed from
  "token" to "access_token". Fix: updated field mapping in AuthClient.kt:42.

- 2026-02-18: Attempted to optimize DB queries with batch fetching.
  Failed — Hibernate N+1 fix broke lazy loading in OrderService.
  Reverted. Need to use @EntityGraph instead.
```

**Storage**: Append-only log with structured fields (date, summary, outcome, files touched). Searchable by vector similarity AND keyword. Grows ~1-2 entries per session — after a year that's maybe 500 entries.

### 3. Procedural Memory — "How to do things here"

Project-specific recipes and workflows.

```
- To add a new API endpoint: create handler in /handlers,
  add route in /routes/index.kt, add integration test in /tests/api/
- To run tests behind proxy: set GRADLE_OPTS=-Dhttps.proxyHost=...
- Deploy to staging: ./deploy.sh staging (requires VPN)
```

**Storage**: Structured entries, manually curated or agent-proposed. Rarely more than 20-50 entries.

### 4. Failure Memory — "What NOT to do"

Anti-patterns and known pitfalls specific to this codebase.

```
- Don't use System.loadLibrary() in IntelliJ plugins — classloader
  isolation breaks it. Must extract native libs manually.
- Don't call CopilotEmbeddings from behind corporate proxy —
  api.githubcopilot.com is blocked. Use LocalEmbeddings instead.
- PostgreSQL JSONB columns: don't use @Column(columnDefinition=...)
  with H2 test DB — use a custom Hibernate type instead.
```

**Storage**: Same as procedural. Small, high-value.

## The Key Insight

The **LLM itself** does the extraction. After each session:

1. Summarize what happened → episodic memory
2. Extract any new facts learned → semantic memory
3. Extract any procedures discovered → procedural memory
4. Extract any failures/anti-patterns → failure memory

This is a post-conversation step, not real-time embedding. The output is **compact, structured, high-signal** — the opposite of raw conversation vectors.

## Where Vectors Fit

Vectors are useful for **retrieval** over episodic memory (finding the session where you fixed a similar bug). But the other memory types are better served by keyword/tag search over structured data.

### Storage Layout

```
~/.copilot-chat/memory/
  <project>/
    semantic.md        ← facts (keyword search)
    episodes.json      ← session summaries (vector + keyword)
    procedures.md      ← how-to recipes (keyword search)
    failures.md        ← anti-patterns (keyword search)

  code-index/
    <project-hash>.json  ← RAG vectors (vector search)
```

The vector store only handles **code index** and **episode retrieval**. Everything else is structured text — simpler, more reliable, and more useful.

## Practical Impact on Storage Choice

This makes the case for a pure Kotlin flat vector store:

| Data | Count | Storage |
|------|-------|---------|
| Code vectors | 1K-5K per project | Brute-force vector search |
| Episode vectors | 500-1K over a year | Brute-force vector search |
| Semantic facts | 50-200 per project | Keyword search over markdown |
| Procedures | 20-50 per project | Keyword search over markdown |
| Failures | 20-50 per project | Keyword search over markdown |

No need for Qdrant, HNSW, or any external database. The hard part isn't storage — it's the **extraction pipeline** that turns conversations into structured knowledge.
