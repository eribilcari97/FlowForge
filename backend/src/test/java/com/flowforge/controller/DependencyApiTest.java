package com.flowforge.controller;

import static com.flowforge.TestApi.idOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.flowforge.IntegrationTest;
import com.flowforge.TestAccounts;
import com.flowforge.TestApi;
import com.flowforge.service.WorkflowGraph;

@IntegrationTest
class DependencyApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    TestApi api;
    String user;
    long projectId;
    long workflowId;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc);
        user = new TestAccounts(mvc).newUserBearer();
        projectId = api.createProject(user, "Acme Shop Ops");
        workflowId = api.createWorkflow(user, projectId, "Diamond");
    }

    @Test
    void aDiamondIsAccepted() {
        long a = step("a");
        long b = step("b");
        long c = step("c");
        long d = step("d");

        assertThat(api.setDependencies(user, workflowId, b, a)).hasStatusOk();
        assertThat(api.setDependencies(user, workflowId, c, a)).hasStatusOk();
        assertThat(api.setDependencies(user, workflowId, d, c, b))
                .hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        { "stepId": %d, "dependsOn": [%d, %d] }
                        """.formatted(d, b, c));

        assertThat(api.get(user, "/api/workflows/" + workflowId)).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        { "steps": [
                          { "key": "a", "dependsOn": [] },
                          { "key": "b", "dependsOn": [%d] },
                          { "key": "c", "dependsOn": [%d] },
                          { "key": "d", "dependsOn": [%d, %d] }
                        ] }
                        """.formatted(a, a, b, c));
    }

    @Test
    void aCycleIsRejectedWithItsPath() {
        long notify = step("notify_crm");
        long wait = step("wait_1_day");
        assertThat(api.setDependencies(user, workflowId, notify, wait)).hasStatusOk();

        assertThat(api.setDependencies(user, workflowId, wait, notify))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "status": 422,
                          "code": "DEPENDENCY_CYCLE",
                          "detail": "This would create a cycle: notify_crm → wait_1_day → notify_crm",
                          "cycle": ["notify_crm", "wait_1_day", "notify_crm"]
                        }
                        """);

        assertThat(storedDependencies()).containsEntry("wait_1_day", List.of());
    }

    @Test
    void aLongerCycleReportsEveryStepOnIt() {
        long a = step("a");
        long b = step("b");
        long c = step("c");
        api.setDependencies(user, workflowId, b, a);
        api.setDependencies(user, workflowId, c, b);

        assertThat(api.setDependencies(user, workflowId, a, c))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.cycle").asArray().containsExactly("c", "a", "b", "c");
    }

    @Test
    void aStepCannotDependOnItselfOrOnStepsOfAnotherWorkflow() {
        long a = step("a");
        long otherWorkflow = api.createWorkflow(user, projectId, "Other");
        long foreignStep = idOf(api.addHttpStep(user, otherWorkflow, "foreign"));

        assertThat(api.setDependencies(user, workflowId, a, a))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        { "code": "VALIDATION_ERROR", "errors": [ { "field": "dependsOn", "message": "a step can't depend on itself" } ] }
                        """);
        assertThat(api.setDependencies(user, workflowId, a, foreignStep))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("step " + foreignStep + " is not part of this workflow");
    }

    @Test
    void theListIsReplacedAndAnEmptyListMakesTheStepARoot() {
        long a = step("a");
        long b = step("b");
        long c = step("c");
        api.setDependencies(user, workflowId, c, a, b);

        assertThat(api.setDependencies(user, workflowId, c, b)).hasStatusOk();
        assertThat(storedDependencies()).containsEntry("c", List.of("b"));

        assertThat(api.setDependencies(user, workflowId, c)).hasStatusOk();
        assertThat(storedDependencies()).containsEntry("c", List.of());
    }

    @Test
    void aStepThatOthersDependOnCannotBeDeleted() {
        long fetch = step("fetch_orders");
        long report = step("send_report");
        long archive = step("archive_orders");
        api.setDependencies(user, workflowId, report, fetch);
        api.setDependencies(user, workflowId, archive, fetch);

        assertThat(api.delete(user, "/api/workflows/" + workflowId + "/steps/" + fetch))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().isLenientlyEqualTo("""
                        { "code": "STEP_HAS_DEPENDENTS", "detail": "fetch_orders is still required by: archive_orders, send_report" }
                        """);

        assertThat(api.delete(user, "/api/workflows/" + workflowId + "/steps/" + report)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(api.delete(user, "/api/workflows/" + workflowId + "/steps/" + archive)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(api.delete(user, "/api/workflows/" + workflowId + "/steps/" + fetch)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(storedDependencies()).isEmpty();
    }

    @Test
    void anotherUsersStepDependenciesAreNotFound() {
        long a = step("a");
        long b = step("b");
        String otherUser = new TestAccounts(mvc).newUserBearer();

        assertThat(api.setDependencies(otherUser, workflowId, b, a)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(storedDependencies()).containsEntry("b", List.of());
    }

    @Test
    void concurrentOppositeEditsNeverStoreACycle() throws Exception {
        for (int round = 0; round < 10; round++) {
            long workflow = api.createWorkflow(user, projectId, "Race " + round);
            long a = idOf(api.addHttpStep(user, workflow, "a"));
            long b = idOf(api.addHttpStep(user, workflow, "b"));

            List<Integer> statuses = runConcurrently(
                    () -> api.setDependencies(user, workflow, b, a).getResponse().getStatus(),
                    () -> api.setDependencies(user, workflow, a, b).getResponse().getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(200, 422);
            assertThat(new WorkflowGraph(dependenciesOf(workflow)).findCycle()).isEmpty();
        }
    }

    private long step(String key) {
        return idOf(api.addHttpStep(user, workflowId, key));
    }

    private Map<String, List<String>> storedDependencies() {
        return dependenciesOf(workflowId);
    }

    private Map<String, List<String>> dependenciesOf(long workflow) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        jdbc.sql("select step_key from workflow_step where workflow_id = ? order by id")
                .param(workflow)
                .query(String.class)
                .list()
                .forEach(key -> dependencies.put(key, new ArrayList<>()));
        jdbc.sql("""
                select s.step_key, d.step_key as depends_on
                from workflow_dependency e
                join workflow_step s on s.id = e.step_id
                join workflow_step d on d.id = e.depends_on_step_id
                where e.workflow_id = ?
                order by d.step_key
                """)
                .param(workflow)
                .query((row, number) -> Map.entry(row.getString(1), row.getString(2)))
                .list()
                .forEach(edge -> dependencies.get(edge.getKey()).add(edge.getValue()));
        return dependencies;
    }

    @SafeVarargs
    private static List<Integer> runConcurrently(Callable<Integer>... actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (Callable<Integer> action : actions) {
                results.add(executor.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }
}
