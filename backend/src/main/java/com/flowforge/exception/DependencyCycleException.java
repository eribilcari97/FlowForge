package com.flowforge.exception;

import java.util.List;

public class DependencyCycleException extends RuntimeException {

    private final List<String> cycle;

    public DependencyCycleException(List<String> cycle) {
        super("This would create a cycle: " + String.join(" → ", cycle));
        this.cycle = cycle;
    }

    public List<String> getCycle() {
        return cycle;
    }
}
