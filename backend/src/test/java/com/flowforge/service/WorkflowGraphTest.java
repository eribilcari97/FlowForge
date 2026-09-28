package com.flowforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

    private static WorkflowGraph graph(String... edges) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (String edge : edges) {
            String[] parts = edge.split(" depends on ");
            dependencies.computeIfAbsent(parts[0], key -> new ArrayList<>());
            if (parts.length == 2) {
                for (String dependency : parts[1].split(",")) {
                    dependencies.get(parts[0]).add(dependency.trim());
                }
            }
        }
        return new WorkflowGraph(dependencies);
    }

    private final WorkflowGraph diamond = graph(
            "a",
            "b depends on a",
            "c depends on a",
            "d depends on b, c");

    @Test
    void aDiamondHasNoCycle() {
        assertThat(diamond.findCycle()).isEmpty();
    }

    @Test
    void topologicalOrderPutsEveryStepAfterItsDependencies() {
        assertThat(diamond.topologicalOrder()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void independentRootsKeepTheirOrder() {
        WorkflowGraph onboarding = graph(
                "create_crm_contact",
                "create_account",
                "wait_1_day depends on create_crm_contact, create_account",
                "notify_crm depends on wait_1_day");

        assertThat(onboarding.topologicalOrder())
                .containsExactly("create_crm_contact", "create_account", "wait_1_day", "notify_crm");
    }

    @Test
    void ancestorsIncludeDirectAndTransitiveDependencies() {
        assertThat(diamond.ancestors("d")).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(diamond.ancestors("b")).containsExactly("a");
        assertThat(diamond.ancestors("a")).isEmpty();
    }

    @Test
    void descendantsIncludeEveryStepThatWaitsDirectlyOrTransitively() {
        assertThat(diamond.descendants("a")).containsExactlyInAnyOrder("b", "c", "d");
        assertThat(diamond.descendants("c")).containsExactly("d");
        assertThat(diamond.descendants("d")).isEmpty();
    }

    @Test
    void findsATwoStepCycle() {
        WorkflowGraph cyclic = graph("a depends on b", "b depends on a");

        assertThat(cyclic.findCycle()).hasValueSatisfying(cycle -> {
            assertThat(cycle).hasSize(3);
            assertThat(cycle.getFirst()).isEqualTo(cycle.getLast());
            assertThat(cycle).contains("a", "b");
        });
    }

    @Test
    void cyclePathFollowsRunOrderAndStartsAtTheDependencyThatClosesTheLoop() {
        WorkflowGraph cyclic = graph(
                "wait_1_day depends on notify_crm",
                "notify_crm depends on wait_1_day");

        assertThat(cyclic.findCycleThrough("wait_1_day"))
                .hasValue(List.of("notify_crm", "wait_1_day", "notify_crm"));
    }

    @Test
    void reportsTheWholeLongerCycle() {
        WorkflowGraph cyclic = graph(
                "a depends on c",
                "b depends on a",
                "c depends on b",
                "d depends on c");

        assertThat(cyclic.findCycleThrough("a")).hasValue(List.of("c", "a", "b", "c"));
        assertThat(cyclic.findCycle()).hasValueSatisfying(cycle ->
                assertThat(cycle).hasSize(4).contains("a", "b", "c").doesNotContain("d"));
    }

    @Test
    void noCycleThroughAStepOutsideAnyCycle() {
        assertThat(diamond.findCycleThrough("b")).isEmpty();
    }

    @Test
    void topologicalOrderRefusesACyclicGraph() {
        assertThatThrownBy(() -> graph("a depends on b", "b depends on a").topologicalOrder())
                .isInstanceOf(IllegalStateException.class);
    }
}
