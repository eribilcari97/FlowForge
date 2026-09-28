package com.flowforge;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class EngineTestData {

    private EngineTestData() {
    }

    public static void cancelLeftoverWork(JdbcClient jdbc) {
        jdbc.sql("""
                UPDATE job_attempt SET status = 'ABANDONED', finished_at = now() WHERE status = 'RUNNING'
                """).update();
        jdbc.sql("""
                UPDATE job_execution SET status = 'CANCELLED', finished_at = now(), locked_by = NULL, lease_expires_at = NULL
                WHERE status IN ('PENDING', 'READY', 'RUNNING')
                """).update();
        jdbc.sql("""
                UPDATE workflow_execution SET status = 'CANCELLED', finished_at = now() WHERE status = 'RUNNING'
                """).update();
    }

    public static String jobStatus(JdbcClient jdbc, long executionId, String stepKey) {
        return jdbc.sql("SELECT status FROM job_execution WHERE workflow_execution_id = ? AND step_key = ?")
                .params(executionId, stepKey)
                .query(String.class)
                .single();
    }

    public static String executionStatus(JdbcClient jdbc, long executionId) {
        return jdbc.sql("SELECT status FROM workflow_execution WHERE id = ?")
                .param(executionId)
                .query(String.class)
                .single();
    }
}
