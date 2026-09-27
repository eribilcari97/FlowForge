# FlowForge

FlowForge is a workflow and job execution platform, designed so that users can define multi-step background processes once and execute them manually or on a schedule.

> **Status: Foundation implemented ([roadmap §1](docs/roadmap.md#1-foundation)). No features yet.**
>
> The backend, frontend, local database, test infrastructure and CI are set up. Accounts, projects, workflows and the execution engine are not implemented yet.

---

## What FlowForge is designed to do

A workflow is a set of steps connected by dependencies. Example steps: call an HTTP API, wait, transform data, send an email.

```text
        create_crm_contact ─┐
input ─┤                    ├─► wait_1_day ─► notify_crm
        create_account ─────┘
```

Planned capabilities:

- User accounts with JWT authentication, where each user can access only their own data
- Projects that group workflows
- Workflows made of steps (initially **HTTP** and **DELAY** job types, later **TRANSFORM** and **EMAIL**)
- Dependencies between steps, validated as a directed acyclic graph (cycles are rejected)
- Passing data between steps with placeholders such as `{{steps.fetch_orders.output.body.id}}`
- Manual execution with JSON input, protected against duplicate requests
- Concurrent execution of independent steps
- Retries of transient failures with exponential backoff, and per-attempt timeouts
- Automatic recovery of jobs interrupted by a crash
- Cancellation of running executions
- Cron schedules with time zones
- A web UI to inspect execution history, job states and every attempt with its error

The full requirements are in [docs/FRS.md](docs/FRS.md).

---

## Planned architecture

**Target architecture:** a modular monolith with one Angular application, one Spring Boot application and one PostgreSQL database.

```text
Angular SPA
    │  HTTP + JSON (JWT)
    ▼
Spring Boot application
 ├── REST API
 ├── Worker          (planned: poll loop + thread pool)
 ├── Scheduler       (planned: cron schedules → executions)
 └── Recovery task   (planned: jobs whose worker stopped)
    │
    ▼
PostgreSQL           (system of record and job queue)
```

The execution engine (worker, scheduler and recovery task) is planned to run inside the Spring Boot application initially. The main design decisions:

- **PostgreSQL as the job queue.** Queue state and job state are intended to stay transactionally consistent in one database, so the design needs no message broker.
- **API and worker communicate only through the database.** This is intended to let several application instances share the workload later without code changes.
- **Safe job claiming.** The planned worker will use `SELECT … FOR UPDATE SKIP LOCKED` so that no job is run by two workers at once.
- **No transaction open during external work.** The design uses short transactions before and after each HTTP call or delay.
- **Per-execution row lock when recording outcomes.** This ensures a dependent job is released exactly once, even when its dependencies finish at the same moment.
- **Leases and attempt numbers (fencing).** Designed to recover crashed jobs and discard late results from stalled workers.
- **Execution snapshots.** Steps are to be copied into jobs when a run starts, so later workflow edits don't affect running executions.
- **At-least-once execution with exactly-once result recording.** Steps that aren't safe to repeat (POST, email) will not be retried automatically when their outcome is unknown.

Redis, Kafka, microservices and Kubernetes are deliberately not part of the design. The reasoning is in [architecture.md §10](docs/architecture.md#10-technology-decisions).

---

## Planned technology stack

Configured so far: Java 21, Spring Boot, PostgreSQL, Spring Data JPA, Flyway, Angular with Angular Material, JUnit 5, Testcontainers, Docker Compose (database only) and GitHub Actions. The others arrive with the roadmap section that needs them.

| Area | Planned technology |
|---|---|
| Backend | Java 21, Spring Boot |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Database migrations | Flyway |
| Security | Spring Security + JWT |
| Frontend | Angular, RxJS, Angular Material |
| Testing | JUnit 5, Testcontainers, WireMock |
| Infrastructure | Docker, Docker Compose, Nginx |
| CI/CD | GitHub Actions |

---

## Documentation

```text
docs/
├── FRS.md                Purpose, scope, user stories, functional and non-functional requirements, example workflows
├── architecture.md       Target system structure, security, frontend, deployment, technology decisions
├── database.md           Planned tables, ER diagram, constraints, indexes, migration plan
├── api.md                Planned REST endpoints, request and response formats, status codes
├── execution-engine.md   Job states, claiming, concurrency, retries, idempotency, crash recovery, scheduling
└── roadmap.md            Implementation order, definition of done, testing strategy
```

---

## Repository structure

```text
FlowForge/
├── backend/                  Spring Boot application (Maven wrapper)
├── frontend/                 Angular application
├── docs/                     Design documentation
├── .github/workflows/ci.yml  Backend and frontend build and tests
├── docker-compose.yml        PostgreSQL for local development
└── README.md
```

The backend package structure is described in [architecture.md §3](docs/architecture.md#3-backend-structure). Layer packages exist as placeholders (`package-info.java`) until their first classes are implemented.

---

## Implementation roadmap

Implementation will proceed incrementally: project foundation → accounts and domain features → workflow execution → reliability (retries, recovery, scheduling) → infrastructure → deployment. Each step is planned as a vertical slice with its own tests.

The full implementation order is in [docs/roadmap.md](docs/roadmap.md).

---

## Getting started

**Prerequisites:** Java 21 or newer, Node.js 24 (or 22 LTS), Docker.

```bash
# 1. Start PostgreSQL (localhost:5432, database/user/password: flowforge)
docker compose up -d

# 2. Start the backend (http://localhost:8080)
cd backend
./mvnw spring-boot:run

# 3. Start the frontend (http://localhost:4200)
cd frontend
npm install
npm start
```

The frontend dev server proxies `/api` and `/actuator` to `http://localhost:8080` (`frontend/proxy.conf.json`), so the browser only talks to `localhost:4200`. The toolbar shows the backend status read from `/actuator/health`.

The backend's database connection defaults to the Compose database. Override it with `FLOWFORGE_DB_URL`, `FLOWFORGE_DB_USERNAME` and `FLOWFORGE_DB_PASSWORD`.

Health check: `GET http://localhost:8080/actuator/health`

**Run the tests** (Docker must be running, because backend integration tests start PostgreSQL with Testcontainers):

```bash
cd backend && ./mvnw verify
cd frontend && npm test -- --watch=false
```

---

## Development status

```text
Architecture/design    Complete
Repository foundation  Complete
Backend                Foundation only (health, error handling)
Frontend               Foundation only (application shell)
Database               PostgreSQL and Flyway configured, no tables yet
Execution engine       Not started
Testing                Infrastructure in place (Testcontainers, Vitest)
Docker                 PostgreSQL via Docker Compose only
CI/CD                  CI (build and tests). No CD.
Deployment             Not started
```
