package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 一次触发/收敛的结论。多个触发入口必须得到一致且可解释的结论。
 */
public class ReconcileOutcome {

    private final String target;
    private final String flowId;
    private final boolean actionTaken;
    private final boolean converged;
    private final FlowStatus flowStatus;
    private final ReasonCode reason;
    private final String message;
    private final String stableVersion;

    public ReconcileOutcome(String target, String flowId, boolean actionTaken, boolean converged,
                            FlowStatus flowStatus, ReasonCode reason, String message, String stableVersion) {
        this.target = target;
        this.flowId = flowId;
        this.actionTaken = actionTaken;
        this.converged = converged;
        this.flowStatus = flowStatus;
        this.reason = reason;
        this.message = message;
        this.stableVersion = stableVersion;
    }

    public String getTarget() { return target; }
    public String getFlowId() { return flowId; }
    public boolean isActionTaken() { return actionTaken; }
    public boolean isConverged() { return converged; }
    public FlowStatus getFlowStatus() { return flowStatus; }
    public ReasonCode getReason() { return reason; }
    public String getMessage() { return message; }
    public String getStableVersion() { return stableVersion; }
}
