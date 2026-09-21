package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;

/** 单阶段执行结论：终态 + 可区分原因码 + 说明 + 最终承载版本。 */
public record StageExecutionResult(StageStatus status, String reasonCode,
                                   String message, String finalVersion) {

    public boolean succeeded() {
        return status == StageStatus.SUCCEEDED;
    }

    public boolean rolledBack() {
        return status == StageStatus.ROLLED_BACK;
    }
}
