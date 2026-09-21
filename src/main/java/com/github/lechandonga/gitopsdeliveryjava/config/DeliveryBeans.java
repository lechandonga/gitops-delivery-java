package com.github.lechandonga.gitopsdeliveryjava.config;

import com.github.lechandonga.gitopsdeliveryjava.engine.CapacityController;
import com.github.lechandonga.gitopsdeliveryjava.engine.GateKeeper;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReconciliationEngine;
import com.github.lechandonga.gitopsdeliveryjava.engine.RecoveryService;
import com.github.lechandonga.gitopsdeliveryjava.engine.StageExecutor;
import com.github.lechandonga.gitopsdeliveryjava.infra.InMemoryLocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.infra.LocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.store.FileEventLog;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import com.github.lechandonga.gitopsdeliveryjava.store.ManifestStore;
import com.github.lechandonga.gitopsdeliveryjava.store.StateRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 组装本地持续交付编排组件（全部单例，共享同一事件日志/状态/平台实例）。 */
@Configuration
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryBeans {

    @Bean
    public Path deliveryDataDir(DeliveryProperties props) {
        return Path.of(props.getDataDir());
    }

    @Bean
    public FileEventLog fileEventLog(Path deliveryDataDir) {
        return new FileEventLog(deliveryDataDir);
    }

    @Bean
    public ManifestStore manifestStore(Path deliveryDataDir) {
        return new ManifestStore(deliveryDataDir);
    }

    @Bean
    public StateRepository stateRepository(Path deliveryDataDir) {
        return new StateRepository(deliveryDataDir);
    }

    @Bean
    public LeaseStore leaseStore(Path deliveryDataDir, DeliveryProperties props) {
        return new LeaseStore(deliveryDataDir, props.getLeaseTtlMillis());
    }

    @Bean
    public LocalPlatform localPlatform() {
        return new InMemoryLocalPlatform();
    }

    @Bean
    public GateKeeper gateKeeper() {
        return new GateKeeper();
    }

    @Bean
    public CapacityController capacityController(DeliveryProperties props) {
        return new CapacityController(props.getMaxConcurrency(), props.getMaxQueue());
    }

    @Bean
    public StageExecutor stageExecutor(LocalPlatform platform, LeaseStore leases,
                                       StateRepository states, FileEventLog events,
                                       DeliveryProperties props) {
        return new StageExecutor(platform, leases, states, events,
                props.getLeaseTtlMillis(), props.getLeaseRenewMillis());
    }

    @Bean
    public ReconciliationEngine reconciliationEngine(ManifestStore manifestStore,
                                                     StateRepository states, LeaseStore leases,
                                                     FileEventLog events, LocalPlatform platform,
                                                     GateKeeper gate, CapacityController capacity,
                                                     StageExecutor executor,
                                                     DeliveryProperties props) {
        return new ReconciliationEngine(manifestStore, states, leases, events, platform, gate,
                capacity, executor, props.getCapacityWaitMillis());
    }

    @Bean
    public RecoveryService recoveryService(StateRepository states, LeaseStore leases,
                                           FileEventLog events, ManifestStore manifestStore) {
        return new RecoveryService(states, leases, events, manifestStore);
    }
}
