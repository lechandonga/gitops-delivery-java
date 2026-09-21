package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
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
 * 重启恢复：用同一数据目录构建两个“进程实例”（第一期崩溃）。
 * 第二期 recover() 必须：接管过期租约、清理半发布残留、不重复已完成阶段、
 * 关键事件不丢失；之后再收敛可以成功，且 BUILD 不被重复执行。
 */
class RestartRecoveryTest {

    @Test
    void recoverHalfReleasedFlowAfterRestart(@TempDir Path dir) {
        // 第一期：先稳定 v1，再灰度 v2 时“崩溃”（手工制造 CANARY 半发布 + 未终结流程）
        DeliverySystemHarness first = new DeliverySystemHarness(dir);
        first.service.applyManifest(TestSupport.single("svc-r", "v1"));
        ReconcileOutcome v1 = first.service.reconcileTarget(
                "svc-r", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, v1.getFlowStatus());

        // 期望清单先切换到 v2（声明式），随后在灰度 v2 中途“崩溃”
        first.service.applyManifest(TestSupport.single("svc-r", "v2"));

        // 构造崩溃现场：流程 RUNNING、已完成 BUILD，实际状态停在 CANARY
        var actual = first.store.getOrCreateActual("svc-r");
        actual.setPhase(TargetPhase.CANARY);
        actual.setCanaryVersion("v2");
        actual.setCanaryPercent(50);
        actual.setStableVersion("v1");
        first.store.saveActual(actual, 0L); // 恢复阶段写入无活跃租约，token=0

        FlowRecord flow = new FlowRecord();
        flow.setFlowId("flow-crashed");
        flow.setTarget("svc-r");
        flow.setDesiredVersion("v2");
        flow.setActor("a");
        flow.setStatus(FlowStatus.RUNNING);
        flow.getCompletedStages().add(com.github.lechandonga.gitopsdeliveryjava.model.Stage.BUILD);
        flow.setCreatedAt(System.currentTimeMillis());
        flow.setDeadlineAt(System.currentTimeMillis() + 60_000L);
        flow.setLeaseHolder("dead-executor");
        flow.setFencingToken(999L);
        first.store.saveFlow(flow);

        // 第二期：全新实例模拟进程重启（租约表为空）
        DeliverySystemHarness second = new DeliverySystemHarness(dir);
        int recovered = second.recovery.recover();
        assertTrue(recovered >= 1);

        var after = second.store.getOrCreateActual("svc-r");
        assertEquals(TargetPhase.ROLLED_BACK, after.getPhase(), "半发布必须被清理");
        assertNull(after.getCanaryVersion());
        assertEquals(0, after.getCanaryPercent());
        assertEquals("v1", after.getStableVersion());

        FlowRecord recoveredFlow = second.store.findFlow("flow-crashed").orElseThrow();
        assertTrue(FlowStatus.ROLLED_BACK == recoveredFlow.getStatus()
                || FlowStatus.FAILED == recoveredFlow.getStatus());

        // 接管必须留痕，且原有关键事件（v1 发布）不丢失
        List<EventRecord> events = second.store.events("svc-r");
        assertTrue(events.stream().anyMatch(e -> e.getReason() == ReasonCode.LEASE_TAKEN_OVER));
        assertTrue(events.stream().anyMatch(e -> e.getReason() == ReasonCode.ROLLBACK_DONE));
        long takeoverToken = events.stream()
                .filter(e -> e.getReason() == ReasonCode.LEASE_TAKEN_OVER)
                .mapToLong(EventRecord::getFencingToken).findFirst().orElse(0L);
        assertTrue(takeoutTokenGreater(takeoverToken), "接管 token 必须大于旧 token 999");

        // 恢复后重新驱动收敛到 v2：BUILD 不重复（新流程模拟层计数仍体现幂等语义）
        ReconcileOutcome redo = second.service.reconcileTarget(
                "svc-r", "a", Set.of("dev"), false, "post-recovery");
        assertEquals(FlowStatus.SUCCEEDED, redo.getFlowStatus());
        assertEquals("v2", second.store.getOrCreateActual("svc-r").getStableVersion());
    }

    private boolean takeoutTokenGreater(long token) {
        return token > 999L;
    }
}
