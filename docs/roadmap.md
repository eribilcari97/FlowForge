# FlowForge — Implementation Roadmap

| | |
|---|---|
| Related | [FRS.md](FRS.md) · [architecture.md](architecture.md) · [database.md](database.md) · [api.md](api.md) · [execution-engine.md](execution-engine.md) |

The system is built in vertical slices. Each section below delivers a working feature from database to API to UI, with its tests, and leaves the application runnable. Sections are listed in implementation order.

**MVP** (sections 1–8): authentication, projects, workflows, steps, dependencies, executions, the execution engine, HTTP and DELAY jobs, retries and crash recovery, scheduling.

---

## Definition of done

Applies to every section.

- The feature works end to end (UI → API → database → engine where relevant)
- New behavior is covered by tests, and all tests pass locally and in CI
- Schema changes are delivered as new Flyway migrations. Existing migrations are never edited.
- Documentation is updated when a design decision changes
- The application starts from a clean checkout following the README

---

## 1. Foundation

### Purpose

Establish the project structure, build, database and test infrastructure the rest of the system depends on.

### Implementation

- Spring Boot backend (Java 21, Maven wrapper) with the layer packages from [architecture §3](architecture.md#3-backend-structure)
- `docker-compose.yml` with PostgreSQL only. Flyway enabled.
- Global exception handler producing `ProblemDetail` with a `code` field
- `/actuator/health`
- Angular frontend: routing, Angular Material, application shell, dev proxy `/api → localhost:8080`
- CI (GitHub Actions): backend `mvn verify`, frontend test and build

### Tests

- Application context starts against PostgreSQL (Testcontainers)
- Error responses have the documented `ProblemDetail` format

### Complete when

A clean checkout builds and passes CI. The backend starts against the Compose database, and the frontend shell loads through the dev proxy.

---

## 2. Authentication and projects

### Purpose

User accounts and project ownership. Ownership is implemented before any workflow feature because every later query is scoped by the owning user. Adding it afterwards would mean revisiting every repository and endpoint.

### Implementation

- `"user"` and `project` tables (`V1`, `V2`)
- Registration, login and JWT authentication (60-minute access token, bcrypt passwords), `GET /api/auth/me`
- Project CRUD. A project can be deleted only when it's empty.
- Ownership enforcement: lookups by `id` **and** owner. Other users' resources return `404`.
- Angular: login and register pages, `AuthService`, `authInterceptor`, `authGuard`, project list, detail and form

### Tests

- Registration: success, duplicate email (`409`)
- Login: success, and wrong password or unknown email (both give the same generic `401`)
- Requests without a token → `401`
- User B requesting user A's project → `404`

### Complete when

Two users can each manage projects and cannot see each other's.

### Deferred

Roles, refresh tokens, rate limiting.

---

## 3. Workflows and steps

### Purpose

Define workflows and their steps with validated, typed configuration.

### Implementation

- `workflow` and `workflow_step` tables (`V3`)
- Workflow CRUD in `DRAFT`. Delete, or archive if the workflow has executions. Optimistic locking on metadata (`@Version` → `409 VERSION_CONFLICT`).
- Step CRUD: immutable `key` and `jobType`, config validated per job type (HTTP, DELAY), limit of 30 steps
- Angular: workflow page with a step list and a step form whose config fields depend on the job type

### Tests

- Config validation for HTTP and DELAY (valid and invalid cases)
- Step key uniqueness within a workflow
- Stale `version` on update → `409`
- Ownership through workflow → project

### Complete when

A workflow with HTTP and DELAY steps can be created and edited through the UI.

---

## 4. Workflow dependencies

### Purpose

Allow steps to depend on other steps, and guarantee that every workflow stays a valid DAG.

### Implementation

- `workflow_dependency` table with composite foreign keys (same-workflow edges only) and a no-self-dependency check (`V4`)
- `PUT …/steps/{stepId}/dependencies` (replaces the step's full dependency list)
- `WorkflowGraph`: cycle detection with the cycle path, topological order, ancestor lookup
- The workflow row is locked (`SELECT … FOR UPDATE`) while dependencies are validated and saved. This prevents concurrent edits from combining into a cycle.
- Deleting a step that others depend on → `409 STEP_HAS_DEPENDENTS`
- Activate and deactivate with full validation, including "placeholders reference only upstream steps"
- Angular: dependency picker (a step's own descendants are excluded), validation messages, activate button

### Tests

- Cycle rejection with the correct cycle path
- A valid diamond graph is accepted
- A cross-workflow dependency is rejected by the database
- Concurrent A→B and B→A edits never produce a stored cycle
- Activation fails with `422` and a list of problems

### Complete when

A workflow can be created, connected, validated and activated safely.

---

## 5. Executions

### Purpose

Create executions as isolated snapshots of a workflow. The worker is added in the next section, so execution creation is implemented and verified on its own first.

### Implementation

- `workflow_execution` and `job_execution` tables (`V5`)
- `POST /api/workflows/{id}/executions` → `202`, with optional `Idempotency-Key` (unique `dedup_key`) and per-workflow run numbers
- Steps copied into `job_execution` (config, limits, `depends_on`). Roots start `READY`, all other jobs `PENDING`.
- Execution list and detail, cancellation, "run again"
- Angular: run dialog (JSON input), execution list, execution detail refreshed by polling every 2 s while `RUNNING`

### Tests

- Correct initial job states for a diamond workflow
- The same `Idempotency-Key` sent twice, including concurrently, creates one execution
- Editing the workflow after starting an execution doesn't change the execution's jobs
- Cancellation sets waiting jobs to `CANCELLED`

### Complete when

Running a workflow produces an execution whose jobs are in the correct initial states and visible in the UI. Jobs don't progress yet.

---

## 6. Worker engine

### Purpose

Execute jobs in dependency order, with parallelism, exactly-once result recording and correct completion detection.

### Implementation

- `job_attempt` table and `locked_by` column (`V6`)
- Worker: poll loop plus a fixed pool of 4 threads, inside the Spring Boot application
- Claim query using `SELECT … FOR UPDATE SKIP LOCKED`. One attempt row per claim.
- `HttpJobHandler` (Spring `RestClient`), `DelayJobHandler` (delay implemented through `available_at`)
- Placeholder resolver for `{{input…}}`, `{{steps.<key>.output…}}` and `{{execution…}}`
- Outcome recording under the execution-row lock: unlock dependents, skip downstream jobs on failure, detect completion
- External work runs outside any database transaction
- Cancellation of running jobs as specified in [execution-engine §10](execution-engine.md#10-cancellation)
- `GET /api/job-executions/{id}` and a job detail page (attempts, output)
- Demo endpoints under the `dev` profile: `/demo/orders`, `/demo/flaky`, `/demo/slow`

Every failure is final at this point. Retries arrive in section 7.

### Tests

- Linear and diamond workflows against WireMock: correct order and completion
- 200 `READY` jobs claimed concurrently by 8 threads: no job claimed twice
- Two dependencies completing at the same instant, repeated: the dependent job is released exactly once
- A failed job skips its downstream jobs while independent branches continue
- Placeholders resolve correctly, and missing values fail the job

### Complete when

The customer onboarding example runs to completion against the demo endpoints, with jobs executing in dependency order.

### Known limitation until section 7

If the application stops while a job is running, the job stays `RUNNING`.

---

## 7. Retries, timeouts and crash recovery

### Purpose

Handle failures according to the retry rules, and recover jobs whose worker stopped.

### Implementation

- `lease_expires_at` column, lease check constraint and lease index (`V7`)
- Error classification (retryable or not) and exponential backoff (`READY` with a future `available_at`)
- `maxAttempts` enforcement → `FAILED`
- Hard per-attempt timeout
- Leases (`lease_expires_at = now() + timeout + 60 s`) and the recovery task (every 30 s) that marks attempts `ABANDONED`
- Fencing: outcomes are recorded only if `attempt_count` still matches
- "Safe to repeat" rule for timeouts and abandoned attempts
- `Idempotency-Key` header on outgoing HTTP requests
- Angular: retry countdown, attempt history with errors, filter for failed jobs

### Tests

- Fail, fail, succeed; and fail ×3 → `FAILED`
- A timeout interrupts the handler
- A job claimed and never reported is recovered after the lease expires
- A late outcome from an old attempt is ignored
- An abandoned POST step → `FAILED` with "outcome unknown"

### Complete when

A flaky endpoint is retried with backoff. A job interrupted by stopping the application is recovered after restart.

---

## 8. Scheduling

### Purpose

Create executions automatically from cron schedules, exactly once per due time.

### Implementation

- `workflow_schedule` table (`V8`)
- Schedule CRUD: 5-field cron, IANA time zone, input, enabled flag
- Scheduler tick every 15 s: `FOR UPDATE SKIP LOCKED` on due schedules, `dedup_key = schedule:<id>:<dueAt>`
- Skip the run if the workflow already has a `RUNNING` execution. At most one catch-up run after downtime.
- Angular: schedules tab with presets and the next run time

### Tests

- Next-run calculation, including a daylight-saving transition date
- Two concurrent scheduler ticks create exactly one execution
- A scheduled run is skipped while an execution is running

### Complete when

A schedule fires on time and creates exactly one execution per due time. **This completes the MVP.**

---

## 9. TRANSFORM and EMAIL job types

### Purpose

Support data transformation and email notification steps (sales report, KPI and product feed examples).

### Implementation

- `TransformJobHandler` using a JSONata library. The library is validated in a short spike before adoption.
- `EmailJobHandler` using Spring Mail. Mailpit added to `docker-compose.yml`.
- EMAIL is never repeated automatically after an unknown outcome
- `job_type` check constraints extended (`V9`)

### Tests

- Transform expressions: filtering, aggregation, arithmetic, missing fields
- Email delivered to a test SMTP server

### Complete when

The daily sales report example runs end to end, and the email is visible in Mailpit.

---

## 10. UI improvements

### Purpose

Improve navigation and visibility of executions.

### Implementation

- Workflow graph view (dagre layout, SVG). On execution pages, nodes are colored by job status.
- Dashboard: running executions, failures in the last 24 hours, upcoming scheduled runs

### Deferred

Server-Sent Events. Added only if polling proves inadequate. With a single instance they would use in-process events (`@TransactionalEventListener(AFTER_COMMIT)` → `SseEmitter`).

---

## 11. Multiple instances

### Purpose

Run several application instances against one database and verify that they share the work safely.

### Implementation

- Worker identity `hostname:pid:thread`, recorded on attempts
- `flowforge.worker.enabled=false` for API-only instances
- No engine changes are expected: claiming, recovery and scheduling already rely on row locks and unique constraints

### Tests

- Two worker instances processing 30 executions: every job succeeds exactly once
- Concurrent schedulers across instances

### Complete when

Two instances run side by side with no duplicate job execution or scheduling. Stopping one instance mid-job is recovered by the other.

---

## 12. Containerization

### Implementation

- Backend Dockerfile: multi-stage build, JRE runtime, non-root user
- Frontend Dockerfile: build stage plus Nginx serving static files and proxying `/api`
- `docker-compose.yml`: postgres, mailpit, backend, frontend (Nginx). Local development starts only `postgres` and `mailpit` from the same file.
- `demo` profile seeding a demo user and example workflows

### Complete when

`docker compose up` starts the complete system, reachable at `http://localhost`.

---

## 13. CI/CD

### Implementation

- CI on every push and pull request: backend tests (Testcontainers), frontend format check (Prettier), tests and build, Docker image builds
- CD on `main`: images pushed to GitHub Container Registry, tagged with the commit SHA

### Complete when

Every merge to `main` produces tested images in the registry.

---

## 14. Deployment

### Purpose

Run a public instance for free: the two Docker images on Render, PostgreSQL managed by Supabase.

### Implementation

- Render free web services running the GHCR images (`render.yaml`). Render provides HTTPS. Secrets are Render environment variables.
- Frontend Nginx proxies `/api` to the backend's public URL (`FLOWFORGE_API_URL`), so the browser stays same-origin and the same image runs locally and on Render.
- Supabase PostgreSQL through the session pooler over SSL. Flyway migrates on startup.
- Backend listens on `$PORT`, reports its commit at `/actuator/info`, and uses JVM settings that fit 512 MB.
- **SSRF protection in `HttpJobHandler`**: requests to private, loopback, link-local and metadata addresses are rejected. Required before public exposure.
- Rate limiting on login and registration (Nginx). Required before public exposure.
- Deployment job on `main`: Render deploy hooks with the commit's images → wait for the new version at `/actuator/info` → smoke test through the frontend.
- Self-hosted alternative: Compose profile `production` with Caddy (Let's Encrypt) and a nightly `pg_dump` backup.

### Complete when

The application runs publicly over HTTPS from the images of the latest `main` commit, with the SSRF guard active.

---

## 15. Backlog

Not scheduled. Each item is designed so it can be added without changing the core architecture.

- Resume a failed execution from the failed job
- Step conditions ("run only if …")
- Encrypted project secrets (removes FRS NFR-S3)
- Refresh tokens, admin role, audit log
- `CANCELLING` execution state with interruption of running jobs
- Per-step "API honors idempotency keys" flag (allows automatic retries of POST after unknown outcomes)
- Backoff jitter
- Evaluation of JobRunr for scheduling or retries
- Prometheus and Grafana metrics
- Redis, only for a measured need such as exact rate limits across instances

---

## Testing strategy

**Unit tests** (JUnit 5, AssertJ)
- Graph validation: cycles, topological order, ancestors
- State transitions
- Retry delay calculation
- Placeholder resolution
- Cron next-run calculation

**Integration tests** (Testcontainers PostgreSQL). H2 is not used because it doesn't reproduce PostgreSQL's locking and `SKIP LOCKED` behavior.
- Repositories and transactions
- Constraints: composite foreign keys, check constraints, unique keys
- Locking and concurrency: claiming, outcome recording, schedule firing

**API tests** (MockMvc + Testcontainers)
- Authentication and ownership (`401`, `404`)
- Validation (`400`, `422`)
- Workflow and execution endpoints, including idempotent execution creation

**Engine tests** (Testcontainers + WireMock + Awaitility)
- Job claiming and dependency resolution
- Retries and backoff
- Worker failure and recovery, fencing
- Cancellation

**Frontend tests**
- Auth interceptor and guard, polling service, step form behavior


