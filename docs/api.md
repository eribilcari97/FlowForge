# FlowForge — REST API

| | |
|---|---|
| Base path | `/api` |
| Format | JSON. Errors use `application/problem+json`. |
| Related | [FRS.md](FRS.md) · [database.md](database.md) · [execution-engine.md](execution-engine.md) |

---

## 1. Endpoint overview

Only the endpoints the application actually needs. **A** = requires a JWT. **O** = also requires ownership of the resource (otherwise `404`).

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/api/auth/register` | Create an account | — |
| POST | `/api/auth/login` | Get a JWT | — |
| GET | `/api/auth/me` | Current user | A |
| GET | `/api/projects` | My projects | A |
| POST | `/api/projects` | Create a project | A |
| GET | `/api/projects/{projectId}` | Project details | A+O |
| PUT | `/api/projects/{projectId}` | Rename / describe | A+O |
| DELETE | `/api/projects/{projectId}` | Delete (only if empty) | A+O |
| GET | `/api/projects/{projectId}/workflows` | Workflows of a project | A+O |
| POST | `/api/projects/{projectId}/workflows` | Create a workflow (`DRAFT`) | A+O |
| GET | `/api/workflows/{workflowId}` | Workflow with steps and dependencies | A+O |
| PUT | `/api/workflows/{workflowId}` | Update name / description | A+O |
| DELETE | `/api/workflows/{workflowId}` | Delete, or archive if it has executions | A+O |
| POST | `/api/workflows/{workflowId}/steps` | Add a step | A+O |
| PUT | `/api/workflows/{workflowId}/steps/{stepId}` | Update a step | A+O |
| DELETE | `/api/workflows/{workflowId}/steps/{stepId}` | Remove a step | A+O |
| PUT | `/api/workflows/{workflowId}/steps/{stepId}/dependencies` | Replace a step's dependencies | A+O |
| POST | `/api/workflows/{workflowId}/activate` | Validate and activate | A+O |
| POST | `/api/workflows/{workflowId}/deactivate` | Back to `DRAFT` | A+O |
| POST | `/api/workflows/{workflowId}/executions` | Run the workflow | A+O |
| GET | `/api/workflows/{workflowId}/executions` | Run history of a workflow | A+O |
| GET | `/api/executions` | All my executions (filterable) | A |
| GET | `/api/executions/{executionId}` | Execution with its jobs | A+O |
| POST | `/api/executions/{executionId}/cancel` | Cancel | A+O |
| GET | `/api/job-executions/{jobId}` | Job with config, output and attempts | A+O |
| GET | `/api/workflows/{workflowId}/schedules` | Schedules of a workflow | A+O |
| POST | `/api/workflows/{workflowId}/schedules` | Add a schedule | A+O |
| PUT | `/api/workflows/{workflowId}/schedules/{scheduleId}` | Update, pause or resume | A+O |
| DELETE | `/api/workflows/{workflowId}/schedules/{scheduleId}` | Remove a schedule | A+O |
| GET | `/api/dashboard` | Running executions, failures of the last 24 h, upcoming scheduled runs | A |
| GET | `/actuator/health` | Health check | — |
| GET | `/api/health` | Foundation smoke test: confirms the frontend reaches the backend through `/api` | — |

About 30 endpoints. Deliberately **not** in the MVP: admin endpoints, token refresh, secrets, retrying a single failed job, streaming (SSE), audit log.

---

## 2. Conventions

| Topic | Rule |
|---|---|
| URLs | Plural nouns. Workflows, executions and jobs have their own top-level URLs because they're linked from many screens. **Steps and schedules are nested under their workflow** because they only exist inside it, and the nesting makes the ownership check obvious (check the workflow, then check the step belongs to it). |
| Actions | State changes that aren't field edits use a verb sub-resource with `POST`: `/activate`, `/deactivate`, `/cancel`. |
| IDs | Numbers (64-bit, sequential). Guessing an ID reveals nothing, because another user's resource returns `404`. |
| Timestamps | ISO-8601 UTC, e.g. `2026-09-27T06:00:00Z` |
| Auth | `Authorization: Bearer <jwt>` |
| Ownership | Someone else's resource → `404` (identical to "doesn't exist") |
| Pagination | Only on execution lists: `?page=0&size=20` → `{ "items": [...], "page": 0, "size": 20, "total": 135 }` |
| Versioning | No `/v1`. There's one client (our Angular app), deployed together with the API. |
| Validation | Bean Validation → `400`. Unknown JSON fields are rejected (catches typos in step configs). |

### 2.1 Error format

Spring's built-in `ProblemDetail` (RFC 9457), plus a stable `code` the frontend can switch on:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "instance": "/api/workflows/5/steps",
  "code": "VALIDATION_ERROR",
  "errors": [
    { "field": "key", "message": "must match ^[a-z][a-z0-9_]{0,49}$" },
    { "field": "maxAttempts", "message": "must be less than or equal to 10" }
  ]
}
```

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Invalid field (has `errors[]`) |
| 400 | `MALFORMED_REQUEST` | Unreadable JSON, unknown field |
| 401 | `UNAUTHENTICATED` | Missing, invalid or expired token |
| 401 | `INVALID_CREDENTIALS` | Login failed (same message whether or not the email exists) |
| 404 | `NOT_FOUND` | Doesn't exist, or isn't yours |
| 409 | `EMAIL_TAKEN`, `DUPLICATE_NAME` | Unique constraint |
| 409 | `VERSION_CONFLICT` | Optimistic lock: someone saved first |
| 409 | `INVALID_STATE` | For example, running a `DRAFT` workflow, cancelling a finished execution, editing an archived workflow |
| 409 | `STEP_HAS_DEPENDENTS`, `PROJECT_NOT_EMPTY` | Delete blocked |
| 422 | `DEPENDENCY_CYCLE` | Has `cycle: ["a","b","a"]` |
| 422 | `WORKFLOW_INVALID` | Activation failed. Has `problems[]`. |
| 422 | `STEP_LIMIT_REACHED` | Adding a step to a workflow that already has 30 steps |
| 500 | `INTERNAL_ERROR` | Details only in the logs |

`type` is omitted, which RFC 9457 defines as `about:blank`. Framework errors without a code in the table (for example `405`) use the HTTP status name as `code` (`METHOD_NOT_ALLOWED`).

**400 vs 422:** `400` means the request itself is malformed. `422` means the request is well-formed but breaks a rule about the workflow as a whole.

---

## 3. Authentication

### `POST /api/auth/register`

```json
{ "email": "ana@example.com", "password": "correct-horse-battery", "displayName": "Ana" }
```

Validation: valid email ≤ 254 chars; password at least 10 chars and at most 72 bytes in UTF-8 (the bcrypt input limit); display name 1–100 chars.
**201** → `{ "id": 1, "email": "ana@example.com", "displayName": "Ana" }` · **400** · **409** `EMAIL_TAKEN`

### `POST /api/auth/login`

```json
{ "email": "ana@example.com", "password": "correct-horse-battery" }
```

**200**

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9…", "expiresIn": 3600, "user": { "id": 1, "email": "ana@example.com", "displayName": "Ana" } }
```

**401** `INVALID_CREDENTIALS`

### `GET /api/auth/me` → **200** user · **401**

---

## 4. Projects

### `POST /api/projects`

```json
{ "name": "Acme Shop Ops", "description": "Reports and monitoring" }
```

Name 1–100 chars, unique per user. **201** + `Location`:

```json
{ "id": 7, "name": "Acme Shop Ops", "description": "Reports and monitoring", "workflowCount": 0, "createdAt": "2026-09-27T09:01:00Z" }
```

**400** · **409** `DUPLICATE_NAME`

- `GET /api/projects` → **200** array of projects (small list, not paginated)
- `GET /api/projects/{id}` → **200** · **404**
- `PUT /api/projects/{id}` (same body) → **200** · **400** · **404** · **409**
- `DELETE /api/projects/{id}` → **204** · **404** · **409** `PROJECT_NOT_EMPTY`

---

## 5. Workflows, steps and dependencies

### `POST /api/projects/{projectId}/workflows`

`{ "name": "Customer onboarding", "description": "…" }` → **201** workflow in `DRAFT` with no steps.

`GET /api/projects/{projectId}/workflows` → **200** array of workflow summaries (`id`, `projectId`, `name`, `description`, `status`, `createdAt`, `updatedAt`, without steps), sorted by name · **404**

### `GET /api/workflows/{workflowId}`

```json
{
  "id": 5,
  "projectId": 7,
  "name": "Customer onboarding",
  "description": "Creates CRM contact and account, then follows up",
  "status": "ACTIVE",
  "version": 4,
  "steps": [
    {
      "id": 11,
      "key": "create_crm_contact",
      "name": "Create CRM contact",
      "jobType": "HTTP",
      "config": {
        "method": "POST",
        "url": "https://crm.example.com/api/contacts",
        "headers": { "Authorization": "Bearer crm_live_…" },
        "body": { "email": "{{input.customer.email}}" },
        "expectedStatus": [200, 201]
      },
      "timeoutSeconds": 30,
      "maxAttempts": 3,
      "retryDelaySeconds": 10,
      "dependsOn": []
    },
    {
      "id": 14,
      "key": "wait_1_day",
      "name": "Wait one day",
      "jobType": "DELAY",
      "config": { "duration": "P1D" },
      "timeoutSeconds": 30,
      "maxAttempts": 3,
      "retryDelaySeconds": 10,
      "dependsOn": [11, 12]
    }
  ],
  "createdAt": "2026-09-20T10:00:00Z",
  "updatedAt": "2026-09-26T16:42:10Z"
}
```

(`dependsOn` holds step IDs. Other steps omitted.)

### `PUT /api/workflows/{workflowId}`

```json
{ "name": "Customer onboarding", "description": "…", "version": 4 }
```

`version` must match the stored one (optimistic locking). **200** (with the new version) · **404** · **409** `VERSION_CONFLICT` · **409** `INVALID_STATE` (archived)

### `DELETE /api/workflows/{workflowId}`

**204**. The workflow is deleted if it has never run, otherwise it's set to `ARCHIVED`. **404**.

### `POST /api/workflows/{workflowId}/steps`

```json
{
  "key": "create_account",
  "name": "Create account",
  "jobType": "HTTP",
  "config": {
    "method": "PUT",
    "url": "https://accounts.example.com/api/accounts/{{execution.id}}",
    "body": { "email": "{{input.customer.email}}", "plan": "{{input.customer.plan}}" }
  },
  "timeoutSeconds": 30,
  "maxAttempts": 3,
  "retryDelaySeconds": 10
}
```

| Field | Rule |
|---|---|
| `key` | `^[a-z][a-z0-9_]{0,49}$`, unique in the workflow, can't be changed later |
| `jobType` | `HTTP` or `DELAY` (later also `TRANSFORM`, `EMAIL`), can't be changed later |
| `config` | Validated per job type (below) |
| `timeoutSeconds` | 1–300, default 30 |
| `maxAttempts` | 1–10, default 3 |
| `retryDelaySeconds` | 1–3600, default 10 |

Config per job type:

| Type | Fields |
|---|---|
| `HTTP` | `method` (GET/POST/PUT/PATCH/DELETE, required), `url` (absolute http/https, required), `headers` (map), `body` (any JSON; placeholders allowed inside strings), `expectedStatus` (default: any 2xx) |
| `DELAY` | `duration`: ISO-8601, `PT1S` … `P7D` |
| `TRANSFORM` | `expression`: a JSONata expression, checked for syntax. It is evaluated against `{ input, steps: { <key>: { output } }, execution: { id, runNumber } }`, and its result becomes the job's output. `steps.<key>` references must be upstream (checked at activation). |
| `EMAIL` | `to` (1–20 addresses, required), `cc` (optional), `subject` (required), `text` and/or `html` (at least one). Addresses may be placeholders. The sender comes from the server configuration (`flowforge.mail.from`). |

**201** step · **400** · **404** · **409** `DUPLICATE_NAME` (key) · **409** `INVALID_STATE` (archived) · **422** `STEP_LIMIT_REACHED` if the workflow already has 30 steps

- `PUT /api/workflows/{workflowId}/steps/{stepId}`: same body without `key` and `jobType`. **200** · **400** · **404**
- `DELETE /api/workflows/{workflowId}/steps/{stepId}` → **204** · **404** · **409** `STEP_HAS_DEPENDENTS`

### `PUT /api/workflows/{workflowId}/steps/{stepId}/dependencies`

```json
{ "dependsOn": [11, 12] }
```

Replaces the whole list (an empty list makes the step a root). Duplicate IDs are ignored. Every ID must be a step of the same workflow and not the step itself, otherwise **400** `VALIDATION_ERROR` on the field `dependsOn`.
**200** `{ "stepId": 14, "dependsOn": [11, 12] }` · **400** · **404** · **409** `INVALID_STATE` (archived) · **422**.

The cycle path follows run order (each step runs before the next one in the list) and starts at the dependency that closes the loop:

```json
{ "status": 422, "code": "DEPENDENCY_CYCLE", "detail": "This would create a cycle: notify_crm → wait_1_day → notify_crm", "cycle": ["notify_crm", "wait_1_day", "notify_crm"] }
```

### `POST /api/workflows/{workflowId}/activate`

Runs full validation: at least one step, no cycles, every step config valid, and every placeholder known and upstream. Accepted placeholders are `{{input}}`, `{{input.<path>}}`, `{{steps.<key>.output}}`, `{{steps.<key>.output.<path>}}`, `{{execution.id}}` and `{{execution.runNumber}}`. Problems that don't belong to one step have `stepKey: null`. Activation changes the workflow, so its `version` increases. **200** workflow with `status: ACTIVE` · **404** · **409** `INVALID_STATE` (archived) · **422**:

```json
{
  "status": 422,
  "code": "WORKFLOW_INVALID",
  "detail": "Workflow cannot be activated",
  "problems": [
    { "stepKey": "notify_crm", "message": "References steps.create_account, which is not upstream of notify_crm" }
  ]
}
```

`POST /api/workflows/{workflowId}/deactivate` → **200** (`DRAFT`). Running executions are not affected.

---

## 6. Executions

### `POST /api/workflows/{workflowId}/executions`

Optional header: `Idempotency-Key: 6b0f3c1e-…` (the UI generates one per Run-button click).

```json
{ "input": { "customer": { "email": "jane@example.com", "plan": "PRO" } } }
```

**202 Accepted** + `Location: /api/executions/{id}`. The body is the execution in the same shape as `GET /api/executions/{executionId}` (including its jobs):

```json
{ "id": 91, "workflowId": 5, "workflowName": "Customer onboarding", "runNumber": 12, "status": "RUNNING", "triggerType": "MANUAL", "createdAt": "2026-09-27T09:30:00Z", "jobs": [ … ] }
```

The body is optional. Without it, `input` is `{}`. `input` must be a JSON object. `Idempotency-Key` is 1–100 characters.

- Same `Idempotency-Key` again → **200** with the *existing* execution (no second run).
- **404** · **409** `INVALID_STATE` (workflow not `ACTIVE`) · **422** `WORKFLOW_INVALID` (it became invalid after activation, because edits are allowed while `ACTIVE`)

`202` rather than `201` because the work hasn't happened yet. It has only been queued.

### `GET /api/executions?status=FAILED&page=0&size=20`

Also `GET /api/workflows/{workflowId}/executions?page=…`. Items are sorted newest first:

```json
{
  "items": [
    { "id": 91, "workflowId": 5, "workflowName": "Customer onboarding", "runNumber": 12,
      "status": "FAILED", "triggerType": "MANUAL", "createdAt": "2026-09-27T09:30:00Z",
      "finishedAt": "2026-09-27T09:31:12Z", "errorSummary": "create_crm_contact failed after 3 attempts: HTTP 503" }
  ],
  "page": 0, "size": 20, "total": 1
}
```

### `GET /api/executions/{executionId}`

The Angular detail page polls this every 2 s while `status = RUNNING`.

```json
{
  "id": 91,
  "workflowId": 5,
  "workflowName": "Customer onboarding",
  "runNumber": 12,
  "status": "RUNNING",
  "triggerType": "MANUAL",
  "input": { "customer": { "email": "jane@example.com", "plan": "PRO" } },
  "createdAt": "2026-09-27T09:30:00Z",
  "finishedAt": null,
  "errorSummary": null,
  "jobs": [
    { "id": 301, "stepKey": "create_crm_contact", "jobType": "HTTP", "status": "READY",
      "attemptCount": 1, "maxAttempts": 3, "availableAt": "2026-09-27T09:30:11Z",
      "lastError": "HTTP 503 Service Unavailable", "dependsOn": [], "startedAt": "2026-09-27T09:30:00Z", "finishedAt": null },
    { "id": 302, "stepKey": "create_account", "jobType": "HTTP", "status": "SUCCEEDED",
      "attemptCount": 1, "maxAttempts": 3, "availableAt": null, "lastError": null, "dependsOn": [],
      "startedAt": "2026-09-27T09:30:00Z", "finishedAt": "2026-09-27T09:30:00Z" },
    { "id": 303, "stepKey": "wait_1_day", "jobType": "DELAY", "status": "PENDING",
      "attemptCount": 0, "maxAttempts": 3, "availableAt": null, "lastError": null,
      "dependsOn": ["create_crm_contact", "create_account"], "startedAt": null, "finishedAt": null }
  ]
}
```

A job that is `READY` with `attemptCount > 0` and a future `availableAt` is **waiting to retry**. The UI shows "retry 2/3 in 8 s". Here `dependsOn` contains step **keys** (the snapshot), not step IDs.

### `POST /api/executions/{executionId}/cancel`

No body. **200** execution with `status: CANCELLED` · **404** · **409** `INVALID_STATE` (already finished).
Jobs that haven't started become `CANCELLED` immediately. A job currently running finishes its attempt, and nothing downstream starts ([execution-engine.md §10](execution-engine.md#10-cancellation)).

---

## 7. Job executions

### `GET /api/job-executions/{jobId}`

```json
{
  "id": 301,
  "executionId": 91,
  "stepKey": "create_crm_contact",
  "stepName": "Create CRM contact",
  "jobType": "HTTP",
  "status": "SUCCEEDED",
  "config": { "method": "POST", "url": "https://crm.example.com/api/contacts", "body": { "email": "{{input.customer.email}}" } },
  "timeoutSeconds": 30,
  "maxAttempts": 3,
  "attemptCount": 2,
  "output": { "status": 201, "body": { "id": "crm_881" }, "durationMs": 184 },
  "lastError": "HTTP 503 Service Unavailable",
  "attempts": [
    { "number": 1, "status": "FAILED", "workerId": "flowforge-1:1:flowforge-job-2", "startedAt": "2026-09-27T09:30:00Z",
      "finishedAt": "2026-09-27T09:30:01Z", "errorType": "HTTP_5XX", "errorMessage": "HTTP 503 Service Unavailable", "retryable": true },
    { "number": 2, "status": "SUCCEEDED", "workerId": "flowforge-1:1:flowforge-job-1", "startedAt": "2026-09-27T09:30:11Z",
      "finishedAt": "2026-09-27T09:30:11Z", "errorType": null, "errorMessage": null, "retryable": null }
  ]
}
```

`config` is the **copy** used by this run, with placeholders as written. **404**.

---

## 8. Schedules

### `POST /api/workflows/{workflowId}/schedules`

```json
{ "cronExpression": "0 8 * * *", "timezone": "Europe/Berlin", "input": {}, "enabled": true }
```

`cronExpression`: 5 fields (minute hour day month weekday). It must parse. `timezone`: a valid IANA zone.
**201**:

```json
{ "id": 3, "cronExpression": "0 8 * * *", "timezone": "Europe/Berlin", "input": {}, "enabled": true,
  "nextRunAt": "2026-09-28T06:00:00Z", "lastRunAt": null }
```

**400** (invalid cron or zone) · **404** · **409** `INVALID_STATE` (archived workflow)

`input` is optional (`{}`) and must be a JSON object. `enabled` is optional (`true`). `nextRunAt` is recalculated from the current time on every create and update, and it is `null` while the schedule is paused. The response also contains `workflowId`.

- `GET /api/workflows/{workflowId}/schedules` → **200** array
- `PUT /api/workflows/{workflowId}/schedules/{scheduleId}` (same body; `enabled: false` pauses the schedule) → **200** · **400** · **404**
- `DELETE /api/workflows/{workflowId}/schedules/{scheduleId}` → **204** · **404**

---

## 9. Dashboard

### `GET /api/dashboard`

**200**. The start page of the UI, in one request:

```json
{
  "running": { "items": [ { "id": 91, "workflowName": "Customer onboarding", "runNumber": 12, "status": "RUNNING", … } ], "page": 0, "size": 10, "total": 1 },
  "failedLast24Hours": { "items": [ { "id": 90, "status": "FAILED", "errorSummary": "fetch_orders failed after 3 attempts: HTTP 503 …", … } ], "page": 0, "size": 10, "total": 14 },
  "upcomingRuns": [ { "scheduleId": 3, "workflowId": 6, "workflowName": "Daily sales report", "cronExpression": "0 8 * * *", "timezone": "Europe/Berlin", "nextRunAt": "2026-09-29T06:00:00Z" } ]
}
```

- `running`: the newest 10 running executions, and how many there are in total. Items have the same shape as the execution list.
- `failedLast24Hours`: executions that finished as `FAILED` in the last 24 hours (measured with the database clock), newest first.
- `upcomingRuns`: the next 10 due times of **enabled** schedules of **`ACTIVE`** workflows, soonest first. Schedules that can't fire are left out.

Only the current user's data is included. **401** without a token.

---

## 10. Open decisions

| Decision | Leaning |
|---|---|
| Should `PUT` for steps also use optimistic locking? | No in the MVP. Step edits lock the workflow row on the server (for cycle safety), and last-write-wins between browser tabs is acceptable for now. |
| A "retry this failed job" endpoint | Later (FRS FR-57). The MVP answer is "run again". |

