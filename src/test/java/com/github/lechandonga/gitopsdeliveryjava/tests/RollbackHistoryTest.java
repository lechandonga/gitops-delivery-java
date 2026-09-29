package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.Stage;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回退到指定历史稳定版本：
 * <ul>
 *   <li>成功线：v3→v2→v1 依次稳定发布后，直接退到 v1，事件可追溯 from/to/actor，
 *       回退后无半发布残留；漂移收敛语义不变（期望更高时下一轮重新推到期望版本）；</li>
 *   <li>拒绝线：从未在该目标稳定发布过的版本 → TARGET_VERSION_UNKNOWN
 *       （与 NO_STABLE_VERSION 区分）；目标有活动流程 → ROLLBACK_TARGET_BUSY；</li>
 *   <li>历史重建：只有旧事件 WAL / 旧快照的老数据目录，历史版本不读丢、读取不报错。</li>
 * </ul>
 */
class RollbackHistoryTest {

    private void publishStable(DeliverySystemHarness h, String target, String version) {
        h.service.applyManifest(TestSupport.single(target, version));
        ReconcileOutcome out = h.service.reconcileTarget(
                target, "alice", Set.of("release-manager"), true, "test");
        assertEquals(FlowStatus.SUCCEEDED, out.getFlowStatus(),
                "前置稳定发布 " + version + " 必须成功: " + out.getReason());
    }

    @Test
    void rollbackToNamedHistoricalVersionIsTraceableAndLeavesNoResidue(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        publishStable(h, "pay", "v1");
        publishStable(h, "pay", "v2");
        publishStable(h, "pay", "v3");

        // 期望清单保持 v3；直接退回到更早的 v1
        h.service.applyManifest(TestSupport.single("pay", "v3"));
        ReconcileOutcome back = h.service.rollbackTo("pay", "v1", "sre-bob");
        assertEquals(FlowStatus.ROLLED_BACK, back.getFlowStatus());
        assertEquals(ReasonCode.ROLLBACK_HISTORY_DONE, back.getReason());
        assertEquals("v1", back.getStableVersion());

        ActualTargetState actual = h.store.getOrCreateActual("pay");
        assertEquals(TargetPhase.ROLLED_BACK, actual.getPhase());
        assertEquals("v1", actual.getStableVersion());
        assertNull(actual.getCanaryVersion(), "回退后不得残留灰度版本");
        assertEquals(0, actual.getCanaryPercent());

        // 审计可追溯：从 v3 退到 v1、触发者是谁
        List<EventRecord> events = h.store.events("pay");
        EventRecord trace = events.stream()
                .filter(e -> e.getReason() == ReasonCode.ROLLBACK_HISTORY_DONE)
                .reduce((a, b) -> b).orElseThrow();
        assertEquals("v3", trace.getInputVersion(), "事件必须记录回退前版本");
        assertEquals("v1", trace.getTargetVersion(), "事件必须记录回退目标版本");
        assertTrue(trace.getActor().contains("sre-bob"), "事件必须记录触发者");

        // 漂移收敛语义保持原样：期望 v3 高于当前 v1，下一轮重新推到 v3
        ReconcileOutcome reConverge = h.service.reconcileTarget(
                "pay", "alice", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, reConverge.getFlowStatus());
        assertEquals("v3", h.store.getOrCreateActual("pay").getStableVersion());

        // 历史目录中三个版本都在（回退本身不擦除历史）
        List<String> versions = h.versionHistory.history("pay").stream()
                .map(e -> e.version()).toList();
        assertEquals(List.of("v1", "v2", "v3"), versions);
    }

    @Test
    void rollbackToNeverPublishedVersionIsDistinguishablyRejected(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        publishStable(h, "pay", "v1");
        publishStable(h, "pay", "v2");
        h.service.applyManifest(TestSupport.single("pay", "v2"));

        // 该目标从未发布过 v42：与“没有可回滚版本”明确区分
        ReconcileOutcome unknown = h.service.rollbackTo("pay", "v42", "sre");
        assertEquals(FlowStatus.REJECTED, unknown.getFlowStatus());
        assertEquals(ReasonCode.TARGET_VERSION_UNKNOWN, unknown.getReason());
        assertEquals("v2", h.store.getOrCreateActual("pay").getStableVersion(),
                "拒绝不得改变已发布版本状态");
        assertEquals(TargetPhase.STABLE, h.store.getOrCreateActual("pay").getPhase());

        // 别的目标发布过的版本也不能用于本目标
        publishStable(h, "shop", "v9");
        h.service.applyManifest(TestSupport.single("pay", "v2"));
        ReconcileOutcome foreign = h.service.rollbackTo("pay", "v9", "sre");
        assertEquals(ReasonCode.TARGET_VERSION_UNKNOWN, foreign.getReason(),
                "非本目标发过的版本必须拒绝");
    }

    @Test
    void rollbackWhileFlowActiveIsRejectedNotRunConcurrently(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        publishStable(h, "pay", "v1");
        h.service.applyManifest(TestSupport.single("pay", "v2"));

        // 手工放置一个活动（暂停中）流程 + 灰度半发布现场
        FlowRecord flow = new FlowRecord();
        flow.setFlowId("flow-active");
        flow.setTarget("pay");
        flow.setDesiredVersion("v2");
        flow.setActor("a");
        flow.setStatus(FlowStatus.PAUSED);
        flow.setCanaryStepIndex(1);
        flow.setCreatedAt(System.currentTimeMillis());
        flow.setDeadlineAt(System.currentTimeMillis() + 60_000L);
        flow.setLeaseHolder("holder");
        flow.setFencingToken(0L);
        h.store.saveFlow(flow);

        ReconcileOutcome busy = h.service.rollbackTo("pay", "v1", "sre");
        assertEquals(FlowStatus.REJECTED, busy.getFlowStatus());
        assertEquals(ReasonCode.ROLLBACK_TARGET_BUSY, busy.getReason(),
                "与进行中流程冲突时必须给确定结论，不能两边同时推");

        // 取消（冻结期也允许的恢复动作）后，回退即可执行
        ReconcileOutcome cancelled = h.service.cancel("pay", "sre");
        assertEquals(FlowStatus.CANCELLED, cancelled.getFlowStatus());
        ReconcileOutcome afterCancel = h.service.rollbackTo("pay", "v1", "sre");
        assertEquals(FlowStatus.ROLLED_BACK, afterCancel.getFlowStatus());
        assertEquals("v1", afterCancel.getStableVersion());
    }

    @Test
    void versionHistoryRebuiltFromLegacyEventsAndSnapshots(@TempDir Path dir) throws Exception {
        // 构造“老数据”：没有 history/ 目录，只有旧事件 WAL + 旧快照，
        // 且事件/快照里带有新版本不认识的额外字段（宽容读取必须不报错）。
        java.nio.file.Path dataDir = dir;
        java.nio.file.Path actualDir = dataDir.resolve("actual");
        java.nio.file.Files.createDirectories(actualDir);
        java.nio.file.Files.writeString(dataDir.resolve("manifest.json"), """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": { "legacy": { "desiredVersion": "v3" } }
                }
                """);
        // 旧事件：v1/v2/v3 三次 PROMOTE 成功，混入一个未知字段 futureField
        String[] versions = {"v1", "v2", "v3"};
        long seq = 0;
        for (String v : versions) {
            seq++;
            String line = """
                    {"seq":%d,"timestamp":%d,"flowId":"old-%s","target":"legacy","actor":"old-alice",
                     "fromState":"CANARY","toState":"STABLE","stage":"PROMOTE_STABLE",
                     "reason":"NONE","inputVersion":"%s","fencingToken":0,"futureField":"x"}
                    """.formatted(seq, seq * 1000L, v, v).replaceAll("\\s+", "");
            java.nio.file.Files.writeString(dataDir.resolve("events.log"),
                    line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        // 旧实际快照也带未知字段
        java.nio.file.Files.writeString(actualDir.resolve("legacy.json"), """
                {"name":"legacy","phase":"STABLE","stableVersion":"v3",
                 "previousStableVersion":"v2","futureField":42}
                """);

        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        List<String> rebuilt = h.versionHistory.history("legacy").stream()
                .map(e -> e.version()).toList();
        assertTrue(rebuilt.containsAll(List.of("v1", "v2", "v3")),
                "老事件里的历史稳定版本必须全部重建出来: " + rebuilt);

        // 重建出的历史可直接支撑指定版本回退
        ReconcileOutcome back = h.service.rollbackTo("legacy", "v1", "sre");
        assertEquals(FlowStatus.ROLLED_BACK, back.getFlowStatus());
        assertEquals("v1", back.getStableVersion());

        // 事件回放本身也不因未知字段失败
        assertFalse(h.store.events("legacy").isEmpty());
    }
}
