package com.flowforge.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.flowforge.entity.AttemptStatus;
import com.flowforge.entity.ExecutionStatus;
import com.flowforge.entity.JobStatus;
import com.flowforge.entity.JobType;

@Repository
public class JobQueue {

    public record ClaimedJob(long id, long executionId, String stepKey, JobType jobType, JsonNode config,
            int timeoutSeconds, int attemptNumber) {
    }

    public record DependentJob(long id, String stepKey, List<String> dependsOn, JobType jobType, JsonNode config) {
    }

    public record ExecutionContext(JsonNode input, int runNumber, Map<String, JsonNode> stepOutputs) {
    }

    public record JobCounts(int active, int failed) {
    }

    public record FailedJob(String stepKey, String lastError) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JobQueue(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ClaimedJob> claim(int limit, String workerId) {
        List<ClaimedJob> claimed = jdbc.sql("""
                WITH picked AS (
                    SELECT id
                    FROM job_execution
                    WHERE status = 'READY' AND available_at <= now()
                    ORDER BY available_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE job_execution j
                SET status        = 'RUNNING',
                    locked_by     = :workerId,
                    attempt_count = j.attempt_count + 1,
                    started_at    = COALESCE(j.started_at, now())
                FROM picked
                WHERE j.id = picked.id
                RETURNING j.id, j.workflow_execution_id, j.step_key, j.job_type, j.config::text AS config,
                          j.timeout_seconds, j.attempt_count
                """)
                .param("limit", limit)
                .param("workerId", workerId)
                .query((row, number) -> new ClaimedJob(
                        row.getLong("id"),
                        row.getLong("workflow_execution_id"),
                        row.getString("step_key"),
                        JobType.valueOf(row.getString("job_type")),
                        objectMapper.readTree(row.getString("config")),
                        row.getInt("timeout_seconds"),
                        row.getInt("attempt_count")))
                .list();

        for (ClaimedJob job : claimed) {
            jdbc.sql("""
                    INSERT INTO job_attempt (job_execution_id, attempt_number, status, worker_id, started_at)
                    VALUES (:jobId, :attempt, 'RUNNING', :workerId, now())
                    """)
                    .param("jobId", job.id())
                    .param("attempt", job.attemptNumber())
                    .param("workerId", workerId)
                    .update();
        }
        return claimed;
    }

    public ExecutionContext loadContext(long executionId) {
        record ExecutionRow(String input, int runNumber) {
        }
        ExecutionRow execution = jdbc.sql("SELECT input::text AS input, run_number FROM workflow_execution WHERE id = ?")
                .param(executionId)
                .query((row, number) -> new ExecutionRow(row.getString("input"), row.getInt("run_number")))
                .single();
        Map<String, JsonNode> outputs = new HashMap<>();
        jdbc.sql("""
                SELECT step_key, output::text AS output
                FROM job_execution
                WHERE workflow_execution_id = ? AND status = 'SUCCEEDED'
                """)
                .param(executionId)
                .query((row, number) -> Map.entry(row.getString("step_key"), readJson(row.getString("output"))))
                .list()
                .forEach(entry -> outputs.put(entry.getKey(), entry.getValue()));
        return new ExecutionContext(objectMapper.readTree(execution.input()), execution.runNumber(), outputs);
    }

    public ExecutionStatus lockExecution(long executionId) {
        return ExecutionStatus.valueOf(jdbc.sql("SELECT status FROM workflow_execution WHERE id = ? FOR UPDATE")
                .param(executionId)
                .query(String.class)
                .single());
    }

    public boolean finishJob(long jobId, int attemptNumber, JobStatus status, JsonNode output, String lastError) {
        int updated = jdbc.sql("""
                UPDATE job_execution
                SET status = :status, output = CAST(:output AS jsonb), last_error = :lastError,
                    finished_at = now(), locked_by = NULL
                WHERE id = :id AND status = 'RUNNING' AND attempt_count = :attempt
                """)
                .param("status", status.name())
                .param("output", output != null ? output.toString() : null)
                .param("lastError", lastError)
                .param("id", jobId)
                .param("attempt", attemptNumber)
                .update();
        return updated == 1;
    }

    public void finishAttempt(long jobId, int attemptNumber, AttemptStatus status, String errorType,
            String errorMessage) {
        jdbc.sql("""
                UPDATE job_attempt
                SET status = :status, finished_at = now(), error_type = :errorType, error_message = :errorMessage
                WHERE job_execution_id = :jobId AND attempt_number = :attempt AND status = 'RUNNING'
                """)
                .param("status", status.name())
                .param("errorType", errorType)
                .param("errorMessage", errorMessage)
                .param("jobId", jobId)
                .param("attempt", attemptNumber)
                .update();
    }

    public List<DependentJob> pendingDependentsOf(long executionId, String stepKey) {
        return jdbc.sql("""
                SELECT id, step_key, depends_on, job_type, config::text AS config
                FROM job_execution
                WHERE workflow_execution_id = :executionId AND status = 'PENDING' AND :stepKey = ANY(depends_on)
                ORDER BY id
                """)
                .param("executionId", executionId)
                .param("stepKey", stepKey)
                .query((row, number) -> new DependentJob(
                        row.getLong("id"),
                        row.getString("step_key"),
                        textArray(row, "depends_on"),
                        JobType.valueOf(row.getString("job_type")),
                        objectMapper.readTree(row.getString("config"))))
                .list();
    }

    public boolean allSucceeded(long executionId, Collection<String> stepKeys) {
        long notSucceeded = jdbc.sql("""
                SELECT count(*) FROM job_execution
                WHERE workflow_execution_id = :executionId AND step_key = ANY(CAST(:stepKeys AS text[]))
                  AND status <> 'SUCCEEDED'
                """)
                .param("executionId", executionId)
                .param("stepKeys", stepKeys.toArray(String[]::new))
                .query(Long.class)
                .single();
        return notSucceeded == 0;
    }

    public void markReady(long jobId, Duration delay) {
        jdbc.sql("""
                UPDATE job_execution
                SET status = 'READY', available_at = now() + make_interval(secs => :seconds)
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("seconds", delay.toMillis() / 1000.0)
                .param("id", jobId)
                .update();
    }

    public List<String> skipPendingDependentsOf(long executionId, Collection<String> stepKeys) {
        return jdbc.sql("""
                UPDATE job_execution
                SET status = 'SKIPPED', finished_at = now()
                WHERE workflow_execution_id = :executionId AND status = 'PENDING'
                  AND depends_on && CAST(:stepKeys AS text[])
                RETURNING step_key
                """)
                .param("executionId", executionId)
                .param("stepKeys", stepKeys.toArray(String[]::new))
                .query(String.class)
                .list();
    }

    public JobCounts countJobs(long executionId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status IN ('PENDING', 'READY', 'RUNNING')) AS active,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM job_execution
                WHERE workflow_execution_id = ?
                """)
                .param(executionId)
                .query((row, number) -> new JobCounts(row.getInt("active"), row.getInt("failed")))
                .single();
    }

    public Optional<FailedJob> firstFailedJob(long executionId) {
        return jdbc.sql("""
                SELECT step_key, last_error FROM job_execution
                WHERE workflow_execution_id = ? AND status = 'FAILED'
                ORDER BY finished_at, id
                LIMIT 1
                """)
                .param(executionId)
                .query((row, number) -> new FailedJob(row.getString("step_key"), row.getString("last_error")))
                .optional();
    }

    public void finishExecution(long executionId, ExecutionStatus status, String errorSummary) {
        jdbc.sql("""
                UPDATE workflow_execution
                SET status = :status, finished_at = now(), error_summary = :errorSummary
                WHERE id = :id AND status = 'RUNNING'
                """)
                .param("status", status.name())
                .param("errorSummary", errorSummary)
                .param("id", executionId)
                .update();
    }

    private JsonNode readJson(String json) {
        return json == null ? null : objectMapper.readTree(json);
    }

    private static List<String> textArray(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        return List.of((String[]) array.getArray());
    }
}
