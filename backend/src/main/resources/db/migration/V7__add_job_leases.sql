ALTER TABLE job_execution ADD COLUMN lease_expires_at timestamptz;

UPDATE job_execution
SET locked_by = COALESCE(locked_by, 'unknown'), lease_expires_at = now()
WHERE status = 'RUNNING';

ALTER TABLE job_execution ADD CONSTRAINT ck_job_running_lease
    CHECK ((status = 'RUNNING') = (lease_expires_at IS NOT NULL AND locked_by IS NOT NULL));

CREATE INDEX ix_job_lease ON job_execution (lease_expires_at) WHERE status = 'RUNNING';
