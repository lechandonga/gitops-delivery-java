package com.github.lechandonga.gitopsdeliveryjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地交付编排的可配置参数（数据目录、额度、租约 TTL、自动收敛开关）。 */
@ConfigurationProperties(prefix = "delivery")
public class DeliveryProperties {
    private String dataDir = "./target/delivery-data";
    private int maxConcurrency = 2;
    private int maxQueue = 16;
    private long leaseTtlMillis = 10_000L;
    private long leaseRenewMillis = 3_000L;
    private long capacityWaitMillis = 2_000L;
    private boolean autoReconcileOnStartup = true;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxQueue() { return maxQueue; }
    public void setMaxQueue(int maxQueue) { this.maxQueue = maxQueue; }
    public long getLeaseTtlMillis() { return leaseTtlMillis; }
    public void setLeaseTtlMillis(long leaseTtlMillis) { this.leaseTtlMillis = leaseTtlMillis; }
    public long getLeaseRenewMillis() { return leaseRenewMillis; }
    public void setLeaseRenewMillis(long leaseRenewMillis) { this.leaseRenewMillis = leaseRenewMillis; }
    public long getCapacityWaitMillis() { return capacityWaitMillis; }
    public void setCapacityWaitMillis(long capacityWaitMillis) { this.capacityWaitMillis = capacityWaitMillis; }
    public boolean isAutoReconcileOnStartup() { return autoReconcileOnStartup; }
    public void setAutoReconcileOnStartup(boolean autoReconcileOnStartup) { this.autoReconcileOnStartup = autoReconcileOnStartup; }
}
