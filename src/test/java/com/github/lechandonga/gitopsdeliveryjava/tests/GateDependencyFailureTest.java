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

class GateDependencyFailureTest {

    @Test
    void protectedEnvRequiresApprovalAndRejectionKeepsVersion(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 10_000, 2, 16, 2_000);
        String json = """
                { "name": "app", "protectedEnv": true, "requireApproval": true,
                  "stages": [ { "id": "prod", "env": "prod", "desiredVersion": "v2" } ] }
                """;
        h.engine.applyManifest(h.manifestStore.parse(json), "api", false);

        ConvergenceReport rejected = h.engine.converge("api");
        assertFalse(rejected.converged());
        assertTrue(rejected.eventReasons().contains("prod:"
                + ReasonCode.GATE_PROTECTED_NO_APPROVAL));
        // 拒绝不得改变已发布版本：无任何部署
        assertEquals(0, platform.trace("prod").size());

        h.engine.grantApproval("prod", "api");
        ConvergenceReport approved = h.engine.converge("api");
        assertTrue(approved.converged(), () -> approved.eventReasons().toString());
        assertEquals("v2", platform.currentVersion("prod"));
    }

    @Test
    void downstreamRejectedWhenPrerequisiteNotDone(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 10_000, 2, 16, 2_000);
        // dev 阶段永远失败（maxAttempts=1），prod 依赖 dev
        String json = """
                { "name": "app", "stages": [
                    { "id": "dev", "env": "dev", "desiredVersion": "v2", "maxAttempts": 1 },
                    { "id": "prod", "env": "prod", "desiredVersion": "v2",
                      "dependsOn": ["dev"] }
                ]}
                """;
        platform.failNextDeploy("dev");
        h.engine.applyManifest(h.manifestStore.parse(json), "api", false);
        ConvergenceReport r = h.engine.converge("api");
        assertFalse(r.converged());
        assertEquals(StageStatus.FAILED, h.states.stage("dev").status);
        // prod 必须以前置未完成被拒绝，而不是被静默跳过或执行
        assertTrue(r.eventReasons().contains("prod:"
                + ReasonCode.PREREQUISITE_NOT_DONE), r.eventReasons()::toString);
        assertEquals(0, platform.trace("prod").size());
    }

    @Test
    void boundedRetriesThenFailsWithoutJudgingSuccess(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 10_000, 1, 4, 2_000);
        String json = """
                { "name": "app", "stages": [
                    { "id": "dev", "env": "dev", "desiredVersion": "v2", "maxAttempts": 2 }
                ]}
                """;
        // 每次部署都失败 -> 尝试恰为 2 次（有界），终态 FAILED，不会被判定成功
        platform.failNextDeploy("dev");
        platform.failNextDeploy("dev");
        h.engine.applyManifest(h.manifestStore.parse(json), "api", false);
        ConvergenceReport r = h.engine.converge("api");
        assertFalse(r.converged());
        StageStatus st = h.states.stage("dev").status;
        assertTrue(st == StageStatus.FAILED, "expected FAILED, got " + st);
        long started = h.events.readAll().stream()
                .filter(e -> e.type().name().equals("STAGE_STARTED")).count();
        assertEquals(2, started, "exactly bounded attempts");
        long succeeded = h.events.readAll().stream()
                .filter(e -> e.type().name().equals("STAGE_SUCCEEDED")).count();
        assertEquals(0, succeeded, "interrupted/failed flow must not be judged success");
    }
}
