package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import com.github.lechandonga.gitopsdeliveryjava.web.DeliveryController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 不同触发入口结论一致性：HTTP 控制器入口与直接调用服务入口必须给出相同结论与原因码；
 * 未知字段经 API 提交同样被拒绝。
 */
class HttpEntryConsistencyTest {

    @Test
    void httpEntryAndServiceEntryAgree(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        DeliveryController api = new DeliveryController(h.service, new ManifestSerde(), h.store);
        String protectedJson = """
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                 "targets":{"sec":{"desiredVersion":"v1",
                 "protection":{"protectedEnv":true,"requiredRoles":["admin"]}}}}
                """;
        api.applyManifest(protectedJson);

        // HTTP 入口：无角色 → 拒绝且原因可区分
        ReconcileOutcome httpOut = api.reconcile("sec", "bob", "", false);
        assertEquals(FlowStatus.REJECTED, httpOut.getFlowStatus());
        assertEquals(ReasonCode.POLICY_UNAUTHORIZED, httpOut.getReason());

        // 服务入口：同样的输入 → 同样结论
        ReconcileOutcome svcOut = h.service.reconcileTarget("sec", "bob",
                java.util.Set.of(), false, "direct");
        assertEquals(httpOut.getReason(), svcOut.getReason());
        assertEquals(httpOut.getFlowStatus(), svcOut.getFlowStatus());

        // 带角色 + 审批 → 成功
        ReconcileOutcome ok = api.reconcile("sec", "root", "admin", true);
        assertEquals(FlowStatus.SUCCEEDED, ok.getFlowStatus());
    }

    @Test
    void unknownFieldViaApiRejected(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        DeliveryController api = new DeliveryController(h.service, new ManifestSerde(), h.store);
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> api.applyManifest("""
                        {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                         "targets":{"z":{"desiredVersion":"1","bogus":true}}}
                        """));
        assertEquals(ReasonCode.UNKNOWN_FIELD_REJECTED, ex.getReasonCode());
    }

    @Test
    void newConsoleEndpointsWorkOverHttp(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        DeliveryController api = new DeliveryController(h.service, new ManifestSerde(), h.store);

        // v1、v2 先后稳定发布（v2 关灰度，后续还要制造一次稳定链）
        api.applyManifest("""
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                 "targets":{"svc":{"desiredVersion":"v1"}}}""");
        assertEquals(FlowStatus.SUCCEEDED, api.reconcile("svc", "a", "", false).getFlowStatus());
        api.applyManifest("""
                {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                 "targets":{"svc":{"desiredVersion":"v2"}}}""");
        assertEquals(FlowStatus.SUCCEEDED, api.reconcile("svc", "a", "", false).getFlowStatus());

        // 无活动流程：暂停/恢复必须给出可区分的 HTTP 结论
        assertEquals(ReasonCode.PAUSE_NOT_RUNNING,
                api.pause("svc", "a").getReason());
        assertEquals(ReasonCode.RESUME_NOT_PAUSED,
                api.resume("svc", "a", "", false).getReason());

        // 指定历史版本回退：从 v2 退回 v1，事件可查
        ReconcileOutcome back = api.rollbackToVersion("svc", "v1", "sre");
        assertEquals(ReasonCode.TARGET_ROLLBACK_DONE, back.getReason());
        ActualTargetState actual = h.store.getOrCreateActual("svc");
        assertEquals("v1", actual.getStableVersion());
        assertEquals(TargetPhase.ROLLED_BACK, actual.getPhase());
        assertNull(actual.getCanaryVersion());
        assertEquals(1, api.events("svc").stream()
                .filter(e -> e.getReason() == ReasonCode.TARGET_ROLLBACK_DONE
                        && "v2".equals(e.getFromVersion()) && "v1".equals(e.getToVersion())
                        && "sre".equals(e.getActor())).count());

        // 未发布/未稳定版本经 HTTP 也是可区分拒绝
        assertEquals(ReasonCode.VERSION_NOT_PUBLISHED,
                api.rollbackToVersion("svc", "vX", "sre").getReason());
    }
}
