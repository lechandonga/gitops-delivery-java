package com.github.lechandonga.gitopsdeliveryjava.infra;

/** 本地模拟健康探测结果（0.0~1.0 成功率）。 */
public record HealthStatus(boolean healthy, double successRate, String version) {
}
