package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度放量健康不达标 → 自动回滚到上一个稳定版本；
 * 回滚后无 CANARY 残留；版本变更在事件流中可追溯。
 */
class CanaryRollbackTest {

    @Test
    void unhealthyCanaryRollsBackToPreviousStable(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);

        // 第一步：先发布 v1 作为稳定版本（关闭灰度）
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        ReconcileOutcome v1 = h.service.reconcileTarget("pay", "alice", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, v1.getFlowStatus());

        // 第二步：清单切换为 v2 并开启灰度；预置 v2 健康率 0.1（低于默认阈值 0.8）
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100], "minHealthyRatio": 0.8 }
                    }
                  }
                }
                """;
        Manifest v2Manifest = new ManifestSerde().parse(json);
        h.service.applyManifest(v2Manifest);
        h.infra.setHealthScript("pay", "v2", 0.1d);

        ReconcileOutcome out = h.service.reconcileTarget("pay", "alice", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.ROLLED_BACK, out.getFlowStatus());
        assertEquals(ReasonCode.HEALTH_CHECK_FAILED, out.getReason(),
                "健康不达标与普通失败必须可区分");
        assertEquals("v1", out.getStableVersion(), "必须回滚到上一个稳定版本");

        ActualTargetState actual = h.store.getOrCreateActual("pay");
        assertEquals(TargetPhase.ROLLED_BACK, actual.getPhase());
        assertNull(actual.getCanaryVersion(), "回滚后不得残留灰度版本");
        assertEquals(0, actual.getCanaryPercent());
        assertEquals("v1", actual.getStableVersion());

        // 版本变更可追溯：事件流同时包含健康失败与回滚记录
        List<EventRecord> events = h.store.events("pay");
        assertTrue(events.stream().anyMatch(e -> e.getReason() == ReasonCode.HEALTH_CHECK_FAILED));
        assertTrue(events.stream().anyMatch(e -> e.getReason() == ReasonCode.HEALTH_CHECK_FAILED
                && e.getToState().equals(TargetPhase.ROLLED_BACK.name())));
    }
}
