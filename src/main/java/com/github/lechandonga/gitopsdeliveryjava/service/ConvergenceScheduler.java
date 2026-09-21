package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 周期性声明式收敛循环（触发入口之一，与手动/API 共用同一编排核心）。
 * 默认关闭，通过 delivery.scheduler-enabled=true 打开。
 * 循环本身幂等：已收敛目标得到 ALREADY_CONVERGED，不产生重复动作。
 */
@Component
@ConditionalOnProperty(prefix = "delivery", name = "scheduler-enabled", havingValue = "true")
public class ConvergenceScheduler {

    private final DeliveryService deliveryService;
    private final StateStore store;

    public ConvergenceScheduler(DeliveryService deliveryService, StateStore store,
                                DeliveryProperties props) {
        this.deliveryService = deliveryService;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${delivery.reconcile.interval-ms:5000}")
    public void tick() {
        if (store.loadManifest().isEmpty()) {
            return;
        }
        // 周期收敛以系统身份执行，仅推进非受保护目标；受保护目标必须走带授权的显式入口
        deliveryService.reconcileAll("scheduler", Set.of("system"), false);
    }
}
