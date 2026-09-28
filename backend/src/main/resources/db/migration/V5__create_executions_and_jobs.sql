CREATE TABLE workflow_execution (
    id            bigint        GENERATED ALWAYS AS IDENTITY,
    workflow_id   bigint        NOT NULL,
    run_number    int           NOT NULL,
    status        varchar(16)   NOT NULL,
    trigger_type  varchar(16)   NOT NULL,
    triggered_by  bigint,
    dedup_key     varchar(120),
    input         jsonb         NOT NULL DEFAULT '{}',
    error_summary varchar(1000),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    finished_at   timestamptz,
    CONSTRAINT pk_workflow_execution PRIMARY KEY (id),
    CONSTRAINT fk_execution_workflow FOREIGN KEY (workflow_id) REFERENCES workflow (id) ON DELETE RESTRICT,
    CONSTRAINT fk_execution_triggered_by FOREIGN KEY (triggered_by) REFERENCES "user" (id) ON DELETE RESTRICT,
    CONSTRAINT uq_execution_run_number UNIQUE (workflow_id, run_number),
    CONSTRAINT uq_execution_dedup UNIQUE (workflow_id, dedup_key),
    CONSTRAINT ck_execution_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_execution_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULE')),
    CONSTRAINT ck_execution_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE INDEX ix_execution_workflow_created ON workflow_execution (workflow_id, created_at DESC);
CREATE INDEX ix_execution_running ON workflow_execution (workflow_id) WHERE status = 'RUNNING';

CREATE TABLE job_execution (
    id                    bigint       GENERATED ALWAYS AS IDENTITY,
    workflow_execution_id bigint       NOT NULL,
    step_key              varchar(50)  NOT NULL,
    step_name             varchar(100) NOT NULL,
    job_type              varchar(20)  NOT NULL,
    config                jsonb        NOT NULL,
    timeout_seconds       int          NOT NULL,
    max_attempts          int          NOT NULL,
    retry_delay_seconds   int          NOT NULL,
    depends_on            text[]       NOT NULL DEFAULT '{}',
    status                varchar(16)  NOT NULL,
    attempt_count         int          NOT NULL DEFAULT 0,
    available_at          timestamptz,
    output                jsonb,
    last_error            text,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    started_at            timestamptz,
    finished_at           timestamptz,
    CONSTRAINT pk_job_execution PRIMARY KEY (id),
    CONSTRAINT fk_job_execution FOREIGN KEY (workflow_execution_id)
        REFERENCES workflow_execution (id) ON DELETE CASCADE,
    CONSTRAINT uq_job_step UNIQUE (workflow_execution_id, step_key),
    CONSTRAINT ck_job_type CHECK (job_type IN ('HTTP', 'DELAY')),
    CONSTRAINT ck_job_status CHECK (status IN ('PENDING', 'READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_job_ready_time CHECK (status <> 'READY' OR available_at IS NOT NULL),
    CONSTRAINT ck_job_attempts CHECK (attempt_count BETWEEN 0 AND max_attempts)
);

CREATE INDEX ix_job_claim ON job_execution (available_at) WHERE status = 'READY';
