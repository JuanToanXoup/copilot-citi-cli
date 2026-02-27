# SpecKit Workflow Prompts — Microservices Project

Copy-paste these prompts in order to take a microservices project from governance through implementation.

---

## Step 1: Constitution — Establish Governance

```
/speckit.constitution

Project: E-Commerce Microservices Platform

Principles:

1. Service Autonomy — Each microservice MUST own its data and expose it only through well-defined APIs. No shared databases between services. Services MUST be independently deployable without coordinating releases with other teams.

2. API-First Design — All inter-service communication MUST use versioned REST or gRPC contracts defined before implementation. Breaking changes MUST go through a deprecation cycle of at least 2 minor versions. Every public endpoint MUST have an OpenAPI or Protobuf schema.

3. Observability by Default — Every service MUST emit structured logs (JSON), expose health check endpoints (/health, /ready), and publish metrics (latency, error rate, throughput) to a centralized observability stack. Distributed tracing MUST propagate correlation IDs across all service boundaries.

4. Resilience Over Availability — Services MUST implement circuit breakers, retries with exponential backoff, and graceful degradation for all downstream dependencies. No service failure should cascade beyond its bounded context. Timeouts MUST be explicitly configured for every outbound call.

5. Security as a First-Class Concern — All inter-service communication MUST use mTLS or signed JWTs. Secrets MUST never appear in code, logs, or environment variables at rest. All user-facing endpoints MUST validate input at the boundary and enforce rate limiting.

6. Infrastructure as Code — All deployment configurations MUST be declarative (Terraform, Helm, Kubernetes manifests). No manual infrastructure changes. Every environment (dev, staging, prod) MUST be reproducible from version-controlled configuration.

Governance:
- Ratification date: 2026-02-27
- Amendment procedure: Any principle change requires a PR with at least 2 approvals and an architecture review
- Compliance review: Monthly automated checks via CI pipeline
```

---

## Step 2: Specify — Define a Feature

```
/speckit.specify

Build an Order Management Service for the e-commerce platform. Users can create orders from their shopping cart, the service validates product availability by calling the Product Catalog Service, calculates totals including tax and shipping, reserves inventory, and processes payment through the Payment Service. Orders go through a lifecycle: Created → Confirmed → Paid → Shipped → Delivered, with the ability to cancel at any point before shipping. The service should support order history lookup, filtering by status and date range, and emit domain events (OrderCreated, OrderPaid, OrderShipped, OrderCancelled) so other services can react asynchronously. Must handle partial failures gracefully — if payment fails after inventory is reserved, inventory must be released. Expected scale: 500 orders per minute at peak, 99.9% availability target, order queries must return within 200ms.
```

---

## Step 3: Clarify — Resolve Ambiguities

```
/speckit.clarify
```

*(No arguments needed — the agent reads the existing spec and generates targeted questions. Answer each question as it comes. Example answers you might give:)*

- **Q1 (Scope):** "B — Start with the core order lifecycle only. Refunds and returns will be a separate service."
- **Q2 (Payment):** "A — Synchronous payment confirmation. We need to know immediately if payment succeeded before confirming the order."
- **Q3 (Events):** "B — Use an event broker (Kafka). We need durable, replayable events for downstream consumers."
- **Q4 (Idempotency):** "A — Yes, all order creation and payment endpoints must be idempotent using client-supplied idempotency keys."
- **Q5 (Multi-currency):** "C — USD only for now. Document the assumption and we'll add multi-currency later."

---

## Step 4: Plan — Technical Design

```
/speckit.plan

I am building with:
- Language: Go 1.22
- Framework: Chi router for HTTP, gRPC for inter-service communication
- Database: PostgreSQL 16 for order storage, Redis for caching and idempotency keys
- Message broker: Apache Kafka for domain events
- Containerization: Docker with Kubernetes (Helm charts)
- CI/CD: GitHub Actions
- Observability: OpenTelemetry, Prometheus, Grafana, Jaeger
```

---

## Step 5: Tasks — Break Into Tasks

```
/speckit.tasks
```

*(No arguments needed — the agent reads `plan.md` and `spec.md` and generates the full task breakdown automatically. It will organize tasks into phases:)*

- *Phase 1: Setup (project scaffolding, go.mod, Dockerfile, Helm chart)*
- *Phase 2: Foundational (database schema, migrations, domain models, Kafka producer/consumer)*
- *Phase 3+: User Stories (create order, process payment, order lifecycle transitions, order history query, event publishing)*
- *Final: Polish (health checks, metrics, integration tests, load testing)*

---

## Step 6: Checklist — Validate Requirements Quality

Run these separately to generate domain-specific checklists:

### 6a. API Requirements Quality
```
/speckit.checklist API contract completeness and consistency — focus on the REST and gRPC interfaces the Order Service exposes and consumes, including error schemas, versioning strategy, and pagination for order history queries
```

### 6b. Resilience Requirements Quality
```
/speckit.checklist Resilience and failure handling — focus on circuit breakers, retry policies, saga/compensation patterns for the order-payment-inventory coordination, timeout configurations, and graceful degradation requirements
```

### 6c. Data Requirements Quality
```
/speckit.checklist Data model and consistency — focus on order state machine transitions, idempotency guarantees, eventual consistency between services, event ordering, and data retention/archival requirements
```

### 6d. Security Requirements Quality
```
/speckit.checklist Security and access control — focus on authentication between services (mTLS/JWT), input validation at API boundaries, rate limiting, PII handling in order data, and audit logging requirements
```

---

## Step 7: Analyze — Check Consistency

```
/speckit.analyze

Pay special attention to:
1. Whether all order state transitions have corresponding tasks
2. Whether the compensation/saga pattern for payment failure → inventory release is fully covered in tasks
3. Whether observability requirements (tracing, metrics, health checks) have matching implementation tasks
4. Whether the 500 orders/minute scale target is reflected in performance-related tasks
5. Constitution alignment — especially service autonomy (no shared DB) and resilience principles
```

---

## Step 8: Implement — Execute the Plan

```
/speckit.implement
```

*(No arguments needed — the agent reads `tasks.md` and executes phase by phase. It will:)*

- *Check all checklists first — if any are incomplete, it asks whether to proceed*
- *Create `.gitignore`, `.dockerignore` for Go + Docker + Kubernetes*
- *Execute tasks in order: project setup → database/Kafka → domain models → API handlers → inter-service clients → event publishing → tests*
- *Mark each task `[X]` as it completes*
- *Stop and report if any task fails*

---

## Step 9: Tasks to Issues — Sync to GitHub

```
/speckit.taskstoissues
```

*(No arguments needed — the agent reads `tasks.md` and creates one GitHub issue per task. Each issue includes the task ID, description, phase label, and dependency references. Only works if the git remote is a GitHub repository.)*

---

## Quick Reference — Full Pipeline

| Step | Command | When to Run | Wait for User Input? |
|------|---------|-------------|---------------------|
| 1 | `/speckit.constitution` | Once per project | Yes — provide principles |
| 2 | `/speckit.specify <description>` | Once per feature | Yes — answer clarification questions if any |
| 3 | `/speckit.clarify` | After specify, before plan | Yes — answer up to 5 questions |
| 4 | `/speckit.plan` | After spec is clarified | Yes — provide tech stack |
| 5 | `/speckit.tasks` | Auto-sent from plan | No — fully automatic |
| 6 | `/speckit.checklist <domain>` | After plan, run multiple times | Yes — answer up to 3 scoping questions |
| 7 | `/speckit.analyze` | After tasks are generated | No — read-only report |
| 8 | `/speckit.implement` | After analysis passes | Maybe — checklist gate may prompt |
| 9 | `/speckit.taskstoissues` | After or during implementation | No — fully automatic |

---

## Tips

- **Steps 5 and 6 run in parallel** — tasks auto-sends from plan, but you can run checklists at the same time.
- **Steps 7 and 8 auto-send from tasks** — analyze runs first, then implement. Review the analysis report before implementation proceeds.
- **You can loop back** — if analyze finds CRITICAL issues, go back to specify or plan to fix them before implementing.
- **Checklists are cumulative** — each `/speckit.checklist` run creates a new file. Run it for each domain you care about (API, security, resilience, data, etc.).
- **Constitution is enforced everywhere** — plan checks it, analyze flags violations as CRITICAL, implement follows it. Update it deliberately with `/speckit.constitution` if principles need to change.
