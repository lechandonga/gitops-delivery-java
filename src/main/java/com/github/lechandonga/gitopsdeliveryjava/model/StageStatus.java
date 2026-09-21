package com.github.lechandonga.gitopsdeliveryjava.model;

/** 阶段实际执行状态。 */
public enum StageStatus {
    PENDING,
    RUNNING,
    CANARY_RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    ROLLED_BACK
}
