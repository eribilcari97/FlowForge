package com.flowforge.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flowforge.IntegrationTest;

@IntegrationTest
class WorkflowDependencyConstraintsTest {

    @Autowired
    JdbcClient jdbc;

    long workflowA;
    long workflowB;

    @BeforeEach
    void setUp() {
        long userId = jdbc.sql("""
                insert into "user" (email, password_hash, display_name)
                values ('constraints-' || gen_random_uuid() || '@example.com', 'x', 'Constraints')
                returning id
                """).query(Long.class).single();
        long projectId = jdbc.sql("insert into project (owner_id, name) values (?, 'Constraints') returning id")
                .param(userId).query(Long.class).single();
        workflowA = insertWorkflow(projectId, "A");
        workflowB = insertWorkflow(projectId, "B");
    }

    @Test
    void edgesWithinOneWorkflowAreAccepted() {
        long first = insertStep(workflowA, "first");
        long second = insertStep(workflowA, "second");

        assertThatCode(() -> insertEdge(workflowA, second, first)).doesNotThrowAnyException();
    }

    @Test
    void theDatabaseRejectsAnEdgeBetweenTwoWorkflows() {
        long inA = insertStep(workflowA, "in_a");
        long inB = insertStep(workflowB, "in_b");

        assertThatThrownBy(() -> insertEdge(workflowA, inA, inB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_dependency_depends_on");
        assertThatThrownBy(() -> insertEdge(workflowB, inA, inB))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_dependency_step");
    }

    @Test
    void theDatabaseRejectsASelfDependency() {
        long step = insertStep(workflowA, "loop");

        assertThatThrownBy(() -> insertEdge(workflowA, step, step))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_dependency_not_self");
    }

    @Test
    void theDatabaseRejectsDeletingAStepOthersDependOn() {
        long first = insertStep(workflowA, "first");
        long second = insertStep(workflowA, "second");
        insertEdge(workflowA, second, first);

        assertThatThrownBy(() -> jdbc.sql("delete from workflow_step where id = ?").param(first).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_dependency_depends_on");
    }

    private long insertWorkflow(long projectId, String name) {
        return jdbc.sql("insert into workflow (project_id, name) values (?, ?) returning id")
                .params(projectId, name).query(Long.class).single();
    }

    private long insertStep(long workflowId, String key) {
        return jdbc.sql("""
                insert into workflow_step (workflow_id, step_key, name, job_type, config)
                values (?, ?, ?, 'DELAY', '{"duration": "PT1S"}')
                returning id
                """).params(workflowId, key, key).query(Long.class).single();
    }

    private void insertEdge(long workflowId, long stepId, long dependsOnStepId) {
        jdbc.sql("insert into workflow_dependency (workflow_id, step_id, depends_on_step_id) values (?, ?, ?)")
                .params(workflowId, stepId, dependsOnStepId).update();
    }
}
