package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.engine.DependencyResolver;
import com.github.lechandonga.gitopsdeliveryjava.engine.DriftDetector;
import com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager;
import com.github.lechandonga.gitopsdeliveryjava.engine.StageExecutor;
import com.github.lechandonga.gitopsdeliveryjava.engine.TimeSource;
import com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory;
import com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager;
import com.github.lechandonga.gitopsdeliveryjava.service.DeliveryService;
import com.github.lechandonga.gitopsdeliveryjava.service.FreezeGate;
import com.github.lechandonga.gitopsdeliveryjava.service.PolicyGate;
import com.github.lechandonga.gitopsdeliveryjava.service.RecoveryService;
import com.github.lechandonga.gitopsdeliveryjava.service.SimulatedInfrastructure;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;

import java.nio.file.Path;

/**
 * 手工装配一整套本地交付系统（不启动 Spring），便于精确控制与断言。
 */
class DeliverySystemHarness {

    final DeliveryProperties props;
    final StateStore store;
    final LeaseManager leases;
    final QuotaManager quota;
    final SimulatedInfrastructure infra;
    final DeliveryService service;
    final RecoveryService recovery;
    final TimeSource clock;
    final FreezeGate freezeGate;
    final VersionHistory versionHistory;

    DeliverySystemHarness(Path dir) {
        this(dir, 2, 10);
    }

    DeliverySystemHarness(Path dir, int maxConcurrency, int maxQueue) {
        this(dir, maxConcurrency, maxQueue, new TimeSource());
    }

    DeliverySystemHarness(Path dir, int maxConcurrency, int maxQueue, TimeSource timeSource) {
        props = new DeliveryProperties();
        props.setDataDir(dir.toString());
        props.setMaxConcurrency(maxConcurrency);
        props.setMaxQueue(maxQueue);
        props.setLeaseTtlMillis(60_000L);
        props.setExecutorId("exec-test");
        leases = new LeaseManager();
        store = new StateStore(props, leases);
        store.init();
        quota = new QuotaManager(props);
        infra = new SimulatedInfrastructure();
        clock = timeSource;
        freezeGate = new FreezeGate(clock);
        versionHistory = new VersionHistory(props, store);
        versionHistory.init();
        DependencyResolver resolver = new DependencyResolver();
        DriftDetector drift = new DriftDetector();
        PolicyGate gate = new PolicyGate();
        StageExecutor stageExecutor = new StageExecutor(infra, store, leases, props);
        service = new DeliveryService(store, leases, quota, resolver, drift,
                stageExecutor, gate, infra, props, freezeGate, versionHistory);
        recovery = new RecoveryService(store, leases, props, drift, service);
    }
}
