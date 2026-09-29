package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 变更冻结窗口：窗口内常规推进（人工/周期）被独立原因码挡下且不改版本状态；
 * 紧急回滚与取消不受限；窗口边界左闭右开，end 时刻自动恢复；非法窗口清单被拒绝。
 */
class FreezeWindowTest {

    private Manifest manifestWithFreeze(String target, String version, String start, String end) {
        return new ManifestSerde().parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "%s": {
                      "desiredVersion": "%s",
                      "freezeWindow": { "start": "%s", "end": "%s" }
                    }
                  }
                }
                """.formatted(target, version, start, end));
    }

    @Test
    void normalAdvanceBlockedDuringFreezeWithoutStateChange(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(manifestWithFreeze("pay", "v1", "22:00", "06:00"));
        h.timeSource.pin(LocalTime.of(23, 30));

        ReconcileOutcome blocked = h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual");
        assertEquals(FlowStatus.REJECTED, blocked.getFlowStatus());
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, blocked.getReason(),
                "冻结拒绝必须与权限/审批/依赖/额度拒绝可区分");
        assertEquals(TargetPhase.ABSENT, h.store.getOrCreateActual("pay").getPhase(),
                "被挡下的触发不能对已发布版本状态产生任何影响");

        // 周期收敛入口（系统身份）同样被挡
        ReconcileOutcome scheduled = h.service.reconcileAll("scheduler", Set.of("system"), false);
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, scheduled.getReason());
        assertEquals(TargetPhase.ABSENT, h.store.getOrCreateActual("pay").getPhase());
    }

    @Test
    void emergencyRollbackStillWorksDuringFreeze(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        // 先在非冻结时间发布 v1
        h.service.applyManifest(manifestWithFreeze("pay", "v1", "22:00", "06:00"));
        h.timeSource.pin(LocalTime.of(12, 0));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getFlowStatus());

        // 进入冻结窗口，手动紧急回滚必须照常执行（回滚到唯一稳定版本自身 = 恢复语义放行）
        h.timeSource.pin(LocalTime.of(23, 0));
        ReconcileOutcome rollback = h.service.rollback("pay", "sre-oncall");
        assertEquals(FlowStatus.ROLLED_BACK, rollback.getFlowStatus());
        assertNotEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, rollback.getReason());

        // 同一冻结时段内常规推进仍然被挡
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getReason());
    }

    @Test
    void windowIsLeftClosedRightOpenAndRecoveryNeedsNoExtraAction(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(manifestWithFreeze("pay", "v1", "10:00", "11:00"));

        h.timeSource.pin(LocalTime.of(9, 59));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getFlowStatus());

        h.service.applyManifest(manifestWithFreeze("pay", "v2", "10:00", "11:00"));
        // start 时刻（含）即冻结
        h.timeSource.pin(LocalTime.of(10, 0));
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getReason());
        // end 时刻立即恢复，无需额外操作
        h.timeSource.pin(LocalTime.of(11, 0));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getFlowStatus());
    }

    @Test
    void crossMidnightWindowIsJudgedCorrectly(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(manifestWithFreeze("pay", "v1", "22:00", "06:00"));

        // 白天非冻结：正常推进
        h.timeSource.pin(LocalTime.of(12, 0));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getFlowStatus());

        // 切版本 v2 后验证跨午夜窗口边界
        h.service.applyManifest(manifestWithFreeze("pay", "v2", "22:00", "06:00"));
        // 凌晨在窗口内
        h.timeSource.pin(LocalTime.of(2, 0));
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getReason());
        // 21:59 尚未进入窗口：v2 推进成功
        h.timeSource.pin(LocalTime.of(21, 59));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getFlowStatus());
        // 22:00（含）进入窗口：再制造漂移（v3），推进必须被挡
        h.service.applyManifest(manifestWithFreeze("pay", "v3", "22:00", "06:00"));
        h.timeSource.pin(LocalTime.of(22, 0));
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getReason());
        // 06:00 整（end）已恢复，无需额外操作即可推进
        h.timeSource.pin(LocalTime.of(6, 0));
        assertEquals(FlowStatus.SUCCEEDED, h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "manual").getFlowStatus());
    }

    @Test
    void invalidFreezeWindowIsRejectedAtManifestApply() {
        ManifestSerde serde = new ManifestSerde();
        // 时间格式明显不合法
        DeliveryRejectedException badFormat = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse("""
                        {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                         "targets":{"x":{"desiredVersion":"1",
                         "freezeWindow":{"start":"25:0","end":"06:00"}}}}
                        """));
        assertEquals(ReasonCode.MANIFEST_INVALID, badFormat.getReasonCode());

        // start==end 语义歧义，明确拒绝，不静默降级
        DeliveryRejectedException same = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse("""
                        {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                         "targets":{"x":{"desiredVersion":"1",
                         "freezeWindow":{"start":"06:00","end":"06:00"}}}}
                        """));
        assertEquals(ReasonCode.UNSUPPORTED_OPTION, same.getReasonCode());

        // 缺 end 也不能静默忽略
        DeliveryRejectedException missingEnd = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse("""
                        {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                         "targets":{"x":{"desiredVersion":"1",
                         "freezeWindow":{"start":"06:00"}}}}
                        """));
        assertEquals(ReasonCode.MANIFEST_INVALID, missingEnd.getReasonCode());
    }

    @Test
    void manifestWithoutFreezeWindowNeverFreezes(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("legacy", "v1")); // 旧清单无该字段
        h.timeSource.pin(LocalTime.of(3, 33));
        ReconcileOutcome out = h.service.reconcileTarget(
                "legacy", "alice", Set.of("dev"), false, "manual");
        assertEquals(FlowStatus.SUCCEEDED, out.getFlowStatus(),
                "旧清单缺 freezeWindow 默认不冻结");
    }
}
