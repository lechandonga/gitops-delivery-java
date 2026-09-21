package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.List;

/** 清单中的单个阶段（环境内的一个部署目标）。 */
public record ManifestStage(
        String id,
        String env,
        String desiredVersion,
        List<String> dependsOn,
        CanarySpec canary,
        long executionTimeoutMillis,
        int maxAttempts) {
}
