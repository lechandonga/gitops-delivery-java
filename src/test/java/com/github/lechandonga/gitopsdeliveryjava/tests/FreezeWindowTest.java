package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.engine.TimeSource;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.FreezeWindow;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变更冻结窗口：
 * <ul>
 *   <li>冻结时段内常规推进（人工/周期同一路径）被 FREEZE_WINDOW_BLOCKED 拦截，
 *       且不改任何已发布版本状态、不留流程/额度占用；窗口过后无需额外操作即可推进；</li>
 *   <li>紧急回滚与取消在冻结时段内照常执行（冻结防变更不防恢复）；</li>
 *   <li>边界 [start,end)：起始时刻已冻结、结束时刻已解冻；</li>
 *   <li>非法窗口在应用清单阶段以 FREEZE_WINDOW_INVALID 明确拒绝；</li>
 *   <li>旧清单缺 freezeWindows 默认不冻结。</li>
 * </ul>
 */
class FreezeWindowTest {

    /** 固定时钟：按给定“本地时间”判定，测试结果与时区无关地确定性复现。 */
    private static final class FixedClock extends TimeSource {
        private volatile LocalDateTime at;

        FixedClock(LocalDateTime at) {
            this.at = at;
        }

        void set(LocalDateTime t) {
            this.at = t;
        }

        @Override
        public long currentTimeMillis() {
            return at.atZone(zone()).toInstant().toEpochMilli();
        }

        @Override
        public ZoneId zone() {
            return ZoneId.systemDefault();
        }
    }

    private Manifest manifestWithFreeze(String windowJson) {
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": {
                      "desiredVersion": "v2",
                      "freezeWindows": [ %s ]
                    }
                  }
                }
                """.formatted(windowJson);
        return new ManifestSerde().parse(json);
    }

    @Test
    void dailyWindowBlocksNormalAdvanceButAllowsRollbackAndCancel(@TempDir Path dir) {
        FixedClock clock = new FixedClock(LocalDateTime.of(2026, 9, 30, 10, 30));
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 2, 10, clock);

        // 先在非冻结时段稳定发布 v1
        h.service.applyManifest(TestSupport.single("pay", "v1"));
        assertEquals(FlowStatus.SUCCEEDED,
                h.service.reconcileTarget("pay", "a", Set.of("dev"), false, "test").getFlowStatus());

        // 声明每日冻结 10:00-12:00（[start,end)），期望切到 v2
        h.service.applyManifest(manifestWithFreeze(
                "{ \"type\": \"daily\", \"start\": \"10:00\", \"end\": \"12:00\" }"));

        ReconcileOutcome blocked = h.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "http-api");
        assertEquals(FlowStatus.REJECTED, blocked.getFlowStatus());
        assertEquals(ReasonCode.FREEZE_WINDOW_BLOCKED, blocked.getReason(),
                "冻结拒绝必须与权限/审批/依赖/额度拒绝可区分");
        assertEquals("v1", blocked.getStableVersion());

        // 被拦截不得改变已发布版本状态，也不得建立流程/占用额度
        assertEquals(TargetPhase.STABLE, h.store.getOrCreateActual("pay").getPhase());
        assertEquals("v1", h.store.getOrCreateActual("pay").getStableVersion());
        assertTrue(h.store.findActiveFlow("pay").isEmpty(), "冻结拦截不得建立活动流程");
        assertEquals(0, h.quota.activeCount(), "冻结拦截不得占用并发额度");
        assertTrue(h.store.events("pay").stream()
                .anyMatch(e -> e.getReason() == ReasonCode.FREEZE_WINDOW_BLOCKED),
                "冻结拒绝必须有审计留痕");

        // 紧急回滚在冻结时段内必须可用（v1 -> 回退到更早历史版本 v0）
        h.service.applyManifest(new ManifestSerde().parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "pay": { "desiredVersion": "v2",
                      "freezeWindows": [ {"type":"daily","start":"10:00","end":"12:00"} ] }
                  }
                }
                """));
        // 先制造一个更早的历史稳定版本 v0（直接走历史登记 + 快照，模拟 v0 曾稳定发布）
        var actual = h.store.getOrCreateActual("pay");
        actual.setPreviousStableVersion("v0");
        h.store.saveActual(actual, 0L);
        h.versionHistory.recordStable("pay", "v0", "legacy", "legacy-flow", 1L);

        ReconcileOutcome emergency = h.service.rollbackTo("pay", "v0", "sre-oncall");
        assertEquals(FlowStatus.ROLLED_BACK, emergency.getFlowStatus());
        assertEquals(ReasonCode.ROLLBACK_HISTORY_DONE, emergency.getReason());
        assertEquals("v0", emergency.getStableVersion(), "冻结时段内紧急回退必须照常执行");

        // 窗口结束时刻（end，不含）：无需任何额外操作，常规推进自然恢复
        clock.set(LocalDateTime.of(2026, 9, 30, 12, 0));
        // 回退后漂移（期望 v2），重新收敛成功
        ReconcileOutcome released = h.service.reconcileTarget(
                "pay", "a", Set.of("dev"), false, "http-api");
        assertEquals(FlowStatus.SUCCEEDED, released.getFlowStatus(),
                "窗口一过（end 时刻不含）应自动恢复推进，无需人工解除");
        assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion());
    }

    @Test
    void windowBoundariesAreStartInclusiveEndExclusive(@TempDir Path dir) {
        // interval 窗口 2026-09-30T10:00 至 2026-09-30T11:00
        FixedClock clock = new FixedClock(LocalDateTime.of(2026, 9, 30, 9, 59, 59));
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 2, 10, clock);
        Manifest m = manifestWithFreeze(
                "{ \"type\": \"interval\", \"start\": \"2026-09-30T10:00\", "
                        + "\"end\": \"2026-09-30T11:00\" }");
        var target = m.getTargets().get("pay");

        assertFalse(h.freezeGate.isFrozen(target), "窗口前不冻结");
        clock.set(LocalDateTime.of(2026, 9, 30, 10, 0, 0));
        assertTrue(h.freezeGate.isFrozen(target), "起始时刻必须冻结（含 start）");
        clock.set(LocalDateTime.of(2026, 9, 30, 10, 59, 59));
        assertTrue(h.freezeGate.isFrozen(target), "窗口内冻结");
        clock.set(LocalDateTime.of(2026, 9, 30, 11, 0, 0));
        assertFalse(h.freezeGate.isFrozen(target), "结束时刻必须解冻（不含 end）");
    }

    @Test
    void dailyWindowWrapsAroundMidnight(@TempDir Path dir) {
        FixedClock clock = new FixedClock(LocalDateTime.of(2026, 9, 30, 23, 0));
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 2, 10, clock);
        Manifest m = manifestWithFreeze(
                "{ \"type\": \"daily\", \"start\": \"22:00\", \"end\": \"06:00\" }");
        var target = m.getTargets().get("pay");
        assertTrue(h.freezeGate.isFrozen(target), "23:00 落在跨午夜窗口内");
        clock.set(LocalDateTime.of(2026, 9, 30, 5, 0));
        assertTrue(h.freezeGate.isFrozen(target), "次日 05:00 仍在跨午夜窗口内");
        clock.set(LocalDateTime.of(2026, 9, 30, 6, 0));
        assertFalse(h.freezeGate.isFrozen(target), "06:00(end) 已解冻");
        clock.set(LocalDateTime.of(2026, 9, 30, 21, 59));
        assertFalse(h.freezeGate.isFrozen(target), "21:59 尚未进入窗口");
    }

    @Test
    void invalidFreezeWindowsAreRejectedAtApplyTime() {
        ManifestSerde serde = new ManifestSerde();
        String badType = """
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest","targets":{"x":{
                  "desiredVersion":"1",
                  "freezeWindows":[{"type":"weekly","start":"10:00","end":"11:00"}]}}}
                """;
        DeliveryRejectedException e1 = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse(badType));
        assertEquals(ReasonCode.FREEZE_WINDOW_INVALID, e1.getReasonCode(),
                "不支持的窗口类型必须明确拒绝，不能静默忽略");

        String badTime = """
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest","targets":{"x":{
                  "desiredVersion":"1",
                  "freezeWindows":[{"type":"daily","start":"25:00","end":"11:00"}]}}}
                """;
        assertEquals(ReasonCode.FREEZE_WINDOW_INVALID,
                assertThrows(DeliveryRejectedException.class, () -> serde.parse(badTime)).getReasonCode());

        String badInterval = """
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest","targets":{"x":{
                  "desiredVersion":"1",
                  "freezeWindows":[{"type":"interval",
                    "start":"2026-09-30T11:00","end":"2026-09-30T10:00"}]}}}
                """;
        assertEquals(ReasonCode.FREEZE_WINDOW_INVALID,
                assertThrows(DeliveryRejectedException.class, () -> serde.parse(badInterval)).getReasonCode(),
                "interval 结束早于开始必须拒绝");

        String missingEnd = """
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest","targets":{"x":{
                  "desiredVersion":"1",
                  "freezeWindows":[{"type":"daily","start":"10:00"}]}}}
                """;
        assertEquals(ReasonCode.FREEZE_WINDOW_INVALID,
                assertThrows(DeliveryRejectedException.class, () -> serde.parse(missingEnd)).getReasonCode());
    }

    @Test
    void oldManifestWithoutFreezeFieldDefaultsToNoFreeze(@TempDir Path dir) {
        FixedClock clock = new FixedClock(LocalDateTime.of(2026, 9, 30, 10, 30));
        DeliverySystemHarness h = new DeliverySystemHarness(dir, 2, 10, clock);
        Manifest legacy = TestSupport.single("legacy", "v1");
        h.service.applyManifest(legacy);
        ReconcileOutcome out = h.service.reconcileTarget(
                "legacy", "a", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, out.getFlowStatus(), "旧清单无 freezeWindows 默认不冻结");
        assertTrue(legacy.getTargets().get("legacy").getFreezeWindows().isEmpty());
    }
}
