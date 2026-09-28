package com.flowforge.exception;

import java.util.List;

import com.flowforge.dto.ValidationProblem;

public class WorkflowInvalidException extends RuntimeException {

    private final List<ValidationProblem> problems;

    public WorkflowInvalidException(List<ValidationProblem> problems) {
        super("Workflow cannot be activated");
        this.problems = problems;
    }

    public List<ValidationProblem> getProblems() {
        return problems;
    }
}
