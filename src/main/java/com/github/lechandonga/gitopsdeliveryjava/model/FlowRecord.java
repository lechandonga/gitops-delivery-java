package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次收敛流程的记录（重启后据此恢复）。
 */
public class FlowRecord {

    private String flowId;
    private String target;
    private String desiredVersion;
    private String actor;
    private FlowStatus status = FlowStatus.PENDING;
    private Stage currentStage;
    private List<Stage> completedStages = new ArrayList<>();
    /** DEPLOY_CANARY 已成功放量到的步骤下标（-1 表示一步都还没完成），恢复/续跑据此跳过，绝不重放。 */
    private int canaryStepIndex = -1;
    /** 流程类型：普通发布 PUBLISH（旧快照缺省）/ 指定版本回退 ROLLBACK。 */
    private String kind = "PUBLISH";
    /** ROLLBACK 流程的目标版本；审计“从哪个版本退到哪个版本”。 */
    private String rollbackToVersion;
    /**
     * PAUSED 流程是否已收到显式恢复请求（进入额度排队）。
     * false=刚暂停，即使槽位空着也不得被自动提升续跑；必须等待显式 resume。
     */
    private boolean resumeRequested = false;
    private ReasonCode terminalReason = ReasonCode.NONE;
    private String stableVersionBefore;
    private long createdAt;
    private long updatedAt;
    private long deadlineAt;
    private int attempts;
    private long fencingToken;
    private String leaseHolder;

    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getDesiredVersion() { return desiredVersion; }
    public void setDesiredVersion(String desiredVersion) { this.desiredVersion = desiredVersion; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public FlowStatus getStatus() { return status; }
    public void setStatus(FlowStatus status) { this.status = status; }
    public Stage getCurrentStage() { return currentStage; }
    public void setCurrentStage(Stage currentStage) { this.currentStage = currentStage; }
    public List<Stage> getCompletedStages() { return completedStages; }
    public void setCompletedStages(List<Stage> completedStages) { this.completedStages = completedStages; }
    public int getCanaryStepIndex() { return canaryStepIndex; }
    public void setCanaryStepIndex(int canaryStepIndex) { this.canaryStepIndex = canaryStepIndex; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRollbackToVersion() { return rollbackToVersion; }
    public void setRollbackToVersion(String rollbackToVersion) { this.rollbackToVersion = rollbackToVersion; }
    public boolean isResumeRequested() { return resumeRequested; }
    public void setResumeRequested(boolean resumeRequested) { this.resumeRequested = resumeRequested; }
    public ReasonCode getTerminalReason() { return terminalReason; }
    public void setTerminalReason(ReasonCode terminalReason) { this.terminalReason = terminalReason; }
    public String getStableVersionBefore() { return stableVersionBefore; }
    public void setStableVersionBefore(String stableVersionBefore) { this.stableVersionBefore = stableVersionBefore; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public long getDeadlineAt() { return deadlineAt; }
    public void setDeadlineAt(long deadlineAt) { this.deadlineAt = deadlineAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long fencingToken) { this.fencingToken = fencingToken; }
    public String getLeaseHolder() { return leaseHolder; }
    public void setLeaseHolder(String leaseHolder) { this.leaseHolder = leaseHolder; }
}
