package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;
import com.github.lechandonga.gitopsdeliveryjava.store.ManifestStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestCompatibilityTest {

    private ManifestStore store(Path dir) {
        return new ManifestStore(dir);
    }

    @Test
    void missingFieldsGetStableDefaults() {
        String json = """
                { "name": "app", "stages": [
                    { "id": "s1", "env": "dev", "desiredVersion": "v1" }
                ]}
                """;
        DeliveryManifest m = store(Path.of("target/junit-nonexistent")).parse(json);
        assertEquals(ManifestStore.DEFAULT_SCHEMA_VERSION, m.schemaVersion());
        assertFalse(m.protectedEnv());
        assertFalse(m.requireApproval());
        var s = m.stages().get(0);
        assertTrue(s.dependsOn().isEmpty());
        assertNull(s.canary());
        assertEquals(ManifestStore.DEFAULT_TIMEOUT_MS, s.executionTimeoutMillis());
        assertEquals(ManifestStore.DEFAULT_MAX_ATTEMPTS, s.maxAttempts());
    }

    @Test
    void unknownFieldIsRejectedNotIgnored(@TempDir Path dir) {
        String json = """
                { "name": "app", "mysteryOption": 42, "stages": [
                    { "id": "s1", "env": "dev", "desiredVersion": "v1" }
                ]}
                """;
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> store(dir).parse(json));
        assertEquals(ReasonCode.GATE_UNSUPPORTED_OPTION, ex.reasonCode());
        assertTrue(ex.getMessage().contains("mysteryOption"));
    }

    @Test
    void unsupportedSchemaRejected(@TempDir Path dir) {
        String json = """
                { "schemaVersion": "delivery.gitops/v9", "name": "app", "stages": [
                    { "id": "s1", "env": "dev", "desiredVersion": "v1" }
                ]}
                """;
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> store(dir).parse(json));
        assertEquals(ReasonCode.GATE_UNSUPPORTED_OPTION, ex.reasonCode());
    }

    @Test
    void invalidCanaryWeightsRejected(@TempDir Path dir) {
        String json = """
                { "name": "app", "stages": [
                    { "id": "s1", "env": "dev", "desiredVersion": "v1",
                      "canary": { "weights": [50, 20] } }
                ]}
                """;
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> store(dir).parse(json));
        assertEquals(ReasonCode.GATE_UNSUPPORTED_OPTION, ex.reasonCode());
    }

    @Test
    void validCanaryAccepted(@TempDir Path dir) {
        String json = """
                { "name": "app", "stages": [
                    { "id": "s1", "env": "dev", "desiredVersion": "v1",
                      "canary": { "weights": [20, 60, 100], "healthThreshold": 0.9 } }
                ]}
                """;
        DeliveryManifest m = store(dir).parse(json);
        assertNotNull(m.stages().get(0).canary());
        assertEquals(java.util.List.of(20, 60, 100), m.stages().get(0).canary().weights());
    }
}
