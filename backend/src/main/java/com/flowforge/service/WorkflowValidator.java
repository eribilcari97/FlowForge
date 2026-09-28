package com.flowforge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import com.flowforge.dto.ValidationProblem;
import com.flowforge.entity.JobType;
import com.flowforge.entity.WorkflowStep;
import com.flowforge.exception.InvalidFieldsException;

@Component
public class WorkflowValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.*?)}}");
    private static final Pattern INPUT = Pattern.compile("input(\\.[A-Za-z0-9_]+)*");
    private static final Pattern STEP_OUTPUT = Pattern.compile("steps\\.([a-z][a-z0-9_]*)\\.output(\\.[A-Za-z0-9_]+)*");
    private static final Set<String> EXECUTION_VALUES = Set.of("execution.id", "execution.runNumber");
    private static final Pattern EXPRESSION_STEP_REFERENCE = Pattern.compile("\\bsteps\\.([a-z][a-z0-9_]*)");

    private final StepConfigValidator configValidator;

    public WorkflowValidator(StepConfigValidator configValidator) {
        this.configValidator = configValidator;
    }

    public List<ValidationProblem> validate(List<WorkflowStep> steps, WorkflowGraph graph) {
        List<ValidationProblem> problems = new ArrayList<>();
        if (steps.isEmpty()) {
            problems.add(new ValidationProblem(null, "The workflow has no steps"));
            return problems;
        }
        graph.findCycle().ifPresent(cycle -> problems.add(
                new ValidationProblem(null, "Dependencies form a cycle: " + String.join(" → ", cycle))));

        Set<String> stepKeys = new LinkedHashSet<>();
        steps.forEach(step -> stepKeys.add(step.getKey()));
        for (WorkflowStep step : steps) {
            problems.addAll(configProblems(step));
            problems.addAll(placeholderProblems(step, stepKeys, graph));
        }
        return problems;
    }

    private List<ValidationProblem> configProblems(WorkflowStep step) {
        try {
            configValidator.validate(step.getJobType(), step.getConfig());
            return List.of();
        } catch (InvalidFieldsException e) {
            return e.getErrors().stream()
                    .map(error -> new ValidationProblem(step.getKey(), error.field() + " " + error.message()))
                    .toList();
        }
    }

    private List<ValidationProblem> placeholderProblems(WorkflowStep step, Set<String> stepKeys, WorkflowGraph graph) {
        List<ValidationProblem> problems = new ArrayList<>();
        Set<String> ancestors = graph.ancestors(step.getKey());
        for (String placeholder : placeholdersIn(step.getConfig())) {
            String problem = checkPlaceholder(placeholder, step.getKey(), stepKeys, ancestors);
            if (problem != null) {
                problems.add(new ValidationProblem(step.getKey(), problem));
            }
        }
        if (step.getJobType() == JobType.TRANSFORM) {
            Matcher reference = EXPRESSION_STEP_REFERENCE.matcher(step.getConfig().path("expression").asString(""));
            Set<String> referenced = new LinkedHashSet<>();
            while (reference.find()) {
                referenced.add(reference.group(1));
            }
            for (String referencedStep : referenced) {
                if (!stepKeys.contains(referencedStep)) {
                    problems.add(new ValidationProblem(step.getKey(),
                            "Expression references steps." + referencedStep + ", which does not exist"));
                } else if (!ancestors.contains(referencedStep)) {
                    problems.add(new ValidationProblem(step.getKey(), "Expression references steps."
                            + referencedStep + ", which is not upstream of " + step.getKey()));
                }
            }
        }
        return problems;
    }

    private static String checkPlaceholder(String placeholder, String stepKey, Set<String> stepKeys,
            Set<String> ancestors) {
        if (INPUT.matcher(placeholder).matches() || EXECUTION_VALUES.contains(placeholder)) {
            return null;
        }
        Matcher stepOutput = STEP_OUTPUT.matcher(placeholder);
        if (!stepOutput.matches()) {
            return "Unknown placeholder {{" + placeholder + "}}";
        }
        String referencedStep = stepOutput.group(1);
        if (!stepKeys.contains(referencedStep)) {
            return "References steps." + referencedStep + ", which does not exist";
        }
        if (!ancestors.contains(referencedStep)) {
            return "References steps." + referencedStep + ", which is not upstream of " + stepKey;
        }
        return null;
    }

    private static Set<String> placeholdersIn(JsonNode node) {
        Set<String> placeholders = new LinkedHashSet<>();
        collectPlaceholders(node, placeholders);
        return placeholders;
    }

    private static void collectPlaceholders(JsonNode node, Set<String> placeholders) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            Matcher matcher = PLACEHOLDER.matcher(node.stringValue());
            while (matcher.find()) {
                placeholders.add(matcher.group(1).trim());
            }
            return;
        }
        for (JsonNode child : node) {
            collectPlaceholders(child, placeholders);
        }
    }
}
