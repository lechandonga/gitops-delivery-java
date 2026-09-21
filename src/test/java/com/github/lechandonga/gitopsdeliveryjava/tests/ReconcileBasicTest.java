package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.ConvergenceReport;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.infra.InMemoryLocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileBasicTest {

    private static final String MANIFEST = """
            { "name": "app", "stages": [
                { "id": "dev", "env": "dev", "desiredVersion": "v2" },
                { "id": "prod", "env": "prod", "desiredVersion": "v2",
                  "dependsOn": ["dev"] }
            ]}
            """;

    @Test
    void deploysInOrderAndConverges(@TempDir Path dir) {
        DeliveryHarness h = new DeliveryHarness(dir, new InMemoryLocalPlatform(),
                10_000, 2, 16, 2_000);
        h.engine.applyManifest(h.manifestStore.parse(MANIFEST), "api", false);
        ConvergenceReport r = h.engine.converge("api");
        assertTrue(r.converged(), () -> "reasons=" + r.eventReasons());
        assertTrue(r.driftDetected());
        assertEquals(StageStatus.SUCCEEDED, h.states.stage("dev").status);
        assertEquals(StageStatus.SUCCEEDED, h.states.stage("prod").status);
        assertEquals("v2", h.platform.currentVersion("prod"));
    }

    @Test
    void repeatedConvergeIsIdempotentAndSideEffectFree(@TempDir Path dir) {
        DeliveryHarness h = new DeliveryHarness(dir, new InMemoryLocalPlatform(),
                10_000, 2, 16, 2_000);
        h.engine.applyManifest(h.manifestStore.parse(MANIFEST), "api", false);
        h.engine.converge("api");
        long eventsAfterFirst = h.events.lastSeq();
        ConvergenceReport second = h.engine.converge("api");
        ConvergenceReport third = h.engine.converge("scheduler");
        assertTrue(second.converged());
        assertTrue(third.converged());
        // 两轮重复收敛：每轮每阶段一条幂等跳过事件，共 4 条，无部署副作用
        long added = h.events.lastSeq() - eventsAfterFirst;
        assertEquals(4L, added, "two idempotent-skip events per repeated converge x2");
        assertEquals(1, h.platform.trace("prod").size(),
                "no repeated deploy actions: " + h.platform.trace("prod"));
        assertEquals(1, h.platform.trace("dev").size());
    }

    @Test
    void unauthorizedTriggerRejectedWithoutVersionChange(@TempDir Path dir) {
        DeliveryHarness h = new DeliveryHarness(dir, new InMemoryLocalPlatform(),
                10_000, 2, 16, 2_000);
        h.engine.applyManifest(h.manifestStore.parse(MANIFEST), "api", false);
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                com.github.lechandonga.gitopsdeliveryjava.engine.DeliveryRejectedException.class,
                () -> h.engine.converge("backdoor"));
        assertEquals(ReasonCode.GATE_UNAUTHORIZED_TRIGGER, ex.reasonCode());
        // 拒绝不得改变已发布版本：物理平台没有任何部署
        assertEquals(0, h.platform.trace("dev").size());
        assertEquals(0, h.platform.trace("prod").size());
    }
}
