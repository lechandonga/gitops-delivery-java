package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 收敛流程（一次发布尝试）的状态。
 */
public enum FlowStatus {
    PENDING,
    RUNNING,
    WAITING_DEPS,
    WAITING_QUOTA,
    PAUSED,
    SUCCEEDED,
    FAILED,
    ROLLED_BACK,
    CANCELLED,
    TIMED_OUT,
    REJECTED
}
