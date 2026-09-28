package com.flowforge.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class WorkflowGraph {

    private final Map<String, List<String>> upstream = new LinkedHashMap<>();
    private final Map<String, List<String>> downstream = new LinkedHashMap<>();

    public WorkflowGraph(Map<String, ? extends Collection<String>> dependencies) {
        dependencies.keySet().forEach(this::addStep);
        dependencies.forEach((step, stepDependencies) -> {
            for (String dependency : stepDependencies) {
                addStep(dependency);
                upstream.get(step).add(dependency);
                downstream.get(dependency).add(step);
            }
        });
    }

    public Optional<List<String>> findCycle() {
        Set<String> finished = new HashSet<>();
        for (String step : downstream.keySet()) {
            List<String> path = new ArrayList<>();
            Optional<List<String>> cycle = findCycleFrom(step, path, finished);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    public Optional<List<String>> findCycleThrough(String step) {
        List<String> path = new ArrayList<>(List.of(step));
        if (!findPathBack(step, step, path, new HashSet<>())) {
            return Optional.empty();
        }
        List<String> loop = path.subList(0, path.size() - 1);
        String closingStep = loop.get(loop.size() - 1);
        List<String> cycle = new ArrayList<>();
        cycle.add(closingStep);
        cycle.addAll(loop);
        return Optional.of(cycle);
    }

    public List<String> topologicalOrder() {
        Map<String, Integer> remainingDependencies = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        upstream.forEach((step, dependencies) -> {
            remainingDependencies.put(step, dependencies.size());
            if (dependencies.isEmpty()) {
                ready.add(step);
            }
        });

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String step = ready.poll();
            order.add(step);
            for (String dependent : downstream.get(step)) {
                int remaining = remainingDependencies.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != upstream.size()) {
            throw new IllegalStateException("The graph contains a cycle");
        }
        return order;
    }

    public Set<String> ancestors(String step) {
        return reachable(step, upstream);
    }

    public Set<String> descendants(String step) {
        return reachable(step, downstream);
    }

    private void addStep(String step) {
        upstream.putIfAbsent(step, new ArrayList<>());
        downstream.putIfAbsent(step, new ArrayList<>());
    }

    private Optional<List<String>> findCycleFrom(String step, List<String> path, Set<String> finished) {
        if (finished.contains(step)) {
            return Optional.empty();
        }
        int index = path.indexOf(step);
        if (index >= 0) {
            List<String> cycle = new ArrayList<>(path.subList(index, path.size()));
            cycle.add(step);
            return Optional.of(cycle);
        }
        path.add(step);
        for (String next : downstream.get(step)) {
            Optional<List<String>> cycle = findCycleFrom(next, path, finished);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        finished.add(step);
        return Optional.empty();
    }

    private boolean findPathBack(String step, String target, List<String> path, Set<String> visited) {
        for (String next : downstream.get(step)) {
            if (next.equals(target)) {
                path.add(next);
                return true;
            }
            if (visited.add(next)) {
                path.add(next);
                if (findPathBack(next, target, path, visited)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
        }
        return false;
    }

    private static Set<String> reachable(String start, Map<String, List<String>> edges) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> toVisit = new ArrayDeque<>(edges.getOrDefault(start, List.of()));
        while (!toVisit.isEmpty()) {
            String step = toVisit.poll();
            if (found.add(step)) {
                toVisit.addAll(edges.get(step));
            }
        }
        return found;
    }
}
