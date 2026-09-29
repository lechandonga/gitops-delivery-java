package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 审计事件：每次状态迁移记录输入与判定依据。
 * 拒绝/回滚/接管必须带可区分原因码。
 */
public class EventRecord {

    private long seq;
    private long timestamp;
    private String flowId;
    private String target;
    private String actor;
    private String fromState;
    private String toState;
    private Stage stage;
    private ReasonCode reason;
    private String detail;
    private String inputVersion;
    /** 版本变更类事件的“变更后/回退目标”版本，用于追溯从哪个版本退到哪个版本。 */
    private String targetVersion;
    private long fencingToken;

    public EventRecord() {
    }

    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getFromState() { return fromState; }
    public void setFromState(String fromState) { this.fromState = fromState; }
    public String getToState() { return toState; }
    public void setToState(String toState) { this.toState = toState; }
    public Stage getStage() { return stage; }
    public void setStage(Stage stage) { this.stage = stage; }
    public ReasonCode getReason() { return reason; }
    public void setReason(ReasonCode reason) { this.reason = reason; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getInputVersion() { return inputVersion; }
    public void setInputVersion(String inputVersion) { this.inputVersion = inputVersion; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
    public long getFencingToken() { return fencingToken; }
    public void setFencingToken(long fencingToken) { this.fencingToken = fencingToken; }
}
