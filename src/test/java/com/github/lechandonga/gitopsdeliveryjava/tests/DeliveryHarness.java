package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.CapacityController;
import com.github.lechandonga.gitopsdeliveryjava.engine.GateKeeper;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReconciliationEngine;
import com.github.lechandonga.gitopsdeliveryjava.engine.RecoveryService;
import com.github.lechandonga.gitopsdeliveryjava.engine.StageExecutor;
import com.github.lechandonga.gitopsdeliveryjava.infra.InMemoryLocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;
import com.github.lechandonga.gitopsdeliveryjava.store.FileEventLog;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import com.github.lechandonga.gitopsdeliveryjava.store.ManifestStore;
import com.github.lechandonga.gitopsdeliveryjava.store.StateRepository;

import java.nio.file.Path;

/**
 * 集成测试夹具：用真实文件事件日志/租约/清单 + 内存模拟平台组装引擎。
 * “重启”= 重建存储与引擎（复用同一物理平台实例），验证事件重放恢复。
 */
final class DeliveryHarness {

    final Path dir;
    final InMemoryLocalPlatform platform;
    final FileEventLog events;
    final ManifestStore manifestStore;
    final StateRepository states;
    final LeaseStore leases;
    final GateKeeper gate;
    final CapacityController capacity;
    final StageExecutor executor;
    final ReconciliationEngine engine;
    final RecoveryService recovery;

    DeliveryHarness(Path dir, InMemoryLocalPlatform platform, long leaseTtl,
                    int maxConcurrency, int maxQueue, long capacityWait) {
        this.dir = dir;
        this.platform = platform;
        this.events = new FileEventLog(dir);
        this.manifestStore = new ManifestStore(dir);
        this.states = new StateRepository(dir);
        this.leases = new LeaseStore(dir, leaseTtl);
        this.gate = new GateKeeper();
        this.capacity = new CapacityController(maxConcurrency, maxQueue);
        this.executor = new StageExecutor(platform, leases, states, events, leaseTtl,
                Math.max(50, leaseTtl / 3));
        this.engine = new ReconciliationEngine(manifestStore, states, leases, events, platform,
                gate, capacity, executor, capacityWait);
        this.recovery = new RecoveryService(states, leases, events, manifestStore);
    }

    /** 模拟进程重启：存储全部从文件重建，平台复用（物理环境仍在运行）。 */
    DeliveryHarness restart(long leaseTtl, int maxConcurrency, int maxQueue, long capacityWait) {
        return new DeliveryHarness(dir, platform, leaseTtl, maxConcurrency, maxQueue,
                capacityWait);
    }

    DeliveryHarness restart() {
        return restart(10_000, 2, 16, 2_000);
    }

    static DeliveryManifest manifest(String json, ManifestStore store) {
        return store.parse(json);
    }
}
