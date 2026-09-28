# FlowForge

FlowForge is a workflow and job execution platform for multi-step background processes. You define a process once as a workflow: steps connected by dependencies. FlowForge then runs it manually or on a cron schedule. It executes steps in dependency order, runs independent steps in parallel, retries transient failures, recovers work interrupted by a crash, and records every attempt so each run can be inspected in a web UI.

It is a modular monolith built with Spring Boot, Angular and PostgreSQL. PostgreSQL is both the system of record and the job queue.

---

## Contents

- [The problem](#the-problem)
- [Core concepts](#core-concepts)
- [Example workflow](#example-workflow)
- [Architecture](#architecture)
- [Execution engine](#execution-engine)
- [Job lifecycle](#job-lifecycle)
- [Dependencies and concurrency](#dependencies-and-concurrency)
- [Retries and failure handling](#retries-and-failure-handling)
- [Crash recovery and leases](#crash-recovery-and-leases)
- [At-least-once execution and idempotency](#at-least-once-execution-and-idempotency)
- [Cancellation](#cancellation)
- [Scheduling](#scheduling)
- [Security and ownership](#security-and-ownership)
- [Database model](#database-model)
- [Frontend](#frontend)
- [Technology stack](#technology-stack)
- [Testing strategy](#testing-strategy)
- [Deployment and infrastructure](#deployment-and-infrastructure)
- [CI/CD](#cicd)
- [Repository structure](#repository-structure)
- [Running locally](#running-locally)
- [Design principles](#design-principles)
- [Documentation](#documentation)

---

## The problem

Small recurring processes usually become one-off scripts behind a cron entry. Examples include a daily sales report, a nightly product-feed sync, an API health check every five minutes, or a customer onboarding sequence that calls two services and follows up a day later. Every one of those scripts re-implements the same concerns: ordering, retries, timeouts, what happens after a crash, and how to find out what went wrong.

FlowForge handles those concerns once, in one place. It is aimed at developers and technical users who want to describe a process declaratively and get a complete history of every run, including every attempt of every step and its error.

---

## Core concepts

| Concept | Meaning |
|---|---|
| **Project** | Container owned by one user. Groups workflows and anchors ownership. |
| **Workflow** | Definition of a process: steps plus dependencies. Status is `DRAFT` (editable), `ACTIVE` (runnable and schedulable) or `ARCHIVED` (read-only history). |
| **Step** | One node of the workflow: a job type, a JSON configuration, a timeout and retry settings. Identified by an immutable `key` such as `fetch_orders`. |
| **Dependency** | "Step B may start only after step A has succeeded." A workflow's dependencies must form a **directed acyclic graph (DAG)**. |
| **Execution** | One run of a workflow, with its own JSON input, status and per-workflow run number. |
| **Job** | One step within one execution: the unit the worker runs. |
| **Attempt** | One try at running a job. A job has one current state but many attempts. |
| **Schedule** | A cron rule that creates executions automatically. |

**Job types**

| Type | Behavior |
|---|---|
| `HTTP` | Calls an HTTP API (method, URL, headers, JSON body, expected status codes). Output: status, selected headers, body and duration. |
| `DELAY` | Waits from 1 second to 7 days without occupying a worker thread. |
| `TRANSFORM` | Computes a JSON value from the input and upstream outputs using a JSONata expression. |
| `EMAIL` | Sends an email (recipients, subject, text or HTML body) via SMTP. |

**Data passing.** String values in a step's configuration may contain placeholders: `{{input.<path>}}`, `{{steps.<key>.output.<path>}}`, `{{execution.id}}` and `{{execution.runNumber}}`. Placeholders are plain path lookups with no expression evaluation. A placeholder may reference only steps upstream of it, which is checked when the workflow is activated. A value missing at run time fails the job with a non-retryable error.

**Definitions vs. runtime records.** Workflows and steps are definitions. Executions, jobs and attempts are runtime records. When an execution starts, every step is copied into a job (an **execution snapshot**), so editing a workflow never changes a run that already exists.

---

## Example workflow

Customer onboarding, started manually with input `{ "customer": { "email": "jane@example.com", "plan": "PRO" } }`:

```text
 create_crm_contact  (HTTP POST)  ─┐
                                   ├──►  wait_1_day (DELAY P1D)  ──►  notify_crm (HTTP POST)
 create_account      (HTTP PUT)   ─┘
```

```text
create_crm_contact  POST https://crm.example.com/api/contacts
                    body {"email": "{{input.customer.email}}"}
                    → output.body = { "id": "crm_881" }

create_account      PUT https://accounts.example.com/api/accounts/{{execution.id}}
                    body {"email": "{{input.customer.email}}", "plan": "{{input.customer.plan}}"}

wait_1_day          DELAY P1D  (starts when both have succeeded)

notify_crm          POST https://crm.example.com/api/contacts/{{steps.create_crm_contact.output.body.id}}/notes
                    body {"text": "Account {{steps.create_account.output.body.accountNumber}} active"}
```

The two roots run in parallel. `create_account` uses `PUT` with a fixed identifier, so it is safe to repeat after a timeout or crash. The `POST` steps are not repeated automatically when their outcome is unknown (see [idempotency](#at-least-once-execution-and-idempotency)).

Other reference workflows: an API health check every 5 minutes, a daily sales report (HTTP → TRANSFORM → EMAIL), a nightly product-feed sync (HTTP → TRANSFORM → HTTP PUT) and weekly KPIs (two parallel HTTP calls → TRANSFORM → EMAIL).

---

## Architecture

FlowForge is a **modular monolith**: one Angular single-page application, one Spring Boot application and one PostgreSQL database. The execution engine runs inside the Spring Boot application as background components.

```text
 ┌──────────────┐
 │ Angular SPA  │
 └──────┬───────┘
        │ HTTP + JSON (JWT)
 ┌──────▼───────────────────────────────────────────────┐
 │ Spring Boot application                              │
 │                                                      │
 │  REST API        validation, ownership, transactions │
 │  Worker          poll loop + fixed thread pool       │──── HTTP jobs ───► external APIs
 │  Scheduler       cron schedules → executions         │──── EMAIL jobs ──► SMTP
 │  Recovery task   expired leases → retry or fail      │
 └──────┬───────────────────────────────────────────────┘
        │
 ┌──────▼───────────────────────────────────────────────┐
 │ PostgreSQL       system of record and job queue      │
 └──────────────────────────────────────────────────────┘
```

**The API and the worker communicate only through PostgreSQL.** The API writes executions and jobs. The worker claims jobs and records their outcomes. There are no in-memory calls or internal events between them. As a result, several application instances can run against one database and share the workload safely, without a message broker. Instances can also be split by role: API-only instances set `flowforge.worker.enabled=false`.

**Why one application instead of services.** Creating an execution and all of its jobs must be one database transaction. Splitting workflow management and execution into separate services would turn that transaction into a distributed consistency problem, with no benefit at this scale.

**Why PostgreSQL is the queue.** A job is "queued" when its `job_execution` row is `READY`. Queue state and job state therefore can't disagree, and there is no second system to keep in sync. A broker such as Kafka or RabbitMQ would become a second source of truth.

**Backend layering.** Code is organized by technical layer, with dependencies pointing one way: `controller → service → repository → entity`. Controllers exchange DTOs and never touch repositories. Services own use cases, transaction boundaries and ownership checks. Engine queries whose locking must be precise and visible (claiming, outcome recording, recovery) use explicit SQL rather than JPA dirty checking. Flyway owns the schema, and Hibernate only validates it.

---

## Execution engine

The engine is implemented directly in the application rather than on a job framework such as JobRunr or Quartz. Claiming, retry, recovery and dependency semantics are workflow-specific, so they are kept explicit and fully under the application's control.

It follows five principles:

1. **PostgreSQL is the queue.**
2. **Handlers do the work, the engine does the bookkeeping.** A handler (for example `HttpJobHandler`) performs the call and returns a result value. Only the engine changes job and execution state.
3. **No database transaction is open while a job does I/O.** Every attempt consists of short transactions around the work, never during it.
4. **Every state change is conditional.** Updates say `WHERE status = 'RUNNING' AND attempt_count = 2`, not just `WHERE id = ?`, so a late or duplicate update changes nothing.
5. **Time comes from the database.** Leases and retry times use PostgreSQL's `now()`, so machines with slightly different clocks still agree.

**Lifecycle of an execution**

```text
POST /executions ──► create execution + one job per step (snapshot)      ┐ one transaction
                     roots → READY, others → PENDING                     ┘
                            │
Worker poll ──────► claim READY jobs (FOR UPDATE SKIP LOCKED) → RUNNING    transaction 1
                            │
                            ▼
                     run handler (HTTP call, delay, …), bounded by timeout  no transaction
                            │
                            ▼
                     record outcome under the execution-row lock:          transaction 2
                       SUCCEEDED      → release dependents (PENDING → READY)
                       retryable      → READY again with a later available_at
                       final failure  → FAILED, everything downstream → SKIPPED
                       nothing left waiting or running → execution SUCCEEDED / FAILED
```

The worker is one poll thread plus a fixed pool of 4 job threads. It polls every second and also immediately after a job finishes, claiming at most as many jobs as it has free threads. A `DELAY` job never blocks a thread: its `available_at` is simply in the future, so it isn't claimable until then. A three-day delay costs nothing but a timestamp.

**Claiming** uses a single statement:

```sql
WITH picked AS (
    SELECT id FROM job_execution
    WHERE status = 'READY' AND available_at <= now()
    ORDER BY available_at
    LIMIT :free
    FOR UPDATE SKIP LOCKED
)
UPDATE job_execution j
SET status = 'RUNNING', locked_by = :workerId, attempt_count = j.attempt_count + 1,
    lease_expires_at = now() + make_interval(secs => j.timeout_seconds + 60)
FROM picked WHERE j.id = picked.id
RETURNING j.*;
```

`FOR UPDATE` locks the selected rows, and `SKIP LOCKED` makes a concurrent worker take other rows instead of waiting. After commit the row is `RUNNING` and no longer matches. A unique `(job_execution_id, attempt_number)` on `job_attempt` makes a double claim impossible even in the presence of a bug.

---

## Job lifecycle

A job has seven states:

| State | Meaning |
|---|---|
| `PENDING` | Waiting for its dependencies |
| `READY` | Claimable once `available_at <= now()`, whether now, after a retry delay, or after a `DELAY` |
| `RUNNING` | Claimed by a worker, with an active lease |
| `SUCCEEDED` | Finished successfully. Output stored (at most 256 KB). |
| `FAILED` | Failed and will not be retried. This is the dead letter. |
| `SKIPPED` | Can never run because an upstream job failed |
| `CANCELLED` | The execution was cancelled |

```text
                   all dependencies SUCCEEDED
   ┌─────────┐ ─────────────────────────────► ┌───────┐  claimed   ┌─────────┐ ──► SUCCEEDED
   │ PENDING │                                │ READY │ ─────────► │ RUNNING │ ──► FAILED
   └─────────┘                                └───────┘ ◄───────── └─────────┘ ──► CANCELLED
        │ a dependency FAILED / SKIPPED            retryable failure, attempts left
        ▼                                          (available_at = now + backoff)
     SKIPPED            PENDING / READY ──► CANCELLED when the execution is cancelled
```

There is deliberately no `RETRYING` state: a job waiting to retry is `READY` with a future `available_at`, which keeps a single claimable state. Allowed transitions are defined on the status enums and backed by database `CHECK` constraints. One example: a `RUNNING` job must have both a lease and an owner.

An **execution** is `RUNNING`, `SUCCEEDED`, `FAILED` or `CANCELLED`. An **attempt** is `RUNNING`, `SUCCEEDED`, `FAILED` or `ABANDONED`, where `ABANDONED` means the worker disappeared and the outcome is unknown.

---

## Dependencies and concurrency

**Cycle detection.** A cycle can't be expressed as a database constraint because it is a property of the whole graph. `WorkflowGraph` (plain Java, depth-first search) checks every dependency change and returns the cycle path (`a → b → a`) in the error. Two concurrent edits that are each valid could together form a cycle (write skew). To prevent this, the service locks the workflow row (`SELECT … FOR UPDATE`) while it validates and saves dependencies. The database itself guarantees that dependencies stay within one workflow (composite foreign keys) and that a step can't depend on itself.

**Parallel execution.** Roots start immediately. Independent branches of the same execution run concurrently on different worker threads, or on different application instances.

**The lost-wakeup race.** Suppose D depends on B and C, and B and C finish at the same instant on two threads. Under `READ COMMITTED`, each transaction sees the other dependency as still `RUNNING`, so neither releases D, and the execution would hang forever. FlowForge prevents this with **execution-row locking**: every outcome transaction starts with

```sql
SELECT id, status FROM workflow_execution WHERE id = :executionId FOR UPDATE;
```

Updates within one execution are therefore serialized for a few milliseconds each, while different executions proceed fully in parallel. The same lock makes completion detection and cancellation race-free. Lock order is always execution row, then job rows, and the claim query never waits, so it can't take part in a deadlock.

**Completion.** After every recorded outcome, still under the lock, the engine counts active and failed jobs. When nothing is `PENDING`, `READY` or `RUNNING`, the execution becomes `SUCCEEDED`, or `FAILED` if any job failed. No background process polls for completion: whichever transaction finishes the last job also finishes the execution.

---

## Retries and failure handling

Each step configures `timeoutSeconds` (1–300, default 30), `maxAttempts` (1–10, default 3) and `retryDelaySeconds` (1–3600, default 10). Every attempt is bounded by the step's timeout, and the handler is interrupted when it expires.

| Error | Retryable |
|---|---|
| Connection error, HTTP 5xx, HTTP 429 | Yes |
| Timeout, lease expired | Only if the step is safe to repeat |
| HTTP 4xx, invalid configuration, missing placeholder value, output over 256 KB | No |
| Unexpected handler exception | Yes, bounded by `maxAttempts` |

**Exponential backoff:**

```text
delay after attempt n = min(retryDelaySeconds × 2^(n−1), 600 s)
```

With the defaults, a job is retried after 10 s and then after 20 s. When `maxAttempts` is exhausted, the job becomes `FAILED`, every job downstream of it becomes `SKIPPED`, and independent branches keep running. Once nothing is left waiting or running, the execution becomes `FAILED` with a summary such as `create_crm_contact failed after 3 attempts: HTTP 503`. All attempts, with timestamps, errors and worker, remain visible. **Run again** starts a new execution with the same input.

---

## Crash recovery and leases

If the application stops while a job is `RUNNING`, nobody will ever report its outcome. To handle this, **every claim is a lease**: `lease_expires_at = now() + timeoutSeconds + 60 s`. A healthy worker always finishes or times out before its lease expires.

The **recovery task** runs every 30 seconds in every instance. It finds `RUNNING` jobs with an expired lease and, for each one, in a transaction under the execution-row lock:

1. re-checks that the job is still `RUNNING` with the same `attempt_count`
2. marks the attempt `ABANDONED` (`LEASE_EXPIRED`)
3. makes the job `READY` with backoff if it is safe to repeat and has attempts left, `CANCELLED` if the execution was cancelled, and `FAILED` otherwise, with "outcome unknown" for unsafe steps

Abandoned attempts count against `maxAttempts`, so a job that crashes its worker every time can't loop forever. A job never remains `RUNNING` indefinitely: it is recovered within its timeout plus about 90 seconds. Because every attempt has a hard timeout of at most five minutes, a lease can cover the whole attempt, so no heartbeat thread is needed.

**Fencing.** The attempt number is a fencing token. Outcomes are recorded conditionally:

```sql
UPDATE job_execution SET status = 'SUCCEEDED', output = :output, finished_at = now()
WHERE id = :jobId AND status = 'RUNNING' AND attempt_count = :attemptNumber;
```

A worker that was only frozen, not dead, may report attempt 1 after the job has already been retried as attempt 2. Its update matches zero rows, so the late result is noted on the old attempt and changes nothing else.

---

## At-least-once execution and idempotency

"Doing the action" happens in an external system, while "recording that it was done" happens in PostgreSQL. No transaction spans both, so a crash can always fall between them. FlowForge therefore provides **at-least-once execution with exactly-once result recording**: a job may run more than once, but its result is recorded exactly once, thanks to fencing.

Making the side effect itself happen only once needs cooperation from the target system. Three measures handle this:

| Measure | How |
|---|---|
| **Safe-to-repeat classification** | `GET`, `HEAD`, `PUT` and `DELETE` are repeatable by HTTP semantics, and `DELAY` and `TRANSFORM` have no external effects. `POST`, `PATCH` and `EMAIL` are not safe to repeat. |
| **No automatic repeat after an unknown outcome** | After a timeout or an expired lease, an unsafe step goes straight to `FAILED` with "outcome unknown: check the target system before running again". Guessing wrong could mean two emails or two payments, so a human decides. |
| **`Idempotency-Key` on outgoing requests** | Every HTTP job request carries `Idempotency-Key: <jobExecutionId>`, identical on every attempt, so APIs that honor idempotency keys execute it only once. |

Step design matters too: prefer "set" over "add". For example, use `PUT /accounts/{{execution.id}}` rather than `POST /accounts`.

**Duplicate execution requests** are a separate problem, solved at the API level. `POST /api/workflows/{id}/executions` accepts an optional `Idempotency-Key` header, which is stored as `dedup_key`, and a unique constraint on `(workflow_id, dedup_key)` makes a second insert impossible. A double-clicked Run button returns the existing execution, including when the two requests arrive concurrently.

---

## Cancellation

`POST /api/executions/{id}/cancel` runs in one transaction under the execution-row lock:

- the execution becomes `CANCELLED`
- all `PENDING` and `READY` jobs become `CANCELLED` immediately, including a `DELAY` that is waiting for its time
- `RUNNING` jobs are not interrupted. They complete their current attempt, and when the outcome is recorded the engine sees the cancelled execution, marks the job `CANCELLED` and releases nothing downstream.

The trade-off is deliberate: for a short time the execution shows `CANCELLED` while a single in-flight HTTP call finishes. A cancel racing with a claim resolves consistently in either order, because both operate on locked rows.

---

## Scheduling

A workflow can have any number of schedules. Each schedule has a 5-field cron expression, an **IANA time zone**, a JSON input and an enabled flag. A schedule stores its `next_run_at`, so "what is due?" is a single indexed range query instead of evaluating every cron expression.

The scheduler runs every 15 seconds in every instance and processes due schedules one at a time, each in its own transaction:

```sql
SELECT s.* FROM workflow_schedule s JOIN workflow w ON w.id = s.workflow_id
WHERE s.enabled AND s.next_run_at <= now() AND w.status = 'ACTIVE'
ORDER BY s.next_run_at LIMIT 1
FOR UPDATE OF s SKIP LOCKED;
```

- **Exactly one execution per due time.** `SKIP LOCKED` keeps two instances from processing the same schedule, and `dedup_key = schedule:<id>:<dueAt>` rejects a second execution for the same due time even if something slips through.
- **No overlap.** If the workflow already has a `RUNNING` execution, the scheduled run is skipped.
- **After downtime**, missed runs are not replayed one by one. There is at most one catch-up run, and the next run time is computed from the current time.
- **Time zones.** The next run is calculated in the schedule's zone, so "08:00 Europe/Berlin" is correct across daylight-saving transitions.
- Schedules of workflows that aren't `ACTIVE` don't fire.

User schedules are data, so they live in the database rather than in `@Scheduled(cron = …)` annotations, which are fixed at startup, fire in every instance and forget state on restart.

---

## Security and ownership

```text
Request ─► JWT filter (valid token → user id in the security context)
        ─► Controller (@Valid DTO)
        ─► Service: repository.findByIdAndOwnerId(id, currentUserId) ── empty → 404
        ─► business logic, transaction commit
```

- **Authentication.** Accounts are created with email (unique, case-insensitive), password and display name. Passwords are hashed with **bcrypt**. Login returns a stateless **JWT** (HS256, 60-minute expiry) that is sent as `Authorization: Bearer <token>`. The signing secret comes from the environment, and the application refuses to start without one. Every endpoint except registration, login and health checks requires a valid token.
- **Generic login failures.** A wrong password and an unknown email return the same `401 INVALID_CREDENTIALS`, and an unknown email still costs a bcrypt comparison, so neither the response nor its timing reveals whether an account exists.
- **Ownership-based authorization.** Every lookup includes the owning user, so another user's resource is indistinguishable from a missing one (`404`). Ownership lives on the project, and everything else is owned through it: workflows through their project, executions through their workflow, jobs through their execution. There are no roles. Every user has the same capabilities on their own data.
- **Data passing is inert.** Placeholders are path lookups only, with no expression or code evaluation.
- **SSRF protection.** HTTP jobs execute user-supplied URLs, so requests to private, loopback, link-local and cloud-metadata addresses are rejected.
- **Login rate limiting** is enforced on public deployments.
- **Step credentials.** Credentials used by HTTP steps (for example an `Authorization` header) are part of the step configuration and are visible to the project owner.

Errors use Spring's `ProblemDetail` (RFC 9457) with an added stable `code`, for example `VALIDATION_ERROR` with a field list, `NOT_FOUND`, `EMAIL_TAKEN`, `VERSION_CONFLICT`, `DEPENDENCY_CYCLE` with the cycle path, or `WORKFLOW_INVALID` with a list of problems. Internal exception details are never sent to clients.

---

## Database model

Nine tables, all created and evolved exclusively through **Flyway** migrations:

```text
"user" ─┬─< project ─< workflow ─┬─< workflow_step ─< workflow_dependency (edges, same-workflow only)
        │                        ├─< workflow_schedule
        │                        └─< workflow_execution ─< job_execution ─< job_attempt
        └──── triggered ────────────^
```

| Table | Role |
|---|---|
| `"user"` | Accounts. The name is quoted because `user` is reserved in PostgreSQL. |
| `project` | Groups workflows and carries ownership |
| `workflow` | Definition, status, run-number counter, `@Version` for optimistic locking |
| `workflow_step` | Step definition: `jsonb` config validated per job type, plus retry and timeout limits |
| `workflow_dependency` | Graph edges. Composite foreign keys keep both ends in the same workflow, and a `CHECK` forbids self-edges. |
| `workflow_execution` | One run: status, trigger, input, run number, `dedup_key` |
| `job_execution` | One step within one run, with a copy of its config and `depends_on`. **Also the job queue.** |
| `job_attempt` | Immutable record of each try: worker, timestamps, error type and message |
| `workflow_schedule` | Cron expression, time zone, input, `next_run_at` |

Conventions:

- IDs are `bigint` identity columns, and timestamps are `timestamptz`.
- Enums are `varchar` with `CHECK` constraints.
- Constraint names are explicit, so a violated unique constraint maps to a precise `409`.
- Within a group, deletes cascade. Across groups they are restricted, so execution history is never deleted by accident.
- Partial indexes keep the engine's hot queries small: `(available_at) WHERE status = 'READY'` for claiming, and `(lease_expires_at) WHERE status = 'RUNNING'` for recovery.

The full schema, constraints and reasoning are in [docs/database.md](docs/database.md).

---

## Frontend

An Angular single-page application built from standalone components with lazy-loaded routes and Angular Material.

- **Screens:** login and registration; projects; the workflow editor (step list, a step form whose fields depend on the job type, a dependency picker that excludes the step's own descendants, validation messages and activation); the execution list; execution detail; job detail with its attempt history; schedules with presets and the next run time; a workflow graph view; and a dashboard of running executions, recent failures and upcoming scheduled runs.
- **State:** each page holds its state in signals inside the page component or a page-scoped service. There is no global store, because every screen owns its data.
- **API access:** one service per feature returns Observables, and components never build URLs. RxJS handles HTTP, route-parameter changes and polling.
- **Auth:** `AuthService` holds the session, `authInterceptor` attaches the bearer token to API calls and logs out on `401`, and `authGuard` protects routes.
- **Live progress:** the execution detail page polls `GET /api/executions/{id}` every 2 seconds while the execution is `RUNNING`. Polling works unchanged across multiple backend instances, because any instance can answer from the database.
- **Same-origin:** `/api` is proxied by `ng serve` in development and by Nginx in production, so no CORS configuration is needed.

---

## Technology stack

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot, Spring Data JPA / Hibernate, Bean Validation, Spring `RestClient` |
| Database | PostgreSQL, Flyway |
| Security | Spring Security (OAuth2 Resource Server, JWT), bcrypt |
| Job types | JSONata (`TRANSFORM`), Spring Mail (`EMAIL`) |
| Frontend | Angular, TypeScript, RxJS, signals, Angular Material |
| Testing | JUnit 5, AssertJ, MockMvc, Testcontainers, WireMock, Awaitility, Vitest |
| Infrastructure | Docker, Docker Compose, Nginx, Mailpit |
| CI/CD | GitHub Actions, GitHub Container Registry |

Deliberately not used: Redis, Kafka or RabbitMQ, microservices, Kubernetes, distributed lock services, event sourcing or CQRS, and NgRx. PostgreSQL row locks, leases and unique constraints cover queueing and coordination at this scale. The reasoning is in [architecture.md §10](docs/architecture.md#10-technology-decisions).

---

## Testing strategy

Correctness of the engine depends on PostgreSQL's locking semantics, so **everything touching the database runs against real PostgreSQL via Testcontainers**. H2 is never used as a substitute, because it doesn't reproduce `SKIP LOCKED` or row-locking behavior.

| Level | Tools | Covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | Graph validation (cycles, topological order, ancestors), state transitions, retry delay calculation, placeholder resolution, cron next-run calculation, including daylight-saving dates |
| Integration | Testcontainers PostgreSQL | Repositories and transactions; constraints (composite FKs, checks, unique keys); claiming, outcome recording and schedule firing under concurrency |
| API | MockMvc + Testcontainers | Authentication and ownership (`401`, `404`), validation (`400`, `422`), idempotent execution creation |
| Engine | Testcontainers + **WireMock** + **Awaitility** | Linear and diamond workflows against stub HTTP servers; retries and backoff; worker failure, lease recovery and fencing; cancellation |
| Frontend | **Vitest** | Auth interceptor and guard, services, polling, form behavior |

Representative concurrency tests:

- 200 `READY` jobs claimed by 8 concurrent threads, with no job claimed twice
- two dependencies completing at the same instant, repeated many times, with the dependent released exactly once
- the same `Idempotency-Key` sent concurrently, creating one execution
- concurrent `A→B` and `B→A` edits, never storing a cycle
- two scheduler ticks at the same moment, creating exactly one execution
- two application instances processing 30 executions, with every job succeeding exactly once

---

## Deployment and infrastructure

FlowForge runs on a single VM with Docker Compose:

```text
Browser ──► Nginx (TLS, static Angular build, /api proxy) ──► Spring Boot (API + worker + scheduler) ──► PostgreSQL
                                                                     └──► SMTP / Mailpit
```

- **Images:** the backend image is a multi-stage build with a JRE runtime and a non-root user. The frontend image builds Angular, and Nginx serves the static files and proxies `/api`.
- **Full stack:** `docker-compose.full.yml` runs Nginx, the application, PostgreSQL and Mailpit. A `demo` profile seeds a demo user and example workflows.
- **Production:** HTTPS via Let's Encrypt, secrets supplied through an `.env` file outside version control, and a nightly `pg_dump` backup.
- **Scaling out:** additional application instances share the same database. Job claiming, recovery and scheduling rely only on row locks and unique constraints, so no engine changes or leader election are needed. Each worker has the identity `hostname:pid:thread`, which is recorded on every attempt.
- **Operability:** `/actuator/health`, and job-related log lines carry `executionId`, `jobId` and `attempt` through SLF4J MDC.

---

## CI/CD

GitHub Actions:

- **CI on every push and pull request:** backend build and tests (`./mvnw verify`, with Testcontainers using the runner's Docker), frontend lint, tests and production build, and Docker image builds.
- **CD on `main`:** images are pushed to **GitHub Container Registry**, tagged with the commit SHA.
- **Deployment:** manual approval, then SSH to the VM, `docker compose pull && docker compose up -d`, then a smoke test.

---

## Repository structure

```text
FlowForge/
├── backend/                     Spring Boot application (Maven wrapper)
│   └── src/main/java/com/flowforge/
│       ├── entity/              JPA entities and status enums
│       ├── repository/          Spring Data repositories and engine SQL
│       ├── service/             use cases, transactions, ownership checks
│       ├── controller/          REST controllers (HTTP mapping, DTO validation)
│       ├── security/            JWT, security configuration, current user
│       ├── exception/           ProblemDetail error handling and error codes
│       ├── dto/                 request and response records
│       └── config/              application configuration
│   └── src/main/resources/db/migration/   Flyway migrations
├── frontend/                    Angular application
│   └── src/app/
│       ├── core/                auth service, interceptor, guard, layout
│       ├── shared/              reusable components and pipes
│       └── features/            auth, projects, workflows, executions, schedules
├── docs/                        Design documentation
├── .github/workflows/           CI/CD pipelines
├── docker-compose.yml           Local development services
└── README.md
```

---

## Running locally

**Prerequisites:** Java 21 or newer, Node.js 24 (or 22 LTS), Docker.

```bash
# 1. Start the development services (PostgreSQL on localhost:5432)
docker compose up -d

# 2. Create backend/.env from the template and set FLOWFORGE_JWT_SECRET
#    (for example: openssl rand -base64 48)
cp backend/.env.example backend/.env

# 3. Start the backend on http://localhost:8080
cd backend
./mvnw spring-boot:run

# 4. Start the frontend on http://localhost:4200
cd frontend
npm install
npm start
```

Open `http://localhost:4200` and create an account. The dev server proxies `/api` and `/actuator` to the backend.

**Configuration**

The backend reads these variables from `backend/.env` when that file exists. `backend/.env` is gitignored and must never be committed, and `backend/.env.example` is the committed template. Without the file, for example on a server, set them as real environment variables.

| Variable | Purpose |
|---|---|
| `FLOWFORGE_JWT_SECRET` | JWT signing key (at least 32 bytes). Required. |
| `FLOWFORGE_DB_URL`, `FLOWFORGE_DB_USERNAME`, `FLOWFORGE_DB_PASSWORD` | Database connection. Defaults match `docker-compose.yml`. |

Engine settings live under `flowforge.*`: worker threads, poll interval, lease grace, recovery and scheduler intervals, and limits such as 30 steps per workflow and 256 KB of job output.

**Tests** (Docker must be running for Testcontainers):

```bash
cd backend && ./mvnw verify
cd frontend && npm test -- --watch=false && npm run build
```

---

## Design principles

- **One source of truth.** Job state and queue state live in the same PostgreSQL rows and change in the same transactions.
- **Correctness through the database, not through luck.** Row locks, `SKIP LOCKED`, unique constraints, `CHECK` constraints and conditional updates enforce the rules even when two instances race or a bug slips through.
- **Short transactions, no I/O inside them.** External calls never hold locks or connections.
- **Explicit over magical.** Engine SQL is written out, and state transitions are enumerated. Graph and retry logic are plain, unit-tested Java.
- **Honest about distributed-systems limits.** Execution is at-least-once, recording is exactly-once, and anything with an unknown outcome that isn't safe to repeat is surfaced to a human instead of guessed.
- **Snapshots over shared mutable definitions.** A running execution never observes later edits.
- **Simple infrastructure.** One application, one database, one VM, with no broker, cache or orchestrator until a measured need exists.
- **Deliberately out of scope:** teams and sharing, inbound webhooks, loops and dynamic fan-out, sub-workflows, and script or SQL job types.

---

## Documentation

The `docs/` directory is the detailed specification of the system:

| Document | Contents |
|---|---|
| [FRS.md](docs/FRS.md) | Purpose, scope, user stories, functional and non-functional requirements, example workflows |
| [architecture.md](docs/architecture.md) | System structure, backend layering, data access, security, frontend, deployment, technology decisions |
| [database.md](docs/database.md) | Tables, ER diagram, constraints, indexes, ownership queries, migrations |
| [api.md](docs/api.md) | REST endpoints, request and response formats, error format and status codes |
| [execution-engine.md](docs/execution-engine.md) | State machines, claiming, concurrency, retries, idempotency, leases, cancellation, scheduling, failure scenarios |
| [roadmap.md](docs/roadmap.md) | Definition of done and testing strategy |
