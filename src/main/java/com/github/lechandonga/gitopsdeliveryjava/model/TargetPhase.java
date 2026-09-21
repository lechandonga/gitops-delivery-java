package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 目标在本地模拟基础设施上的实际所处相位。
 */
public enum TargetPhase {
    ABSENT,
    BUILT,
    CANARY,
    STABLE,
    FAILED,
    ROLLED_BACK
}
