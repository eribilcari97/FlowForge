ALTER TABLE workflow_step DROP CONSTRAINT ck_step_job_type;
ALTER TABLE workflow_step ADD CONSTRAINT ck_step_job_type
    CHECK (job_type IN ('HTTP', 'DELAY', 'TRANSFORM', 'EMAIL'));

ALTER TABLE job_execution DROP CONSTRAINT ck_job_type;
ALTER TABLE job_execution ADD CONSTRAINT ck_job_type
    CHECK (job_type IN ('HTTP', 'DELAY', 'TRANSFORM', 'EMAIL'));
