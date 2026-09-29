package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度人工暂停/恢复：
 * 停在放量边界不判成败；恢复接着走、不重放不多发；暂停不长期占并发槽；
 * 额度满恢复要排队；重复调用幂等；非活动流程拒绝原因可区分；重启后仍为暂停态。
 */
class PauseResumeTest {

    private Manifest twoTargetsCanaryManifest() {
        return new ManifestSerde().parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100] }
                    },
                    "edge": { "desiredVersion": "e1" }
                  }
                }
                """);
    }

    @Test
    void pauseStopsAtCanaryBoundaryReleasesSlotAndResumeContinuesWithoutReplay(@TempDir Path dir) {
        // 并发上限 1：暂停后槽必须释放，其他目标才能推进
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 1, 10);
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "t").getFlowStatus());
        h.service.applyManifest(twoTargetsCanaryManifest());

        // 先登记暂停请求，再触发：流程应在第一个放量边界（10% 后）停住
        h.pauseControl.request("pay");
        ReconcileOutcome paused = h.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "t");
        assertEquals(FlowStatus.PAUSED, paused.getFlowStatus());
        assertEquals(ReasonCode.FLOW_PAUSED, paused.getReason());

        ActualTargetState actual = h.store.getOrCreateActual("pay");
        assertEquals(TargetPhase.CANARY, actual.getPhase());
        assertEquals(10, actual.getCanaryPercent());
        assertEquals("v2", actual.getCanaryVersion());
        assertEquals("v1", actual.getStableVersion(), "暂停不动已发布稳定版本");
        assertEquals(0, h.quota.activeCount(), "暂停不得长期占用并发额度");

        FlowRecord flow = h.store.findActiveFlow("pay").orElseThrow();
        assertEquals(FlowStatus.PAUSED, flow.getStatus());
        assertEquals(0, flow.getCanaryStepIndex(), "已完成到下标 0（10%）");

        // 挂起期间其他目标可以拿到唯一并发槽正常推进
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "edge", "a", Set.of("dev"), false, "t").getFlowStatus());

        // 重复暂停幂等、无副作用
        ReconcileOutcome pauseAgain = h.service.pause("pay", "a");
        assertEquals(FlowStatus.PAUSED, pauseAgain.getFlowStatus());
        assertEquals(ReasonCode.FLOW_PAUSED, pauseAgain.getReason());
        assertEquals(10, h.store.getOrCreateActual("pay").getCanaryPercent());

        // 恢复：从 50% 接着走，10% 绝不重放，最终成功
        ReconcileOutcome resumed = h.service.resume("pay", "a", Set.of("dev"), false);
        assertEquals(FlowStatus.SUCCEEDED, resumed.getFlowStatus());
        assertEquals(3, h.infra.deployCount("pay", "v2"),
                "v2 只应有 10/50/100 三次真实放量：暂停恢复不重放、不多发");
        assertEquals(1, h.infra.deployCount("pay", "v1"),
                "旧稳定版本 v1 不应被恢复过程重新部署");
        assertEquals(1, h.infra.promoteCount("pay", "v2"), "v2 只能推广一次");
        assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion());
        assertNull(h.store.getOrCreateActual("pay").getCanaryVersion());
        assertEquals(0, h.quota.activeCount());
    }

    @Test
    void resumeWaitsForQuotaInQueueInsteadOfFailing(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 1, 10);
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        h.service.applyManifest(twoTargetsCanaryManifest());

        h.pauseControl.request("pay");
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        assertEquals(FlowStatus.PAUSED, h.store.findActiveFlow("pay").orElseThrow().getStatus());

        // 人为占满唯一并发槽：恢复必须排队而不是直接失败
        assertEquals(com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager.Admission.GRANTED,
                h.quota.tryAcquire("occupier"));
        ReconcileOutcome waiting = h.service.resume("pay", "a", Set.of("dev"), false);
        assertEquals(FlowStatus.WAITING_QUOTA, waiting.getFlowStatus());
        assertEquals(ReasonCode.QUOTA_QUEUED, waiting.getReason());

        // 槽位释放：FIFO 提升 pay 并就地续跑到成功；同时刻推进数不超过上限 1
        h.quota.release("occupier");
        assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion());
        assertEquals(TargetPhase.STABLE, h.store.getOrCreateActual("pay").getPhase());
        assertEquals(0, h.quota.activeCount());
    }

    @Test
    void pauseAndResumeOnWrongStateAreDistinguishablyRejected(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("idle", "v1"));

        ReconcileOutcome pauseNobody = h.service.pause("idle", "a");
        assertEquals(FlowStatus.REJECTED, pauseNobody.getFlowStatus());
        assertEquals(ReasonCode.PAUSE_NOT_RUNNING, pauseNobody.getReason());

        ReconcileOutcome resumeNobody = h.service.resume("idle", "a", Set.of("dev"), false);
        assertEquals(FlowStatus.REJECTED, resumeNobody.getFlowStatus());
        assertEquals(ReasonCode.RESUME_NOT_PAUSED, resumeNobody.getReason(),
                "无暂停可恢复 与 无流程可暂停 必须可区分");
    }

    @Test
    void resumeIsBlockedByFreezeAndKeepsPausedState(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100] },
                      "freezeWindow": { "start": "00:00", "end": "23:59" }
                    }
                  }
                }
                """;
        // 先用无冻结清单把 v1 发稳定，再切到带冻结窗口的 v2 清单并暂停在 10%
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        Manifest v2 = new ManifestSerde().parse(json);
        h.service.applyManifest(v2);
        h.timeSource.pin(LocalTime.of(23, 59)); // 窗口 [00:00,23:59) 此刻尚未生效
        h.pauseControl.request("pay");
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        assertEquals(FlowStatus.PAUSED, h.store.findActiveFlow("pay").orElseThrow().getStatus());

        // 选一个必然落在 [00:00,23:59) 内的时刻
        h.timeSource.pin(LocalTime.of(12, 0));
        ReconcileOutcome blocked = h.service.resume("pay", "a", Set.of("dev"), false);
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, blocked.getReason());
        assertEquals(FlowStatus.PAUSED, h.store.findActiveFlow("pay").orElseThrow().getStatus(),
                "冻结挡下恢复不得改变暂停状态与灰度进度");
        assertEquals(10, h.store.getOrCreateActual("pay").getCanaryPercent());

        // 23:58 仍在窗口内（左闭右开，end=23:59 才恢复）
        h.timeSource.pin(LocalTime.of(12, 0));
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED,
                h.service.resume("pay", "a", Set.of("dev"), false).getReason());
        h.timeSource.pin(LocalTime.of(23, 58));
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED,
                h.service.resume("pay", "a", Set.of("dev"), false).getReason());
        // 23:59 整窗口结束，恢复立即放行并续跑成功
        h.timeSource.pin(LocalTime.of(23, 59));
        assertEquals(FlowStatus.SUCCEEDED,
                h.service.resume("pay", "a", Set.of("dev"), false).getFlowStatus());
    }

    @Test
    void queuedResumeRequeuesDuringFreezeAndRunsAfterWindowEnds(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 1, 10);
        // 冻结窗口 00:00-23:59（几乎全天），起始时刻选窗口外
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100] },
                      "freezeWindow": { "start": "00:00", "end": "23:59" }
                    }
                  }
                }
                """;
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        h.service.applyManifest(new ManifestSerde().parse(json));
        h.timeSource.pin(LocalTime.of(23, 59));
        h.pauseControl.request("pay");
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        assertEquals(FlowStatus.PAUSED, h.store.findActiveFlow("pay").orElseThrow().getStatus());

        // 占满唯一槽，恢复进入队列
        assertEquals(com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager.Admission.GRANTED,
                h.quota.tryAcquire("occ"));
        assertEquals(FlowStatus.WAITING_QUOTA,
                h.service.resume("pay", "a", Set.of("dev"), false).getFlowStatus());

        // 槽释放时正处在冻结窗口：不失败，保持暂停并重新排队，不推进
        h.timeSource.pin(LocalTime.of(12, 0));
        h.quota.release("occ");
        assertEquals(FlowStatus.PAUSED, h.store.findActiveFlow("pay").orElseThrow().getStatus());
        assertEquals(10, h.store.getOrCreateActual("pay").getCanaryPercent(),
                "冻结期间重新排队不得推进放量");
        assertTrue(h.quota.isQueued("pay"));

        // 换一个新的占槽目标在窗口结束后释放：pay 被提升并成功续跑
        assertEquals(com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager.Admission.GRANTED,
                h.quota.tryAcquire("occ2"));
        h.timeSource.pin(LocalTime.of(23, 59));
        h.quota.release("occ2");
        assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion());
    }

    @Test
    void pausedFlowSurvivesRestartAndResumeDoesNotReplay(@TempDir Path dir) {
        DeliverySystemHarness first = new DeliverySystemHarness(dir, 1, 10);
        first.service.applyManifest(TestSupport.single("pay", "v1"));
        first.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100] }
                    }
                  }
                }
                """;
        first.service.applyManifest(new ManifestSerde().parse(json));
        first.pauseControl.request("pay");
        ReconcileOutcome paused = first.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "t");
        assertEquals(FlowStatus.PAUSED, paused.getFlowStatus());
        String flowId = first.store.findActiveFlow("pay").orElseThrow().getFlowId();

        // 第二期“进程重启”：恢复扫描必须保留 PAUSED，且不清空灰度
        DeliverySystemHarness second = new DeliverySystemHarness(dir, 1, 10);
        second.recovery.recover();
        FlowRecord reloaded = second.store.findFlow(flowId).orElseThrow();
        assertEquals(FlowStatus.PAUSED, reloaded.getStatus(), "重启后仍能看出暂停状态");
        assertEquals(0, reloaded.getCanaryStepIndex());
        ActualTargetState actual = second.store.getOrCreateActual("pay");
        assertEquals(TargetPhase.CANARY, actual.getPhase(), "暂停中的灰度不应被恢复逻辑回滚");
        assertEquals(10, actual.getCanaryPercent());

        // 恢复入口仍可用，且从下标 1 续跑，不重放 10%
        ReconcileOutcome resumed = second.service.resume("pay", "a", Set.of("dev"), false);
        assertEquals(FlowStatus.SUCCEEDED, resumed.getFlowStatus());
        assertEquals(0, second.infra.executionCount("pay", "DEPLOY:10"),
                "重启后恢复不得重放已完成的 10% 放量");
        assertEquals(1, second.infra.executionCount("pay", "DEPLOY:50"));
        assertEquals(1, second.infra.executionCount("pay", "DEPLOY:100"));
        assertEquals("v2", second.store.getOrCreateActual("pay").getStableVersion());
    }

    @Test
    void failedCanaryStepIsNeverMarkedCompleteAndGetsRetriedForReal(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 1, 10);
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100] }
                    }
                  }
                }
                """;
        h.service.applyManifest(new ManifestSerde().parse(json));

        // 暂停在 10%，随后让 50% 健康不达标
        h.pauseControl.request("pay");
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "t");
        h.infra.setHealthScript("pay", "v2", 0.1d);
        ReconcileOutcome failed = h.service.resume("pay", "a", Set.of("dev"), false);
        assertEquals(FlowStatus.ROLLED_BACK, failed.getFlowStatus());
        assertEquals(ReasonCode.HEALTH_CHECK_FAILED, failed.getReason());

        FlowRecord dead = h.store.allFlows().stream()
                .filter(f -> FlowStatus.ROLLED_BACK == f.getStatus()
                        && "v2".equals(f.getDesiredVersion())
                        && f.getTerminalReason() == ReasonCode.HEALTH_CHECK_FAILED)
                .findFirst().orElseThrow();
        assertEquals(0, dead.getCanaryStepIndex(),
                "失败的 50% 步骤绝不能被记成已完成");
        assertEquals(1, h.infra.executionCount("pay", "DEPLOY:50"), "50% 确实真实执行过一次");
        assertEquals("v1", h.store.getOrCreateActual("pay").getStableVersion());

        // 下一轮收敛是一次全新尝试：50% 在健康恢复后必须被真正重放并成功
        h.infra.setHealthScript("pay", "v2", 1.0d);
        ReconcileOutcome retry = h.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "retry");
        assertEquals(FlowStatus.SUCCEEDED, retry.getFlowStatus());
        assertTrue(h.infra.executionCount("pay", "DEPLOY:50") >= 2,
                "新尝试必须真实重放之前失败/未完成的 50% 放量");
        assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion());
    }
}
