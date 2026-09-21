package com.github.lechandonga.gitopsdeliveryjava.engine;

/** 可区分的原因码集合：拒绝、回滚、接管、失败各占独立命名空间。 */
public final class ReasonCode {
    // 编排类
    public static final String CYCLIC_DEPENDENCY = "DEP_CYCLE";
    public static final String PREREQUISITE_NOT_DONE = "DEP_PREREQUISITE_NOT_DONE";
    // 门禁类
    public static final String GATE_PROTECTED_NO_APPROVAL = "GATE_PROTECTED_NO_APPROVAL";
    public static final String GATE_UNAUTHORIZED_TRIGGER = "GATE_UNAUTHORIZED_TRIGGER";
    public static final String GATE_UNSUPPORTED_OPTION = "GATE_UNSUPPORTED_OPTION";
    public static final String GATE_MANIFEST_INVALID = "GATE_MANIFEST_INVALID";
    // 额度类
    public static final String QUEUE_FULL = "CAP_QUEUE_FULL";
    // 幂等
    public static final String IDEMPOTENT_ALREADY_DONE = "IDEMPOTENT_ALREADY_DONE";
    // 灰度 / 回滚
    public static final String CANARY_UNHEALTHY = "CANARY_UNHEALTHY";
    public static final String ROLLBACK_NO_STABLE = "ROLLBACK_NO_STABLE_VERSION";
    // 执行类
    public static final String EXEC_FAILED = "EXEC_FAILED";
    public static final String EXEC_TIMEOUT = "EXEC_TIMEOUT";
    public static final String EXEC_CANCELLED = "EXEC_CANCELLED";
    public static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";
    // 租约竞争
    public static final String LEASE_CONTENDED = "LEASE_CONTENDED";
    public static final String LEASE_STALE = "LEASE_STALE_TOKEN";
    // 接管
    public static final String LEASE_TAKEOVER = "LEASE_TAKEN_OVER_AFTER_EXPIRY";

    private ReasonCode() {
    }
}
