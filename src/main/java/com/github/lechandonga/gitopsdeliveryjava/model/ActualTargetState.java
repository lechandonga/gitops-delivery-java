package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 本地模拟的执行/部署实际状态。每个目标一条，持久化到本地状态库。
 * 回滚成功后不得残留 CANARY/半发布相位，canaryVersion 必须清空。
 */
public class ActualTargetState {

    private String name;
    private TargetPhase phase = TargetPhase.ABSENT;
    private String stableVersion;
    private String previousStableVersion;
    private String canaryVersion;
    private int canaryPercent;
    private String flowId;
    private long updatedAt;
    private int buildExecutions;
    private int deployExecutions;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public TargetPhase getPhase() { return phase; }
    public void setPhase(TargetPhase phase) { this.phase = phase; }
    public String getStableVersion() { return stableVersion; }
    public void setStableVersion(String stableVersion) { this.stableVersion = stableVersion; }
    public String getPreviousStableVersion() { return previousStableVersion; }
    public void setPreviousStableVersion(String previousStableVersion) { this.previousStableVersion = previousStableVersion; }
    public String getCanaryVersion() { return canaryVersion; }
    public void setCanaryVersion(String canaryVersion) { this.canaryVersion = canaryVersion; }
    public int getCanaryPercent() { return canaryPercent; }
    public void setCanaryPercent(int canaryPercent) { this.canaryPercent = canaryPercent; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public int getBuildExecutions() { return buildExecutions; }
    public void setBuildExecutions(int buildExecutions) { this.buildExecutions = buildExecutions; }
    public int getDeployExecutions() { return deployExecutions; }
    public void setDeployExecutions(int deployExecutions) { this.deployExecutions = deployExecutions; }
}
