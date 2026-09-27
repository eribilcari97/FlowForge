# FlowForge — Functional Requirements Specification

| | |
|---|---|
| Related | [architecture.md](architecture.md) · [database.md](database.md) · [api.md](api.md) · [execution-engine.md](execution-engine.md) · [roadmap.md](roadmap.md) |

---

## 1. Purpose

FlowForge runs multi-step background processes reliably. A user defines a process once as a **workflow**: a set of **steps** (call an HTTP API, wait, transform data, send an email) connected by **dependencies**. FlowForge then runs it manually or on a schedule. It executes the steps in dependency order, runs independent steps in parallel, retries failures, records every attempt, and shows progress in a web UI.

```text
        A                A = fetch orders
       / \               B = calculate revenue      C = count refunds
      B   C              D = send report (after B and C have succeeded)
       \ /
        D
```

**Target user:** a developer or technical user who wants to automate small recurring processes (reports, API synchronization, health checks, onboarding sequences) without writing and hosting a separate script for each.

---

## 2. Concepts

| Concept | Meaning | Example |
|---|---|---|
| **Project** | Container owned by one user. Groups workflows. | "Acme Shop Ops" |
| **Workflow** | Definition of a process: steps plus dependencies. It doesn't run by itself. | "Daily Sales Report" |
| **Step** | Node of a workflow: job type, configuration, timeout, retry settings | `fetch_orders`: HTTP GET … |
| **Dependency** | Step B may start only after step A has succeeded | `calculate_revenue` depends on `fetch_orders` |
| **Execution** | One run of a workflow, with its own input, status and run number | "Daily Sales Report, run #58" |
| **Job** | One step within one execution. The unit the worker runs. | `fetch_orders` in run #58 |
| **Attempt** | One try at running a job | attempt 2 of `fetch_orders` |
| **Schedule** | Cron rule that creates executions automatically | daily 08:00, Europe/Berlin |
| **Worker** | Background component in the backend that claims and runs jobs | — |

Workflows and steps are definitions. Executions, jobs and attempts are runtime records. When an execution starts, each step is copied into a job, so later edits to the workflow don't affect runs that already exist.

---

## 3. Scope

### 3.1 MVP

- Registration, login, JWT authentication, ownership-based access
- Projects, workflows, steps, dependencies with cycle detection, activation
- Job types: **HTTP** and **DELAY**
- Data passing between steps through placeholders (`{{steps.fetch_orders.output.body.id}}`)
- Manual execution with JSON input, execution history, job and attempt details
- Execution engine inside the backend: job claiming, dependency resolution, completion detection
- Retries with exponential backoff, attempt timeouts, crash recovery
- Execution cancellation
- Cron schedules
- Angular UI for all of the above, with execution status refreshed by polling

### 3.2 Later

These are designed so they can be added without changing the MVP architecture.

- TRANSFORM job type (JSONata) and EMAIL job type (SMTP, Mailpit in development)
- Workflow graph view and dashboard
- Running multiple application instances
- Server-Sent Events instead of polling
- Resuming a failed execution from the failed job
- Step conditions ("run only if …")
- Encrypted project secrets
- Refresh tokens, admin role, audit log, rate limiting

### 3.3 Out of scope

Teams and sharing, webhook triggers from external systems, loops and dynamic fan-out, sub-workflows, script or SQL job types, file storage, high availability, microservices, message brokers, Kubernetes.

---

## 4. User stories

| # | As a user I want to … | So that … |
|---|---|---|
| US-1 | register and log in | my workflows are private |
| US-2 | create projects | I can group related workflows |
| US-3 | create a workflow with HTTP and delay steps | I can describe a process once |
| US-4 | declare dependencies between steps | steps run in the correct order |
| US-5 | be told immediately when dependencies form a cycle | I never create a workflow that can't finish |
| US-6 | configure timeout and retries per step | transient failures don't break the process |
| US-7 | use the output of an earlier step in a later step | steps can build on each other |
| US-8 | run a workflow manually with JSON input | I can start it on demand |
| US-9 | follow an execution step by step | I know what is happening |
| US-10 | see every attempt of a job with its error | I can diagnose failures |
| US-11 | cancel a running execution | I can stop a mistaken run |
| US-12 | schedule a workflow with cron | it runs without manual action |
| US-13 | see the history of executions | I can check past runs |

---

## 5. Functional requirements

### 5.1 Accounts and access

| ID | Requirement | Scope |
|---|---|---|
| FR-01 | Register with email (unique, case-insensitive), password (10–72 characters, at most 72 bytes in UTF-8, because bcrypt reads only the first 72 bytes) and display name. Passwords are stored with bcrypt. | MVP |
| FR-02 | Log in with email and password and receive a JWT access token valid for 60 minutes. Invalid credentials produce one generic error. | MVP |
| FR-03 | Every API endpoint except registration, login and health requires a valid token. | MVP |
| FR-04 | A user can read and modify only resources in their own projects. Another user's resource returns `404`, like a nonexistent one. | MVP |
| FR-05 | Admin role (list users, view all executions). | Later |
| FR-06 | Refresh tokens so sessions survive browser reloads. | Later |

There are no roles in the MVP. All users have the same capabilities on their own data, so access control is ownership-based.

### 5.2 Projects and workflows

| ID | Requirement | Scope |
|---|---|---|
| FR-10 | Create, list, rename and delete projects. A project can be deleted only when it contains no workflows. | MVP |
| FR-11 | Create, list, view, rename and delete workflows. A workflow that has executions is archived instead of deleted, preserving history. | MVP |
| FR-12 | Workflow status: `DRAFT` (editable, not runnable), `ACTIVE` (runnable and schedulable), `ARCHIVED` (read-only). `ACTIVE` can return to `DRAFT`. | MVP |
| FR-13 | Activation validates the workflow: at least one step, no cycles, valid step configurations, placeholders referencing only upstream steps. On failure the response lists the problems. | MVP |
| FR-14 | Updates to workflow metadata use optimistic locking. A stale update is rejected with `409`. | MVP |

### 5.3 Steps and dependencies

| ID | Requirement | Scope |
|---|---|---|
| FR-20 | Steps have a `key` (`^[a-z][a-z0-9_]{0,49}$`, unique within the workflow, immutable), a name, a job type (immutable), a JSON configuration, `timeoutSeconds` (1–300, default 30), `maxAttempts` (1–10, default 3) and `retryDelaySeconds` (1–3600, default 10). | MVP |
| FR-21 | Configuration is validated per job type (for example, HTTP requires a method and an absolute `http`/`https` URL). | MVP |
| FR-22 | A step's dependencies are set by replacing its full list of upstream steps. | MVP |
| FR-23 | Dependencies must reference steps of the same workflow and cannot reference the step itself. The database enforces both. | MVP |
| FR-24 | A change that would create a cycle is rejected, and the error includes the cycle path (`a → b → a`). | MVP |
| FR-25 | A step that other steps depend on cannot be deleted until those dependencies are removed. | MVP |
| FR-26 | A workflow has at most 30 steps. | MVP |
| FR-27 | Editing an `ACTIVE` workflow is allowed. Existing executions are unaffected because they operate on copies of the steps. | MVP |

### 5.4 Job types

| ID | Requirement | Scope |
|---|---|---|
| FR-30 | **HTTP**: method, URL, headers, optional JSON body, expected status codes. Output: status, selected headers, body (JSON or text), duration. | MVP |
| FR-31 | **DELAY**: waits for a duration of 1 second to 7 days without occupying a worker thread. | MVP |
| FR-32 | **TRANSFORM**: computes a JSON value from input and upstream outputs using a JSONata expression. | Later |
| FR-33 | **EMAIL**: sends an email (recipients, subject, text or HTML body) via SMTP. | Later |
| FR-34 | Job output is limited to 256 KB. Larger output fails the job. | MVP |

### 5.5 Data passing

| ID | Requirement | Scope |
|---|---|---|
| FR-40 | String values in a step's configuration may contain placeholders: `{{input.<path>}}`, `{{steps.<key>.output.<path>}}`, `{{execution.id}}`, `{{execution.runNumber}}`. | MVP |
| FR-41 | Placeholders may reference only upstream steps (direct or transitive). This is validated at activation. | MVP |
| FR-42 | A placeholder whose value is missing at run time fails the job with a non-retryable error. | MVP |

### 5.6 Executions

| ID | Requirement | Scope |
|---|---|---|
| FR-50 | Run an `ACTIVE` workflow with optional JSON input. The response is `202 Accepted` with the execution in status `RUNNING`. | MVP |
| FR-51 | A request repeating the same `Idempotency-Key` header returns the existing execution instead of creating another. | MVP |
| FR-52 | Executions have a sequential run number per workflow. | MVP |
| FR-53 | List executions per workflow, and all of a user's executions filtered by status. View an execution with all its jobs. | MVP |
| FR-54 | View a job with the configuration used by the run, its output, its last error and all of its attempts (timestamps, errors, worker). | MVP |
| FR-55 | Cancel a running execution. Jobs that haven't started are cancelled immediately. A running job completes its current attempt, but nothing downstream starts. | MVP |
| FR-56 | Start a new execution with the same input as an existing one ("run again"). | MVP |
| FR-57 | Resume a failed execution from the failed job. | Later |

### 5.7 Execution behavior

| ID | Requirement | Scope |
|---|---|---|
| FR-60 | A job starts only after all of its dependencies have succeeded. Jobs without dependencies start immediately. | MVP |
| FR-61 | Independent jobs of the same execution may run concurrently. | MVP |
| FR-62 | A job is never executed by two workers at the same time. | MVP |
| FR-63 | When a job fails permanently, all jobs downstream of it are `SKIPPED`. Independent branches continue. | MVP |
| FR-64 | An execution ends when no job is waiting or running: `SUCCEEDED` if all jobs succeeded, otherwise `FAILED`. | MVP |
| FR-65 | A failed attempt with a retryable error (connection error, HTTP 5xx or 429; a timeout only if the step is safe to repeat, see [execution-engine.md §8](execution-engine.md#8-idempotency)) is retried after a delay of `retryDelaySeconds × 2^(n−1)`, capped at 10 minutes, until `maxAttempts` is reached. | MVP |
| FR-66 | A non-retryable error (HTTP 4xx, invalid configuration, missing placeholder value) fails the job immediately. | MVP |
| FR-67 | Each attempt is bounded by the step's timeout. | MVP |
| FR-68 | If the backend stops while a job is running, the job is recovered automatically (retried, or failed when repeating is unsafe) within the step's timeout plus about 90 seconds. A job never remains `RUNNING` indefinitely. | MVP |

### 5.8 Scheduling

| ID | Requirement | Scope |
|---|---|---|
| FR-70 | A workflow can have cron schedules: 5-field cron expression, IANA time zone, JSON input, enabled flag. | MVP |
| FR-71 | Each due time produces at most one execution, even with several backend instances. | MVP |
| FR-72 | If the workflow already has a running execution, the scheduled run is skipped. | MVP |
| FR-73 | After downtime, missed runs are not replayed individually. At most one catch-up run occurs, and the next run time is calculated from the current time. | MVP |
| FR-74 | Schedules of workflows that aren't `ACTIVE` don't fire. | MVP |

### 5.9 User interface

| ID | Requirement | Scope |
|---|---|---|
| FR-80 | Screens: login and registration, projects, workflow editor (steps, dependencies, activation), execution list, execution detail, job detail, schedules. | MVP |
| FR-81 | The execution detail page refreshes every 2 seconds while the execution is `RUNNING`. | MVP |
| FR-82 | Workflow graph view and a dashboard of recent executions. | Later |

---

## 6. Non-functional requirements

| ID | Area | Requirement |
|---|---|---|
| NFR-R1 | Reliability | No claimed job is lost after a crash. Every claimed job is eventually completed, retried or failed. |
| NFR-R2 | Reliability | Execution is at-least-once: after a crash a job may run twice. Its result is recorded exactly once. |
| NFR-C1 | Consistency | Every state change is a single database transaction. |
| NFR-P1 | Performance | A ready job starts within about 2 seconds when a worker thread is free. API reads respond in under 200 ms at expected data volumes. |
| NFR-S1 | Security | bcrypt password hashing, JWT on every protected request, ownership checks in every query. |
| NFR-S2 | Security | Placeholders are path lookups only. No expression or code evaluation. |
| NFR-S3 | Security | ⚠ Known MVP limitation: credentials used by HTTP steps are stored in step configuration, unencrypted and visible to the owner. Encrypted secrets are planned. |
| NFR-S4 | Security | Before public deployment, HTTP jobs must be prevented from calling private, loopback, link-local and cloud-metadata addresses (SSRF). |
| NFR-M1 | Maintainability | Backend organized by layer package. Schema changes only through Flyway migrations. |
| NFR-T1 | Testability | Graph validation, state transitions and retry calculation are plain Java, unit-tested. Locking and constraints are tested against real PostgreSQL (Testcontainers). |
| NFR-O1 | Operability | Job-related log lines include `executionId` and `jobId`. `/actuator/health` is available. |
| NFR-D1 | Deployment | Local development requires Java, Node and Docker (for PostgreSQL). The full stack runs with Docker Compose. |

---

## 7. Example workflows

| # | Workflow | Steps | Requires |
|---|---|---|---|
| 1 | **API health check** (every 5 minutes) | `check_api`: HTTP GET `/health`, 3 attempts, 5 s timeout. A failing API shows up as a `FAILED` execution in the history. | MVP |
| 2 | **Customer onboarding** (manual, input `{customer: {...}}`) | `create_crm_contact` (HTTP POST) and `create_account` (HTTP PUT) in parallel → `wait_1_day` (DELAY) → `notify_crm` (HTTP POST using both results) | MVP |
| 3 | **Daily sales report** (08:00) | `fetch_orders` (HTTP) → `calculate_revenue` (TRANSFORM) → `send_report` (EMAIL) | TRANSFORM, EMAIL |
| 4 | **Product feed sync** (nightly) | `fetch_products` (HTTP) → `filter_products` (TRANSFORM) → `upload_feed` (HTTP PUT) | TRANSFORM |
| 5 | **Weekly KPIs** (Mondays) | `fetch_signups` and `fetch_revenue` (HTTP, parallel) → `compute_kpis` (TRANSFORM) → `email_kpis` (EMAIL) | TRANSFORM, EMAIL |

Data flow of example 2:

```text
input = { "customer": { "email": "jane@example.com", "plan": "PRO" } }

create_crm_contact  POST https://crm.example.com/api/contacts
                    body {"email": "{{input.customer.email}}"}
                    → output.body = { "id": "crm_881" }

create_account      PUT https://accounts.example.com/api/accounts/{{execution.id}}
                    body {"email": "{{input.customer.email}}", "plan": "{{input.customer.plan}}"}
                    → output.body = { "accountNumber": "ACME-104" }

wait_1_day          DELAY P1D   (depends on both; starts when both have succeeded)

notify_crm          POST https://crm.example.com/api/contacts/{{steps.create_crm_contact.output.body.id}}/notes
                    body {"text": "Account {{steps.create_account.output.body.accountNumber}} active"}
```

`create_account` uses PUT with a fixed identifier, so it can be repeated safely after a timeout or crash. The two POST steps are not repeated automatically when their outcome is unknown ([execution-engine.md §8](execution-engine.md#8-idempotency)).

---

## 8. Open decisions

| Decision | Current preference |
|---|---|
| Expression language for TRANSFORM | JSONata. It supports arithmetic and date functions, which JMESPath lacks. The Java library is validated in a spike before adoption. |
| Keeping `Project` as a separate entity | Keep. It is cheap and gives ownership a single anchor. |

JWT-related decisions are listed in [architecture.md §11](architecture.md#11-open-decisions).
