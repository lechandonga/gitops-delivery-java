package com.github.lechandonga.gitopsdeliveryjava.engine;

import java.util.List;

/** 一次收敛的可解释结论：各阶段结果 + 漂移 + 事件序号。 */
public record ConvergenceReport(String manifestName, boolean converged, boolean driftDetected,
                                List<StageOutcome> outcomes, List<String> eventReasons) {

    public record StageOutcome(String stageId, String fromStatus, String toStatus,
                               String version, String reasonCode, String message) {
    }
}
