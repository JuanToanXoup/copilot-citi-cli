# SpecKit Workflow Prompts — Single Microservice, 100% Unit Test Coverage

Copy-paste these prompts in order to take a single microservice from zero to 100% unit test coverage through the full SpecKit pipeline.

---

## Step 1: Constitution

```
/speckit.constitution

Project: User Account Microservice

Principles:

1. Test-First Development — Every module MUST have unit tests written before or alongside its implementation. No code MUST be merged without corresponding tests. The project MUST maintain 100% line and branch coverage as measured by the coverage tool, with zero exclusions or pragmas allowed without documented justification.

2. Single Responsibility — Each module, class, and function MUST do exactly one thing. Dependencies MUST be injected, never instantiated internally. All external calls (database, HTTP, message queue) MUST go through interfaces so they can be replaced with test doubles.

3. Deterministic Tests — Unit tests MUST NOT depend on network, filesystem, database, time, or randomness. All side effects MUST be injected and mockable. Tests MUST produce identical results on every run regardless of execution order or environment.

4. Boundary Isolation — The service MUST separate domain logic from infrastructure. Domain models MUST contain no framework imports. Repository, HTTP client, and message publisher interfaces MUST be defined in the domain layer and implemented in the infrastructure layer.

5. Explicit Error Handling — Every function that can fail MUST return or raise typed errors. No silent swallowing of exceptions. Error paths MUST have the same test coverage as happy paths.
```

---

## Step 2: Specify

```
/speckit.specify

Build a User Account microservice that handles user registration, authentication, and profile management. Users register with email and password, receive a verification email, and can log in to receive a JWT access token and refresh token. Authenticated users can view and update their profile (name, avatar, preferences). Passwords are hashed with bcrypt. The service exposes a REST API and publishes domain events (UserRegistered, UserVerified, PasswordChanged, ProfileUpdated) to a message broker. It stores user data in a PostgreSQL database. Admin users can list all users with pagination, search by email, and deactivate accounts. Deactivated users cannot log in but their data is retained. Rate limiting applies to login and registration endpoints. The service must support 100% unit test coverage for all layers: domain logic, application services, API handlers, and repository adapters.
```

---

## Step 3: Clarify

```
/speckit.clarify
```

Example answers:

- **Q1 (Token expiry):** "Access tokens expire in 15 minutes, refresh tokens in 7 days."
- **Q2 (Email verification):** "Verification links expire in 24 hours. Unverified users can log in but cannot update their profile."
- **Q3 (Password policy):** "Minimum 8 characters, at least one uppercase, one lowercase, one digit. No special character requirement."

---

## Step 4: Plan

```
/speckit.plan

I am building with:
- Language: Python 3.12
- Framework: FastAPI with Pydantic v2 for validation
- Database: PostgreSQL 16 via SQLAlchemy 2.0 (async)
- Testing: pytest with pytest-cov, pytest-asyncio, unittest.mock
- Authentication: PyJWT for token generation, bcrypt for password hashing
- Message broker: Kafka via aiokafka (interface-abstracted for testing)
- Coverage target: 100% line and branch coverage
- Architecture: Hexagonal (ports and adapters) — domain layer has zero framework imports
```

---

## Step 5: Validate the Plan

### 5a. Checklists

Run these to generate quality gates before moving to tasks:

#### Test Coverage Requirements
```
/speckit.checklist Unit test coverage completeness — verify that requirements exist for testing every domain model, service method, API handler, error path, edge case, and repository adapter. Focus on whether the spec defines what must be tested, not how.
```

#### Domain Logic Requirements
```
/speckit.checklist Domain model and business rule clarity — verify that registration validation rules, password policy, token lifecycle, account states, and event publishing triggers are specified with enough precision to write deterministic unit tests against them.
```

#### Error Handling Requirements
```
/speckit.checklist Error path coverage — verify that all failure scenarios are specified: invalid input, duplicate email, expired tokens, deactivated accounts, downstream service failures, rate limit exceeded. Each error must have a defined response code and message.
```

### 5b. Analyze

```
/speckit.analyze

Focus on:
1. Whether every domain model and service method has a corresponding test requirement
2. Whether error paths have equal coverage to happy paths in the specification
3. Whether all interfaces (repository, event publisher, HTTP client) are defined as testable abstractions in the plan
4. Whether the 100% coverage target is reflected in concrete plan details, not just stated as a goal
5. Constitution alignment — especially test-first development and deterministic test principles
```

*(Fix any gaps the analysis surfaces before proceeding to tasks.)*

---

## Step 6: Tasks

```
/speckit.tasks
```

*(Auto-generated from plan. The agent generates tasks organized as:)*
- *Phase 1: Setup — project scaffolding, pyproject.toml, conftest.py, coverage config*
- *Phase 2: Foundational — domain models, repository interfaces, service interfaces, event publisher interface*
- *Phase 3: User Stories — registration + tests, authentication + tests, profile management + tests, admin operations + tests*
- *Final: Polish — coverage report validation, edge case tests, integration test stubs*

---

## Step 7: Implement

```
/speckit.implement
```

*(The agent will:)*
- *Check checklists — prompt if any are incomplete*
- *Create `.gitignore`, `pyproject.toml` with coverage config*
- *Execute TDD: write test file first, then implementation, for each module*
- *Mark tasks `[X]` as they complete*
- *Run `pytest --cov --cov-branch --cov-fail-under=100` at the end*

---

## Quick Reference

| Step | Command | User Input Required? |
|------|---------|---------------------|
| 1 | `/speckit.constitution` | Yes — provide principles |
| 2 | `/speckit.specify <description>` | Yes — provide feature description |
| 3 | `/speckit.clarify` | Yes — answer questions |
| 4 | `/speckit.plan` | Yes — provide tech stack |
| 5a | `/speckit.checklist <domain>` | No — generates quality gates |
| 5b | `/speckit.analyze` | No — read-only audit report |
| 6 | `/speckit.tasks` | No — automatic from plan |
| 7 | `/speckit.implement` | Maybe — checklist gate may prompt |

**Commands used:** Only the 8 commands that SpecKit actually provides — 5 core (`constitution`, `specify`, `plan`, `tasks`, `implement`) and 3 optional (`clarify`, `checklist`, `analyze`).
