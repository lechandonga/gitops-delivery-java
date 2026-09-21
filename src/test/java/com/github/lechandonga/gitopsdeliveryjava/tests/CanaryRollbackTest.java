package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.ConvergenceReport;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.infra.InMemoryLocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanaryRollbackTest {

    @Test
    void unhealthyCanaryRollsBackToLastStableWithNoHalfRelease(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 10_000, 2, 16, 2_000);

        // 1) 先发布稳定版本 v1（无灰度）
        String v1 = """
                { "name": "app", "stages": [
                    { "id": "prod", "env": "prod", "desiredVersion": "v1" } ]}
                """;
        h.engine.applyManifest(h.manifestStore.parse(v1), "api", false);
        assertTrue(h.engine.converge("api").converged());
        assertEquals("v1", platform.currentVersion("prod"));

        // 2) 期望切到 v2，灰度 20->100；健康曲线在 20% 时不达标
        String v2 = """
                { "name": "app", "stages": [
                    { "id": "prod", "env": "prod", "desiredVersion": "v2",
                      "canary": { "weights": [20, 100], "stepWaitMillis": 10,
                                  "healthThreshold": 0.9 } } ]}
                """;
        // 仅对新版本 v2 注入不健康曲线：v2@20 观测不达标；回滚到 v1 时健康
        platform.setHealthCurveForVersion("prod", "v2", List.of(0.5, 0.5));
        h.engine.applyManifest(h.manifestStore.parse(v2), "api", false);
        ConvergenceReport r = h.engine.converge("api");

        assertFalse(r.converged());
        StageStatus st = h.states.stage("prod").status;
        assertEquals(StageStatus.ROLLED_BACK, st, "unhealthy canary must roll back");

        // 回滚后物理实际必须回到稳定 v1 且权重 100（无半发布残留）
        assertEquals("v1", platform.currentVersion("prod"));
        List<String> trace = platform.trace("prod");
        assertEquals("v1@100", trace.get(trace.size() - 1),
                "last physical action must be full rollback to v1: " + trace);

        // 原因可区分 + 版本变更可追溯
        assertTrue(r.eventReasons().stream()
                .anyMatch(x -> x.endsWith(ReasonCode.CANARY_UNHEALTHY)));
        long rollbackStarted = h.events.readAll().stream()
                .filter(e -> e.type() == EventType.ROLLBACK_STARTED).count();
        long rollbackOk = h.events.readAll().stream()
                .filter(e -> e.type() == EventType.ROLLBACK_SUCCEEDED).count();
        assertEquals(1, rollbackStarted);
        assertEquals(1, rollbackOk);
        // 状态投影记录最终承载版本为稳定版
        assertEquals("v1", h.states.stage("prod").appliedVersion);
        assertEquals("v1", h.states.stage("prod").lastStableVersion);
    }

    @Test
    void healthyCanaryPromotesFully(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 10_000, 2, 16, 2_000);
        String v2 = """
                { "name": "app", "stages": [
                    { "id": "prod", "env": "prod", "desiredVersion": "v2",
                      "canary": { "weights": [20, 60, 100], "stepWaitMillis": 5,
                                  "healthThreshold": 0.9 } } ]}
                """;
        platform.setHealthCurve("prod", List.of(1.0, 1.0, 1.0));
        h.engine.applyManifest(h.manifestStore.parse(v2), "api", false);
        ConvergenceReport r = h.engine.converge("api");
        assertTrue(r.converged(), r.eventReasons()::toString);
        assertEquals("v2", platform.currentVersion("prod"));
        assertEquals(100, h.states.stage("prod").canaryWeight);
    }
}
