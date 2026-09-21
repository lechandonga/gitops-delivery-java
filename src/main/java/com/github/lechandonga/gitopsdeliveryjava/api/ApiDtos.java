package com.github.lechandonga.gitopsdeliveryjava.api;

import com.github.lechandonga.gitopsdeliveryjava.engine.ConvergenceReport;

import java.util.List;

/** HTTP 出参 DTO 集合。 */
public final class ApiDtos {
    private ApiDtos() {
    }

    public record RejectedResponse(String reasonCode, String message) {
    }

    public record ConvergeResponse(boolean converged, boolean driftDetected,
                                   List<ConvergenceReport.StageOutcome> outcomes,
                                   List<String> eventReasons) {
    }

    public record StageView(String stageId, String env, String desiredVersion, String status,
                            String appliedVersion, String lastStableVersion, int attempt,
                            int canaryWeight, String leaseOwner, long leaseExpiresAt,
                            String reasonCode, String message) {
    }

    public record EnvView(String env, boolean protectedEnv, boolean approved,
                          String currentVersion, String lastStableVersion) {
    }

    public record StateView(List<StageView> stages, List<EnvView> envs,
                            int activeLeases, int activeConcurrency, int queueLength) {
    }
}
