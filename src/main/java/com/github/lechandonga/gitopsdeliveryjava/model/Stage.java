package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 单个目标在一次收敛流程中按顺序执行的阶段。
 */
public enum Stage {
    BUILD,
    DEPLOY_CANARY,
    PROMOTE_STABLE
}
