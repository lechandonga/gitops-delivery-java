package com.github.lechandonga.gitopsdeliveryjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地交付编排的可配置策略（额度、租约、数据目录等）。
 */
@Component
@ConfigurationProperties(prefix = "delivery")
public class DeliveryProperties {

    private String dataDir = "target/delivery-data";
    private int maxConcurrency = 2;
    private int maxQueue = 10;
    private long leaseTtlMillis = 10_000L;
    private boolean schedulerEnabled = false;
    private long reconcileIntervalMs = 5_000L;
    private String executorId = "executor-local";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxQueue() { return maxQueue; }
    public void setMaxQueue(int maxQueue) { this.maxQueue = maxQueue; }
    public long getLeaseTtlMillis() { return leaseTtlMillis; }
    public void setLeaseTtlMillis(long leaseTtlMillis) { this.leaseTtlMillis = leaseTtlMillis; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
    public long getReconcileIntervalMs() { return reconcileIntervalMs; }
    public void setReconcileIntervalMs(long reconcileIntervalMs) { this.reconcileIntervalMs = reconcileIntervalMs; }
    public String getExecutorId() { return executorId; }
    public void setExecutorId(String executorId) { this.executorId = executorId; }
}
