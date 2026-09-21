package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 受保护环境推进门禁：未授权/未审批必须被拒绝且原因可区分；拒绝不得改变版本状态。
 */
class PolicyGateTest {

    private Manifest protectedManifest() {
        String json = """
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": {
                    "prod-db": {
                      "desiredVersion": "v9",
                      "environment": "prod",
                      "protection": {
                        "protectedEnv": true,
                        "requiredRoles": ["release-manager"],
                        "requireApproval": true
                      }
                    }
                  }
                }
                """;
        return new ManifestSerde().parse(json);
    }

    @Test
    void unauthorizedRoleIsRejectedWithoutStateChange(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(protectedManifest());

        ReconcileOutcome out = h.service.reconcileTarget(
                "prod-db", "bob", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.REJECTED, out.getFlowStatus());
        assertEquals(ReasonCode.POLICY_UNAUTHORIZED, out.getReason());
        assertEquals(TargetPhase.ABSENT, h.store.getOrCreateActual("prod-db").getPhase(),
                "门禁拒绝不得产生任何部署状态");
    }

    @Test
    void authorizedButWithoutApprovalIsDistinguishablyRejected(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(protectedManifest());

        ReconcileOutcome out = h.service.reconcileTarget(
                "prod-db", "carol", Set.of("release-manager"), false, "test");
        assertEquals(FlowStatus.REJECTED, out.getFlowStatus());
        assertEquals(ReasonCode.POLICY_APPROVAL_REQUIRED, out.getReason(),
                "角色通过但缺审批，必须与角色不足区分");
        assertEquals(TargetPhase.ABSENT, h.store.getOrCreateActual("prod-db").getPhase());
    }

    @Test
    void authorizedAndApprovedSucceeds(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(protectedManifest());

        ReconcileOutcome out = h.service.reconcileTarget(
                "prod-db", "carol", Set.of("release-manager"), true, "test");
        assertEquals(FlowStatus.SUCCEEDED, out.getFlowStatus());
        assertEquals("v9", h.store.getOrCreateActual("prod-db").getStableVersion());
    }
}
