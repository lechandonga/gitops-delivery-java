package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度进行中的人工暂停/恢复：
 * <ul>
 *   <li>暂停停在当前放量进度，不判成功/失败；恢复从暂停的灰度步继续，
 *       已放过的百分比不重放、不会多发一次版本；</li>
 *   <li>暂停立即释放并发额度（挂起目标不白占槽）；额度满时恢复按 FIFO 排队而非失败；</li>
 *   <li>暂停/恢复对非活动/非暂停流程给出可区分拒绝，且重复调用无副作用；</li>
 *   <li>重启后暂停状态仍可识别、恢复入口仍可用；</li>
 *   <li>失败的灰度动作必须真正重放，绝不被当成已完成。</li>
 * </ul>
 */
class PauseResumeTest {

    private Manifest canaryManifest(String target, String version, Integer... steps) {
        String stepsJson = java.util.Arrays.stream(steps).map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("10,50,100");
        return new ManifestSerde().parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "%s": {
                      "desiredVersion": "%s",
                      "canary": { "enabled": true, "steps": [%s], "minHealthyRatio": 0.8 }
                    }
                  }
                }
                """.formatted(target, version, stepsJson));
    }

    /**
     * 在灰度放到 pauseAtPercent 完成后的“步后栅栏”确定性踩刹车：
     * 栅栏与发布在同一线程同步执行，这里只登记暂停请求（不阻塞），
     * 发布主循环的下一个检查点立即落地 PAUSED 并返回。
     */
    private void armPauseAfterPercent(DeliverySystemHarness h, String target, int pauseAtPercent) {
        h.infra.setCanaryBarrier(key -> {
            int pct = Integer.parseInt(key.substring(key.indexOf(':') + 1));
            if (pct == pauseAtPercent) {
                ReconcileOutcome pauseOut = h.service.pause(target, "sre");
                assertTrue(pauseOut.getReason() == ReasonCode.FLOW_PAUSED,
                        "运行中暂停应被接受: " + pauseOut.getReason());
            }
        });
    }

    /** 发起灰度发布（在独立线程运行，便于需要时与并发触发组合）。 */
    private java.util.concurrent.Future<ReconcileOutcome> startCanary(
            DeliverySystemHarness h, String target, ExecutorService pool) {
        return pool.submit(() -> h.service.reconcileTarget(
                target, "a", Set.of("dev"), false, "test"));
    }

    @Test
    void pauseStopsAtCurrentPercentAndResumeContinuesWithoutReplay(@TempDir Path dir)
            throws Exception {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "test");
        h.service.applyManifest(canaryManifest("pay", "v2", 10, 50, 100));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        armPauseAfterPercent(h, "pay", 10);
        var future = startCanary(h, "pay", pool);
        ReconcileOutcome outcome = future.get(10, TimeUnit.SECONDS);
        assertEquals(FlowStatus.PAUSED, outcome.getFlowStatus());

        ActualTargetState paused = h.store.getOrCreateActual("pay");
        assertEquals(TargetPhase.CANARY, paused.getPhase());
        assertEquals(10, paused.getCanaryPercent(), "停在放过的 10%");
        assertEquals("v2", paused.getCanaryVersion());
        assertEquals("v1", paused.getStableVersion(), "稳定指针未被改动");

        FlowRecord flow = h.store.findActiveFlow("pay").orElseThrow();
        assertEquals(FlowStatus.PAUSED, flow.getStatus());
        assertEquals(1, flow.getCanaryStepIndex(), "已记录完成 1 个灰度步");
        assertEquals(0, h.quota.activeCount(), "暂停不得长期占用并发额度");

        ReconcileOutcome firstPause = h.service.pause("pay", "sre");
        assertEquals(ReasonCode.FLOW_ALREADY_PAUSED, firstPause.getReason(),
                "重复暂停必须幂等且结论可区分");

        // 恢复：从第 2 步(50%)继续。10% 的部署不得重放，100% 后推广，只发布一次 v2
        ReconcileOutcome resumed = h.service.resume("pay", "sre");
        assertEquals(FlowStatus.SUCCEEDED, resumed.getFlowStatus());
        assertEquals("v2", resumed.getStableVersion());

        assertEquals(1, h.infra.executionCount("pay", "DEPLOY:10"),
                "暂停前放过的 10% 在恢复后绝不能重放");
        assertEquals(1, h.infra.executionCount("pay", "DEPLOY:50"));
        // DEPLOY:100/PROMOTE 各 2 次：v1 首发（非灰度走 100%+推广）+ v2 各一次；
        // v2 的 100% 放量与推广都只发生一次（没有因暂停/恢复多发一次版本）
        assertEquals(2, h.infra.executionCount("pay", "DEPLOY:100"));
        assertEquals(2, h.infra.executionCount("pay", "PROMOTE"),
                "v2 只能被推广一次，恢复不能多发一次版本");
        assertEquals("v2", h.infra.currentDeployment("pay").version());
        assertEquals(100, h.infra.currentDeployment("pay").canaryPercent());
        assertEquals(0, h.quota.activeCount());
        pool.shutdownNow();
    }

    @Test
    void pauseAndResumeOnIdleFlowGiveDistinguishableRejections(@TempDir Path dir) throws Exception {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("idle", "v1"));

        assertEquals(ReasonCode.PAUSE_NOT_RUNNING,
                h.service.pause("idle", "a").getReason(), "无活动流程：暂停原因必须明确");
        assertEquals(ReasonCode.RESUME_NOT_PAUSED,
                h.service.resume("idle", "a").getReason(), "无活动流程：恢复原因必须与暂停区分");

        // 运行中（非暂停）流程上调用恢复：同样 RESUME_NOT_PAUSED 但附带运行态，可区分
        h.infra.setCanaryBarrier(key -> {
            if (key.endsWith(":10")) {
                ReconcileOutcome r = h.service.resume("idle", "a");
                assertEquals(ReasonCode.RESUME_NOT_PAUSED, r.getReason(),
                        "运行中的流程不是暂停态，恢复必须拒绝且无副作用");
            }
        });
        h.service.applyManifest(canaryManifest("idle", "v9", 10, 100));
        ReconcileOutcome done = h.service.reconcileTarget(
                "idle", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, done.getFlowStatus(),
                "对运行中流程误调恢复不得影响正常推进");
        assertEquals(1, h.infra.executionCount("idle", "PROMOTE"));
    }

    @Test
    void resumeQueuesWhenQuotaFullAndNeverExceedsConcurrency(@TempDir Path dir) throws Exception {
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 1, 10);
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "test");
        h.service.applyManifest(canaryManifest("pay", "v2", 10, 100));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        armPauseAfterPercent(h, "pay", 10);
        var future = startCanary(h, "pay", pool);
        assertEquals(FlowStatus.PAUSED, future.get(10, TimeUnit.SECONDS).getFlowStatus());
        assertEquals(0, h.quota.activeCount());

        // 唯一并发槽被无关目标占满 → 恢复必须排队等待，而不是直接失败
        assertEquals(com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager.Admission.GRANTED,
                h.quota.tryAcquire("other-work"));
        ReconcileOutcome queued = h.service.resume("pay", "sre");
        assertEquals(FlowStatus.WAITING_QUOTA, queued.getFlowStatus());
        assertEquals(ReasonCode.FLOW_RESUME_QUEUED, queued.getReason());
        ReconcileOutcome queuedAgain = h.service.resume("pay", "sre");
        assertEquals(ReasonCode.FLOW_RESUME_QUEUED, queuedAgain.getReason(),
                "排队中重复恢复无副作用（不重复入队）");
        assertEquals(1, h.quota.queueLength());

        // 槽位释放后，排队的恢复流程被放行并最终成功；全程并发不超过上限 1
        h.quota.release("other-work");
        // FIFO 队首已具备获槽资格：显式再触发恢复（幂等）即获槽并从断点继续
        ReconcileOutcome resumed = h.service.resume("pay", "sre");
        assertEquals(FlowStatus.SUCCEEDED, resumed.getFlowStatus());
        assertTrue(h.quota.activeCount() <= 1, "任何时刻推进目标数不得超过 maxConcurrency");
        assertEquals(1, h.infra.executionCount("pay", "DEPLOY:10"), "已放量步不重放");
        assertEquals(2, h.infra.executionCount("pay", "PROMOTE"),
                "v1 首发 + v2 恢复后推广各一次，v2 没有因排队/恢复被多发一次");
        pool.shutdownNow();
    }

    @Test
    void pausedFlowSurvivesRestartAndCanBeResumed(@TempDir Path dir) throws Exception {
        DeliverySystemHarness first = new DeliverySystemHarness(dir);
        first.service.applyManifest(TestSupport.single("pay", "v1"));
        first.service.reconcileTarget("pay", "a", Set.of("dev"), false, "test");
        first.service.applyManifest(canaryManifest("pay", "v2", 10, 50, 100));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        armPauseAfterPercent(first, "pay", 10);
        var future = pool.submit(() -> first.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "test"));
        assertEquals(FlowStatus.PAUSED, future.get(10, TimeUnit.SECONDS).getFlowStatus());
        pool.shutdownNow();

        // 模拟进程重启：全新实例执行启动恢复
        DeliverySystemHarness second = new DeliverySystemHarness(dir);
        second.recovery.recover();
        FlowRecord afterRestart = second.store.findActiveFlow("pay").orElseThrow();
        assertEquals(FlowStatus.PAUSED, afterRestart.getStatus(),
                "重启后暂停状态必须仍可识别，不能被恢复流程误回滚/误判失败");
        ActualTargetState actual = second.store.getOrCreateActual("pay");
        assertEquals(TargetPhase.CANARY, actual.getPhase(), "暂停的灰度进度必须原样保留");
        assertEquals(10, actual.getCanaryPercent());

        List<EventRecord> events = second.store.events("pay");
        assertTrue(events.stream().anyMatch(e -> e.getReason() == ReasonCode.FLOW_PAUSED),
                "暂停/恢复留痕在重启后不丢失");

        // 恢复入口仍可用：从断点继续
        ReconcileOutcome resumed = second.service.resume("pay", "sre");
        assertEquals(FlowStatus.SUCCEEDED, resumed.getFlowStatus());
        assertEquals("v2", second.store.getOrCreateActual("pay").getStableVersion());
        // 重启后模拟基础设施内存计数已清空；“不重放”由流程快照 canaryStepIndex 保证：
        // 恢复后 10% 步不再作为“新的一步”执行，流程记录显示从第 2 步续跑
        FlowRecord finished = second.store.allFlows().stream()
                .filter(f -> "pay".equals(f.getTarget()) && f.getStatus() == FlowStatus.SUCCEEDED)
                .findFirst().orElseThrow();
        assertTrue(finished.getCompletedStages().size() >= 2,
                "BUILD 与 PROMOTE 都完成，灰度从断点续跑");
        assertTrue(second.store.events("pay").stream()
                        .anyMatch(e -> e.getReason() == ReasonCode.FLOW_RESUMED
                                && e.getDetail() != null && e.getDetail().contains("第 2")),
                "审计必须证明重启后从第 2 个灰度步续跑，已放量的 10% 不重放");
    }

    @Test
    void failedCanaryStepIsActuallyReplayedNotMarkedDone(@TempDir Path dir) throws Exception {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "test");
        h.service.applyManifest(canaryManifest("pay", "v2", 10, 50, 100));

        // 让 50% 这一步在第一次真正执行时失败（一次性）。
        // 先在 10% 后暂停，再注入失败、再恢复：失败动作绝不能被算作“已完成”。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        armPauseAfterPercent(h, "pay", 10);
        var future = startCanary(h, "pay", pool);
        try {
            assertEquals(FlowStatus.PAUSED, future.get(10, TimeUnit.SECONDS).getFlowStatus());
            h.infra.failDeployCanaryOnce("pay", "v2", 50);
            ReconcileOutcome resumed = h.service.resume("pay", "sre");
            // 50% 首次失败被有界重试真实重放后成功，最终发布成功
            assertEquals(FlowStatus.SUCCEEDED, resumed.getFlowStatus(),
                    "失败/未完成的灰度动作必须真正重放，不能当成已完成");
            assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion());
            // 10% 只放一次，50% 真实成功一次（失败那次不计完成）
            assertEquals(1, h.infra.executionCount("pay", "DEPLOY:10"));
            assertEquals(1, h.infra.executionCount("pay", "DEPLOY:50"));
        } finally {
            pool.shutdownNow();
        }
    }
}
