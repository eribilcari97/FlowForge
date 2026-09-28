CREATE TABLE workflow_dependency (
    workflow_id        bigint NOT NULL,
    step_id            bigint NOT NULL,
    depends_on_step_id bigint NOT NULL,
    CONSTRAINT pk_workflow_dependency PRIMARY KEY (step_id, depends_on_step_id),
    CONSTRAINT ck_dependency_not_self CHECK (step_id <> depends_on_step_id),
    CONSTRAINT fk_dependency_step FOREIGN KEY (workflow_id, step_id)
        REFERENCES workflow_step (workflow_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_dependency_depends_on FOREIGN KEY (workflow_id, depends_on_step_id)
        REFERENCES workflow_step (workflow_id, id)
);

CREATE INDEX ix_dependency_depends_on ON workflow_dependency (depends_on_step_id);
