package com.github.lechandonga.gitopsdeliveryjava.event;

/** 状态迁移事件类型（事件溯源用，重启恢复时重放）。 */
public enum EventType {
    MANIFEST_APPLIED,
    STAGE_STARTED,
    STAGE_CANARY_STEP,
    STAGE_SUCCEEDED,
    STAGE_FAILED,
    STAGE_TIMED_OUT,
    STAGE_CANCELLED,
    ROLLBACK_STARTED,
    ROLLBACK_SUCCEEDED,
    LEASE_TAKEN,
    LEASE_TAKEOVER,
    GATE_REJECTED,
    APPROVAL_GRANTED,
    QUEUE_REJECTED,
    CONVERGE_SKIPPED_IDEMPOTENT,
    RECOVERY
}
