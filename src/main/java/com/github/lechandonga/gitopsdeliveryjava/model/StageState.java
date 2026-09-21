package com.github.lechandonga.gitopsdeliveryjava.model;

/** 单个阶段的本地实际运行状态（事件溯源投影，可变）。 */
public class StageState {
    public String stageId;
    public String env;
    public String desiredVersion;
    public StageStatus status = StageStatus.PENDING;
    public String appliedVersion;
    public String lastStableVersion;
    public int attempt;
    public int canaryWeight;
    public String leaseOwner;
    public long leaseExpiresAt;
    public long updatedAt;
    public String reasonCode;
    public String message;

    public StageState() {
    }
}
