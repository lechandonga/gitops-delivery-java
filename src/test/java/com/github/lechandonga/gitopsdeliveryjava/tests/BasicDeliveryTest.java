package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.engine.DependencyResolver;
import com.github.lechandonga.gitopsdeliveryjava.engine.DriftDetector;
import com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager;
import com.github.lechandonga.gitopsdeliveryjava.engine.StageExecutor;
import com.github.lechandonga.gitopsdeliveryjava.engine.TimeSource;
import com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory;
import com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import com.github.lechandonga.gitopsdeliveryjava.service.DeliveryService;
import com.github.lechandonga.gitopsdeliveryjava.service.FreezeGate;
import com.github.lechandonga.gitopsdeliveryjava.service.PolicyGate;
import com.github.lechandonga.gitopsdeliveryjava.service.RecoveryService;
import com.github.lechandonga.gitopsdeliveryjava.service.SimulatedInfrastructure;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小端到端：首次发布成功；重复收敛无动作、无重复执行。
 */
class BasicDeliveryTest {

    private DeliveryService newSystem(Path dir) {
        DeliveryProperties props = new DeliveryProperties();
        props.setDataDir(dir.toString());
        LeaseManager leases = new LeaseManager();
        StateStore store = new StateStore(props, leases);
        store.init();
        QuotaManager quota = new QuotaManager(props);
        SimulatedInfrastructure infra = new SimulatedInfrastructure();
        VersionHistory history = new VersionHistory(props, store);
        history.init();
        TimeSource clock = new TimeSource();
        DependencyResolver resolver = new DependencyResolver();
        DriftDetector drift = new DriftDetector();
        PolicyGate gate = new PolicyGate();
        StageExecutor stageExecutor = new StageExecutor(infra, store, leases, props);
        DeliveryService svc = new DeliveryService(store, leases, quota, resolver, drift,
                stageExecutor, gate, infra, props, new FreezeGate(clock), history);
        return svc;
    }

    @Test
    void publishThenRepeatedReconcileIsIdempotent(@TempDir Path dir) {
        DeliveryService svc = newSystem(dir);
        Manifest manifest = TestSupport.single("svc-a", "v1");
        svc.applyManifest(manifest);

        ReconcileOutcome first = svc.reconcileTarget("svc-a", "alice", Set.of("dev"), false, "test");
        assertEquals(FlowStatus.SUCCEEDED, first.getFlowStatus());
        assertTrue(first.isConverged());
        assertEquals("v1", first.getStableVersion());

        ReconcileOutcome second = svc.reconcileTarget("svc-a", "alice", Set.of("dev"), false, "test");
        assertEquals(ReasonCode.ALREADY_CONVERGED, second.getReason());
        assertFalse(second.isActionTaken(), "重复收敛不得产生动作");
    }
}
