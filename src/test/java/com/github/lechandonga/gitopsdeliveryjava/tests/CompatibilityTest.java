package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史清单兼容：缺失字段走稳定默认；未知字段/不支持 apiVersion/错误选项明确拒绝。
 */
class CompatibilityTest {

    private final ManifestSerde serde = new ManifestSerde();

    @Test
    void oldManifestWithMissingFieldsGetsStableDefaults() {
        // 模拟旧版本清单：只有最基本字段
        Manifest m = serde.parse("""
                {
                  "apiVersion": "gitops.local/v1",
                  "kind": "DeliveryManifest",
                  "targets": { "legacy": { "desiredVersion": "1.0" } }
                }
                """);
        var t = m.getTargets().get("legacy");
        assertEquals("default", t.getEnvironment(), "缺失 environment 默认 default");
        assertFalse(t.getCanary().isEnabled(), "缺失 canary 默认不灰度");
        assertEquals(3, t.getMaxAttempts(), "缺失 maxAttempts 默认 3（重试有界）");
        assertEquals(0.8d, t.getCanary().getMinHealthyRatio(), 0.0001);
    }

    @Test
    void unknownFieldIsRejectedNotIgnored() {
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse("""
                        {
                          "apiVersion": "gitops.local/v1",
                          "kind": "DeliveryManifest",
                          "targets": { "x": { "desiredVersion": "1", "replica": 3 } }
                        }
                        """));
        assertEquals(ReasonCode.UNKNOWN_FIELD_REJECTED, ex.getReasonCode());
    }

    @Test
    void unsupportedApiVersionRejected() {
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse("""
                        {"apiVersion":"gitops.local/v9","kind":"DeliveryManifest",
                         "targets":{"x":{"desiredVersion":"1"}}}
                        """));
        assertEquals(ReasonCode.API_VERSION_UNSUPPORTED, ex.getReasonCode());
    }

    @Test
    void invalidCanaryStepsRejected() {
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> serde.parse("""
                        {"apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
                         "targets":{"x":{"desiredVersion":"1",
                         "canary":{"enabled":true,"steps":[50,10]}}}}
                        """));
        assertEquals(ReasonCode.UNSUPPORTED_OPTION, ex.getReasonCode(),
                "非递增灰度步长必须被明确拒绝");
    }
}
