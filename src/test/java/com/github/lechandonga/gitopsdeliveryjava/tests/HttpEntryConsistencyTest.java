package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.web.DeliveryController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 不同触发入口结论一致性：HTTP 控制器入口与直接调用服务入口必须给出相同结论与原因码；
 * 未知字段经 API 提交同样被拒绝。
 */
class HttpEntryConsistencyTest {

    @Test
    void httpEntryAndServiceEntryAgree(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        DeliveryController api = new DeliveryController(h.service, new ManifestSerde(), h.store,
                h.versionHistory);
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
        DeliveryController api = new DeliveryController(h.service, new ManifestSerde(), h.store,
                h.versionHistory);
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> api.applyManifest("""
                        {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                         "targets":{"z":{"desiredVersion":"1","bogus":true}}}
                        """));
        assertEquals(ReasonCode.UNKNOWN_FIELD_REJECTED, ex.getReasonCode());
    }
}
