package com.flowforge.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.flowforge.dto.DependenciesRequest;
import com.flowforge.dto.ProjectRequest;
import com.flowforge.dto.RegisterRequest;
import com.flowforge.dto.ScheduleRequest;
import com.flowforge.dto.StepCreateRequest;
import com.flowforge.dto.WorkflowRequest;
import com.flowforge.entity.JobType;
import com.flowforge.exception.ConflictException;
import com.flowforge.repository.UserRepository;
import com.flowforge.service.AuthService;
import com.flowforge.service.DependencyService;
import com.flowforge.service.ExecutionService;
import com.flowforge.service.ProjectService;
import com.flowforge.service.ScheduleService;
import com.flowforge.service.StepService;
import com.flowforge.service.WorkflowService;

@Component
@Profile("demo")
@EnableConfigurationProperties(DemoProperties.class)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DemoProperties demo;
    private final UserRepository users;
    private final AuthService auth;
    private final ProjectService projects;
    private final WorkflowService workflows;
    private final StepService steps;
    private final DependencyService dependencies;
    private final ScheduleService schedules;
    private final ExecutionService executions;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public DemoDataSeeder(DemoProperties demo, UserRepository users, AuthService auth, ProjectService projects,
            WorkflowService workflows, StepService steps, DependencyService dependencies, ScheduleService schedules,
            ExecutionService executions, TransactionTemplate transaction, ObjectMapper objectMapper) {
        this.demo = demo;
        this.users = users;
        this.auth = auth;
        this.projects = projects;
        this.workflows = workflows;
        this.steps = steps;
        this.dependencies = dependencies;
        this.schedules = schedules;
        this.executions = executions;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(demo.email())) {
            log.info("Demo data already present for {}", demo.email());
            return;
        }
        try {
            transaction.executeWithoutResult(status -> seed());
            log.info("Seeded demo user {} with example workflows", demo.email());
        } catch (ConflictException | DataIntegrityViolationException e) {
            log.info("Demo data was seeded by another instance");
        }
    }

    private void seed() {
        Long owner = auth.register(new RegisterRequest(demo.email(), demo.password(), "Demo User")).id();
        Long project = projects.create(owner, new ProjectRequest("Demo workspace",
                "Example workflows that call FlowForge's built-in demo endpoints.")).id();
        salesReport(owner, project);
        healthCheck(owner, project);
        flakySync(owner, project);
    }

    private void salesReport(Long owner, Long project) {
        Long workflow = workflows.create(project, owner, new WorkflowRequest("Daily sales report",
                "Fetches the orders, totals the paid ones and emails the result.")).id();
        Long fetch = step(owner, workflow, "fetch_orders", "Fetch orders", JobType.HTTP, """
                { "method": "GET", "url": "%s/demo/orders" }
                """.formatted(demo.baseUrl()), 3);
        Long calculate = step(owner, workflow, "calculate_revenue", "Calculate revenue", JobType.TRANSFORM, """
                { "expression": "{ 'orders': steps.fetch_orders.output.body.count, 'revenue': $sum(steps.fetch_orders.output.body.orders[status = 'PAID'].total) }" }
                """, 1);
        Long send = step(owner, workflow, "send_report", "Email the report", JobType.EMAIL, """
                {
                  "to": ["%s"],
                  "subject": "Daily sales report",
                  "text": "Paid revenue: {{steps.calculate_revenue.output.revenue}} from {{steps.calculate_revenue.output.orders}} orders."
                }
                """.formatted(demo.email()), 1);
        dependencies.replace(workflow, calculate, owner, new DependenciesRequest(List.of(fetch)));
        dependencies.replace(workflow, send, owner, new DependenciesRequest(List.of(calculate)));
        activateScheduleAndRun(owner, workflow, "0 8 * * *");
    }

    private void healthCheck(Long owner, Long project) {
        Long workflow = workflows.create(project, owner, new WorkflowRequest("API health check",
                "Calls the health endpoint and records every failure as a failed run.")).id();
        step(owner, workflow, "check_api", "Check API health", JobType.HTTP, """
                { "method": "GET", "url": "%s/actuator/health", "expectedStatus": [200] }
                """.formatted(demo.baseUrl()), 3);
        activateScheduleAndRun(owner, workflow, "*/15 * * * *");
    }

    private void flakySync(Long owner, Long project) {
        Long workflow = workflows.create(project, owner, new WorkflowRequest("Partner sync with retries",
                "Calls a partner API that fails twice before it answers, then waits before confirming.")).id();
        Long call = step(owner, workflow, "call_partner", "Call partner API", JobType.HTTP, """
                { "method": "GET", "url": "%s/demo/flaky" }
                """.formatted(demo.baseUrl()), 5);
        Long wait = step(owner, workflow, "cool_down", "Wait before confirming", JobType.DELAY, """
                { "duration": "PT10S" }
                """, 1);
        dependencies.replace(workflow, wait, owner, new DependenciesRequest(List.of(call)));
        workflows.activate(workflow, owner);
        executions.start(workflow, owner, null, null);
    }

    private void activateScheduleAndRun(Long owner, Long workflow, String cron) {
        workflows.activate(workflow, owner);
        schedules.create(workflow, owner, new ScheduleRequest(cron, "UTC", null, true));
        executions.start(workflow, owner, null, null);
    }

    private Long step(Long owner, Long workflow, String key, String name, JobType type, String config,
            int maxAttempts) {
        JsonNode json = objectMapper.readTree(config);
        return steps.add(workflow, owner, new StepCreateRequest(key, name, type, json, 30, maxAttempts, 2)).id();
    }
}
