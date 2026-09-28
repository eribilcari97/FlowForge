CREATE TABLE workflow_schedule (
    id              bigint       GENERATED ALWAYS AS IDENTITY,
    workflow_id     bigint       NOT NULL,
    cron_expression varchar(100) NOT NULL,
    timezone        varchar(64)  NOT NULL,
    input           jsonb        NOT NULL DEFAULT '{}',
    enabled         boolean      NOT NULL DEFAULT true,
    next_run_at     timestamptz,
    last_run_at     timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_workflow_schedule PRIMARY KEY (id),
    CONSTRAINT fk_schedule_workflow FOREIGN KEY (workflow_id) REFERENCES workflow (id) ON DELETE CASCADE,
    CONSTRAINT ck_schedule_next_run CHECK (enabled = (next_run_at IS NOT NULL))
);

CREATE INDEX ix_schedule_due ON workflow_schedule (next_run_at) WHERE enabled;

ALTER TABLE workflow_execution
    ADD COLUMN schedule_id bigint,
    ADD COLUMN scheduled_for timestamptz,
    ADD CONSTRAINT fk_execution_schedule FOREIGN KEY (schedule_id)
        REFERENCES workflow_schedule (id) ON DELETE SET NULL;
