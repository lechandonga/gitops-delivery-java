package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.List;

/** 声明式期望状态清单（本地 GitOps 期望态）。 */
public record DeliveryManifest(
        String schemaVersion,
        String name,
        boolean protectedEnv,
        boolean requireApproval,
        List<ManifestStage> stages) {
}
