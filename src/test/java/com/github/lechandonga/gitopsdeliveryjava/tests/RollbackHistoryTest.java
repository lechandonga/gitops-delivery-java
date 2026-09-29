package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回退到指定历史版本：
 * 成功路径（可追溯、无残留、之后按清单重新漂移）、
 * 拒绝路径（从未发布/从未稳定/有进行中流程，原因码互不相同）、
 * 以及从旧事件/旧快照重建版本历史的兼容读取。
 */
class RollbackHistoryTest {

    @Test
    void rollbackToSpecifiedHistoryVersionIsTracedAndConvergesAfterwards(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        // 依次稳定发布 v1 -> v2 -> v3（无灰度直推，每个都稳定过）
        for (String v : new String[]{"v1", "v2", "v3"}) {
            h.service.applyManifest(TestSupport.single("svc", v));
            assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                    "svc", "a", Set.of("dev"), false, "t").getFlowStatus());
        }
        assertEquals("v3", h.store.getOrCreateActual("svc").getStableVersion());

        // 直接退回到更早的 v1（跳过“上一个稳定版本”v2）
        ReconcileOutcome back = h.service.rollbackToVersion("svc", "v1", "sre-bob");
        assertEquals(FlowStatus.ROLLED_BACK, back.getFlowStatus());
        assertEquals(ReasonCode.TARGET_ROLLBACK_DONE, back.getReason(),
                "指定版本回退必须与“回滚到上一个稳定版本”区分");
        assertEquals("v1", back.getStableVersion());

        var actual = h.store.getOrCreateActual("svc");
        assertEquals(TargetPhase.ROLLED_BACK, actual.getPhase());
        assertEquals("v1", actual.getStableVersion());
        assertNull(actual.getCanaryVersion(), "回退完成不得留半发布残留");
        assertEquals(0, actual.getCanaryPercent());

        // 可追溯：事件带 fromVersion/toVersion/actor
        EventRecord trace = h.store.events("svc").stream()
                .filter(e -> e.getReason() == ReasonCode.TARGET_ROLLBACK_DONE)
                .findFirst().orElseThrow();
        assertEquals("v3", trace.getFromVersion());
        assertEquals("v1", trace.getToVersion());
        assertEquals("sre-bob", trace.getActor());
        // 回退本身也是一次流程快照（可审计）
        assertTrue(h.store.allFlows().stream().anyMatch(f ->
                "ROLLBACK".equals(f.getKind()) && "v1".equals(f.getRollbackToVersion())
                        && f.getStatus() == FlowStatus.ROLLED_BACK
                        && f.getTerminalReason() == ReasonCode.TARGET_ROLLBACK_DONE));

        // 漂移收敛语义不变：清单期望 v3 比回退版本高，下一轮重新推
        ReconcileOutcome redrive = h.service.reconcileTarget(
                "svc", "a", Set.of("dev"), false, "redrive");
        assertEquals(FlowStatus.SUCCEEDED, redrive.getFlowStatus());
        assertEquals("v3", h.store.getOrCreateActual("svc").getStableVersion());
    }

    @Test
    void rollbackToVersionRejectsAreDistinguishable(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        // v1 稳定；v2 灰度但健康不达标自动回滚 -> v2 发布过但从未稳定
        h.service.applyManifest(TestSupport.single("svc", "v1"));
        h.service.reconcileTarget("svc", "a", Set.of("dev"), false, "t");
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "svc": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 100], "minHealthyRatio": 0.8 }
                    }
                  }
                }
                """;
        h.service.applyManifest(new com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde()
                .parse(json));
        h.infra.setHealthScript("svc", "v2", 0.0d);
        ReconcileOutcome failed = h.service.reconcileTarget(
                "svc", "a", Set.of("dev"), false, "t");
        assertEquals(FlowStatus.ROLLED_BACK, failed.getFlowStatus());
        assertEquals("v1", h.store.getOrCreateActual("svc").getStableVersion());

        // 根本不是这个目标的版本
        ReconcileOutcome unknown = h.service.rollbackToVersion("svc", "v999", "sre");
        assertEquals(ReasonCode.VERSION_NOT_PUBLISHED, unknown.getReason());

        // 发布过（只到灰度）但从未稳定
        ReconcileOutcome neverStable = h.service.rollbackToVersion("svc", "v2", "sre");
        assertEquals(ReasonCode.VERSION_NEVER_STABLE, neverStable.getReason(),
                "“从未稳定”必须与“从未发布”“没有可回滚版本”三者区分");

        // v1 稳定过，可以退回
        ReconcileOutcome ok = h.service.rollbackToVersion("svc", "v1", "sre");
        assertEquals(ReasonCode.TARGET_ROLLBACK_DONE, ok.getReason());

        // 被拒两次不得改变已发布版本
        assertEquals("v1", h.store.getOrCreateActual("svc").getStableVersion());
    }

    @Test
    void rollbackToVersionConflictsWithActiveFlow(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("svc", "v1"));
        h.service.reconcileTarget("svc", "a", Set.of("dev"), false, "t");
        h.service.applyManifest(new com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde()
                .parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "svc": {
                      "desiredVersion": "v2",
                      "canary": { "enabled": true, "steps": [10, 50, 100] }
                    }
                  }
                }
                """));
        // 暂停一个灰度中的活动流程
        h.pauseControl.request("svc");
        assertEquals(FlowStatus.PAUSED, h.service.reconcileTarget(
                "svc", "a", Set.of("dev"), false, "t").getFlowStatus());

        // 即使目标版本合法、且 v1 是稳定版本，也不能与暂停流程同时推进
        ReconcileOutcome conflict = h.service.rollbackToVersion("svc", "v1", "sre");
        assertEquals(ReasonCode.ACTIVE_FLOW_CONFLICT, conflict.getReason());
        assertEquals(TargetPhase.CANARY, h.store.getOrCreateActual("svc").getPhase(),
                "冲突拒绝不得改动灰度现场");
        assertEquals(10, h.store.getOrCreateActual("svc").getCanaryPercent());

        // 取消后再退回得到确定结论
        h.service.cancel("svc", "a");
        ReconcileOutcome ok = h.service.rollbackToVersion("svc", "v1", "sre");
        assertEquals(ReasonCode.TARGET_ROLLBACK_DONE, ok.getReason());
    }

    @Test
    void versionHistoryRebuiltFromOldEventsAndSnapshots(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("svc", "v1"));
        h.service.reconcileTarget("svc", "a", Set.of("dev"), false, "t");
        h.service.applyManifest(TestSupport.single("svc", "v2"));
        h.service.reconcileTarget("svc", "a", Set.of("dev"), false, "t");

        // 新组件实例（无内存状态）必须只凭磁盘上的事件 WAL + 快照重建历史
        var rebuiltHistory = new com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory(h.store);
        assertEquals(java.util.List.of("v1", "v2"), rebuiltHistory.stableVersions("svc"),
                "版本历史必须能从持久化旧数据重建，不能读丢");
        assertTrue(rebuiltHistory.publishedVersions("svc").containsAll(
                java.util.Set.of("v1", "v2")));

        // 手工写入一条“旧格式”回退事件（缺 fromVersion/toVersion 等新字段）：宽容读取不报错，
        // 且不破坏已有历史
        com.github.lechandonga.gitopsdeliveryjava.model.EventRecord oldStyle =
                new com.github.lechandonga.gitopsdeliveryjava.model.EventRecord();
        oldStyle.setTarget("svc");
        oldStyle.setActor("legacy");
        oldStyle.setFlowId("legacy-rollback");
        oldStyle.setFromState("STABLE");
        oldStyle.setToState(TargetPhase.ROLLED_BACK.name());
        oldStyle.setReason(ReasonCode.ROLLBACK_DONE);
        oldStyle.setDetail("旧版本写入的回滚事件（无 from/to 版本字段）");
        h.store.appendEvent(oldStyle, 0L);

        var rebuiltAgain = new com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory(h.store);
        assertEquals(java.util.List.of("v1", "v2"), rebuiltAgain.stableVersions("svc"),
                "旧事件缺新字段时历史不报错、不丢失");
        // 事件本身也能被宽容读回
        assertTrue(h.store.events("svc").stream()
                .anyMatch(e -> "legacy-rollback".equals(e.getFlowId())));
    }

    @Test
    void manifestWithoutFreezeFieldAndOldSnapshotReadAreCompatible(@TempDir Path dir) {
        // 旧清单缺 freezeWindow：解析与发布都正常（默认不冻结）
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("svc", "v1"));
        h.service.reconcileTarget("svc", "a", Set.of("dev"), false, "t");

        // 新实例读取本地产出的流程快照：新字段均有稳定默认值
        DeliverySystemHarness second = new DeliverySystemHarness(dir);
        var flows = second.store.allFlows();
        assertEquals(1, flows.size());
        assertEquals("PUBLISH", flows.get(0).getKind(), "旧快照 kind 缺省为 PUBLISH");
        assertEquals(-1, flows.get(0).getCanaryStepIndex(),
                "未走灰度的流程 canaryStepIndex=-1（与旧快照缺省一致）");
        assertEquals(TargetPhase.STABLE, second.store.getOrCreateActual("svc").getPhase());
        assertEquals("v1", second.store.getOrCreateActual("svc").getStableVersion());

        // 真正的旧快照（手工剔除全部新字段）必须能被宽容读入且新字段取默认值
        String legacySnapshot = """
                {
                  "flowId": "flow-legacy",
                  "target": "svc",
                  "desiredVersion": "v1",
                  "actor": "legacy",
                  "status": "SUCCEEDED",
                  "completedStages": ["BUILD", "DEPLOY_CANARY", "PROMOTE_STABLE"],
                  "terminalReason": "NONE",
                  "createdAt": 1,
                  "updatedAt": 1,
                  "deadlineAt": 999,
                  "attempts": 1,
                  "fencingToken": 0,
                  "leaseHolder": "legacy"
                }
                """;
        var legacy = new com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde()
                .readLenient(legacySnapshot,
                com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord.class);
        assertEquals(-1, legacy.getCanaryStepIndex(), "旧快照缺 canaryStepIndex 必须取默认 -1");
        assertEquals("PUBLISH", legacy.getKind());
        assertFalse(legacy.isResumeRequested());
    }
}
