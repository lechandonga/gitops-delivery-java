package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖编排（循环拒绝、前置未完成拒绝）与额度上限（排队/拒绝，队列有界）。
 */
class DependencyAndQuotaTest {

    @Test
    void circularDependencyIsRejectedWithCycleNodes(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "a": { "desiredVersion": "1", "dependsOn": ["b"] },
                    "b": { "desiredVersion": "1", "dependsOn": ["a"] }
                  }
                }
                """;
        Manifest m = new ManifestSerde().parse(json);
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> h.service.applyManifest(m));
        assertEquals(ReasonCode.CIRCULAR_DEPENDENCY, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("a") && ex.getMessage().contains("b"),
                "拒绝信息必须指出环上节点: " + ex.getMessage());
    }

    @Test
    void prerequisiteNotReadyIsRejectedNotSkipped(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "base": { "desiredVersion": "1" },
                    "edge": { "desiredVersion": "1", "dependsOn": ["base"] }
                  }
                }
                """;
        h.service.applyManifest(new ManifestSerde().parse(json));
        ReconcileOutcome edge = h.service.reconcileTarget("edge", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.REJECTED, edge.getFlowStatus());
        assertEquals(ReasonCode.PREREQUISITE_NOT_READY, edge.getReason());
    }

    @Test
    void queueIsBoundedAndExcessIsRejected(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 1, 1);
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "q1": { "desiredVersion": "1" },
                    "q2": { "desiredVersion": "1" },
                    "q3": { "desiredVersion": "1" }
                  }
                }
                """;
        h.service.applyManifest(new ManifestSerde().parse(json));
        // 手工占用唯一并发槽，随后对清单目标触发
        assertEquals(com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager.Admission.GRANTED,
                h.quota.tryAcquire("occupy"));

        ReconcileOutcome queued = h.service.reconcileTarget("q1", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.WAITING_QUOTA, queued.getFlowStatus());
        assertEquals(ReasonCode.QUOTA_QUEUED, queued.getReason());

        ReconcileOutcome rejected = h.service.reconcileTarget("q2", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.REJECTED, rejected.getFlowStatus());
        assertEquals(ReasonCode.QUOTA_REJECTED, rejected.getReason(), "队列满必须明确拒绝，不无界增长");
    }

}
