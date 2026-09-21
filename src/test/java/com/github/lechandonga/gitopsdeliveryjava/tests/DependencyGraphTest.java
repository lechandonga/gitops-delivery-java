package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.DependencyGraph;
import com.github.lechandonga.gitopsdeliveryjava.engine.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.CanarySpec;
import com.github.lechandonga.gitopsdeliveryjava.model.ManifestStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyGraphTest {

    private ManifestStage stage(String id, String... deps) {
        return new ManifestStage(id, "env", "v1", List.of(deps), null, 5000, 3);
    }

    @Test
    void ordersByDependency() {
        DependencyGraph g = new DependencyGraph(List.of(
                stage("c", "a", "b"), stage("a"), stage("b", "a")));
        List<String> order = g.topologicalOrder();
        assertTrue(order.indexOf("a") < order.indexOf("b"));
        assertTrue(order.indexOf("b") < order.indexOf("c"));
    }

    @Test
    void rejectsCycleWithDistinctReason() {
        DependencyGraph g = new DependencyGraph(List.of(
                stage("a", "b"), stage("b", "a")));
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                g::topologicalOrder);
        assertEquals(ReasonCode.CYCLIC_DEPENDENCY, ex.reasonCode());
        assertTrue(ex.getMessage().contains("a") && ex.getMessage().contains("b"));
    }

    @Test
    void rejectsUnknownDependency() {
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> new DependencyGraph(List.of(stage("a", "ghost"))));
        assertEquals(ReasonCode.GATE_MANIFEST_INVALID, ex.reasonCode());
    }

    @Test
    void reportsMissingPrerequisites() {
        DependencyGraph g = new DependencyGraph(List.of(stage("a"), stage("b", "a")));
        assertEquals(List.of("a"), g.missingPrerequisites("b", id -> false));
        assertTrue(g.missingPrerequisites("b", id -> true).isEmpty());
    }
}
