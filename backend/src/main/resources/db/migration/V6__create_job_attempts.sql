ALTER TABLE job_execution ADD COLUMN locked_by varchar(100);

CREATE TABLE job_attempt (
    id               bigint       GENERATED ALWAYS AS IDENTITY,
    job_execution_id bigint       NOT NULL,
    attempt_number   int          NOT NULL,
    status           varchar(16)  NOT NULL,
    worker_id        varchar(100) NOT NULL,
    started_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    error_type       varchar(40),
    error_message    text,
    retryable        boolean,
    CONSTRAINT pk_job_attempt PRIMARY KEY (id),
    CONSTRAINT fk_attempt_job FOREIGN KEY (job_execution_id) REFERENCES job_execution (id) ON DELETE CASCADE,
    CONSTRAINT uq_attempt_number UNIQUE (job_execution_id, attempt_number),
    CONSTRAINT ck_attempt_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'ABANDONED')),
    CONSTRAINT ck_attempt_finished CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);
