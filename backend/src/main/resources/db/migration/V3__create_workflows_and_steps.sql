CREATE TABLE workflow (
    id              bigint        GENERATED ALWAYS AS IDENTITY,
    project_id      bigint        NOT NULL,
    name            varchar(100)  NOT NULL,
    description     varchar(2000),
    status          varchar(16)   NOT NULL DEFAULT 'DRAFT',
    execution_count int           NOT NULL DEFAULT 0,
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_workflow PRIMARY KEY (id),
    CONSTRAINT fk_workflow_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uq_workflow_project_name ON workflow (project_id, lower(name)) WHERE status <> 'ARCHIVED';

CREATE TABLE workflow_step (
    id                  bigint       GENERATED ALWAYS AS IDENTITY,
    workflow_id         bigint       NOT NULL,
    step_key            varchar(50)  NOT NULL,
    name                varchar(100) NOT NULL,
    job_type            varchar(20)  NOT NULL,
    config              jsonb        NOT NULL,
    timeout_seconds     int          NOT NULL DEFAULT 30,
    max_attempts        int          NOT NULL DEFAULT 3,
    retry_delay_seconds int          NOT NULL DEFAULT 10,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_workflow_step PRIMARY KEY (id),
    CONSTRAINT fk_step_workflow FOREIGN KEY (workflow_id) REFERENCES workflow (id) ON DELETE CASCADE,
    CONSTRAINT uq_step_key UNIQUE (workflow_id, step_key),
    CONSTRAINT uq_step_workflow_id UNIQUE (workflow_id, id),
    CONSTRAINT ck_step_key CHECK (step_key ~ '^[a-z][a-z0-9_]{0,49}$'),
    CONSTRAINT ck_step_job_type CHECK (job_type IN ('HTTP', 'DELAY')),
    CONSTRAINT ck_step_timeout CHECK (timeout_seconds BETWEEN 1 AND 300),
    CONSTRAINT ck_step_max_attempts CHECK (max_attempts BETWEEN 1 AND 10),
    CONSTRAINT ck_step_retry_delay CHECK (retry_delay_seconds BETWEEN 1 AND 3600)
);
