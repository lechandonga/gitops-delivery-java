package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 失败（有界重试后成功）、超时、取消三类结论必须可区分；中断不得判成功。
 */
class FailureTimeoutCancelTest {

    @Test
    void boundedRetrySucceedsAfterOneTransientFailure(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("flaky", "v1"));
        h.infra.failBuildOnce("flaky", "v1"); // 构建首次失败、后续成功

        ReconcileOutcome out = h.service.reconcileTarget(
                "flaky", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, out.getFlowStatus());
        // 模拟层幂等：BUILD 真正只执行一次（failBuildOnce 被消费即视为成功重试）
        assertEquals(1, h.infra.executionCount("flaky", "BUILD"));
    }

    @Test
    void timeoutFlowIsMarkedTimedOutNotSuccess(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        // timeoutSeconds 不能为 0（校验拒绝），用一个稳定无法健康通过的灰度配合极小 deadline
        String json = """
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                 "targets":{"slow":{"desiredVersion":"v1","timeoutSeconds":1}}}
                """;
        Manifest m = new ManifestSerde().parse(json);
        h.service.applyManifest(m);
        // 预置构建阶段抛错耗尽时间：通过让 BUILD 持续失败 + maxAttempts 大，触发 deadline
        // 这里直接用一个已经超时的流程记录来验证恢复/判定路径
        var flow = new com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord();
        flow.setFlowId("flow-late");
        flow.setTarget("slow");
        flow.setDesiredVersion("v1");
        flow.setActor("a");
        flow.setStatus(FlowStatus.RUNNING);
        flow.setCreatedAt(System.currentTimeMillis() - 10_000L);
        flow.setDeadlineAt(System.currentTimeMillis() - 1_000L);
        flow.setLeaseHolder("late");
        flow.setFencingToken(1L);
        h.store.saveFlow(flow);

        DeliverySystemHarness h2 = new DeliverySystemHarness(dir);
        h2.recovery.recover();
        var recovered = h2.store.findFlow("flow-late").orElseThrow();
        assertEquals(FlowStatus.TIMED_OUT, recovered.getStatus(),
                "超时必须与失败/取消区分，且不能判定为成功");
        assertEquals(ReasonCode.STAGE_TIMEOUT, recovered.getTerminalReason());
    }

    @Test
    void cancelReleasesLeaseAndIsNotSuccess(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("c", "v1"));
        // 建立一个进行中流程占位
        var flow = new com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord();
        flow.setFlowId("flow-run");
        flow.setTarget("c");
        flow.setDesiredVersion("v1");
        flow.setActor("a");
        flow.setStatus(FlowStatus.RUNNING);
        flow.setCreatedAt(System.currentTimeMillis());
        flow.setDeadlineAt(System.currentTimeMillis() + 60_000L);
        flow.setLeaseHolder("holder-run");
        flow.setFencingToken(h.leases.acquire("c", "holder-run", 60_000L));
        h.quota.tryAcquire("c");
        h.store.saveFlow(flow);

        ReconcileOutcome out = h.service.cancel("c", "a");
        assertEquals(FlowStatus.CANCELLED, out.getFlowStatus());
        assertEquals(ReasonCode.FLOW_CANCELLED, out.getReason());
        assertNotEquals(FlowStatus.SUCCEEDED, h.store.findFlow("flow-run").orElseThrow().getStatus());
    }
}
