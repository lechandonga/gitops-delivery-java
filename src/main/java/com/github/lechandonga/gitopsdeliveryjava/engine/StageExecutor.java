package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.event.EventLog;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.infra.DeployResult;
import com.github.lechandonga.gitopsdeliveryjava.infra.HealthStatus;
import com.github.lechandonga.gitopsdeliveryjava.infra.LocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.CanarySpec;
import com.github.lechandonga.gitopsdeliveryjava.model.ManifestStage;
import com.github.lechandonga.gitopsdeliveryjava.model.StageState;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;
import com.github.lechandonga.gitopsdeliveryjava.store.Lease;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import com.github.lechandonga.gitopsdeliveryjava.store.StateRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单阶段执行器。
 * 保证：
 * - 幂等：SUCCEEDED/ROLLED_BACK 直接跳过，不重复执行；
 * - 互斥：按目标租约串行，过期后可被安全接管，fence token 防双写；
 * - 灰度：按权重逐步放量并做健康判定，不达标回滚到上一稳定版本，无半发布残留；
 * - 失败/超时/取消原因可区分；重试有界(maxAttempts)且每次尝试都有事件；
 * - 任何出口释放租约（token 匹配才释放），不残留占用。
 */
public class StageExecutor {

    private final LocalPlatform platform;
    private final LeaseStore leases;
    private final StateRepository states;
    private final EventLog events;
    private final long leaseTtlMillis;
    private final long renewIntervalMillis;

    public StageExecutor(LocalPlatform platform, LeaseStore leases, StateRepository states,
                         EventLog events, long leaseTtlMillis, long renewIntervalMillis) {
        this.platform = platform;
        this.leases = leases;
        this.states = states;
        this.events = events;
        this.leaseTtlMillis = leaseTtlMillis;
        this.renewIntervalMillis = renewIntervalMillis;
    }

    /** 幂等入口：已完成阶段不再执行。ownerId 区分收敛循环实例。 */
    public StageExecutionResult execute(ManifestStage stage, String trigger, String ownerId) {
        StageState existing = states.stage(stage.id());
        // 仅“已成功且为当前目标版本”幂等跳过；ROLLED_BACK 相对期望仍是漂移，允许重试
        if (existing != null && existing.status == StageStatus.SUCCEEDED
                && stage.desiredVersion().equals(existing.appliedVersion)) {
            return new StageExecutionResult(existing.status,
                    ReasonCode.IDEMPOTENT_ALREADY_DONE,
                    "stage already succeeded at desired version",
                    existing.appliedVersion);
        }

        Lease lease = leases.acquire(stage.id(), ownerId);
        if (lease == null) {
            // 可能是过期残留：尝试接管一次
            lease = leases.takeover(stage.id(), ownerId);
            if (lease == null) {
                return new StageExecutionResult(
                        existing == null ? StageStatus.PENDING : existing.status,
                        ReasonCode.LEASE_CONTENDED,
                        "target lease held by another convergence loop", null);
            }
            record(EventType.LEASE_TAKEOVER, stage.id(), null, trigger,
                    ReasonCode.LEASE_TAKEOVER, Map.of("owner", ownerId,
                            "expiresAt", String.valueOf(lease.expiresAt)));
        } else {
            record(EventType.LEASE_TAKEN, stage.id(), stage.desiredVersion(), trigger, null,
                    Map.of("owner", ownerId, "expiresAt", String.valueOf(lease.expiresAt)));
        }

        try {
            return runWithAttempts(stage, trigger, ownerId, lease);
        } finally {
            leases.release(stage.id(), ownerId, lease.fenceToken);
        }
    }

    private StageExecutionResult runWithAttempts(ManifestStage stage, String trigger,
                                                 String ownerId, Lease lease) {
        StageState pre = states.stage(stage.id());
        String stableBefore = pre == null ? null : pre.lastStableVersion;
        Exception lastError = null;

        for (int attempt = 1; attempt <= stage.maxAttempts(); attempt++) {
            record(EventType.STAGE_STARTED, stage.id(), stage.desiredVersion(), trigger, null,
                    Map.of("attempt", String.valueOf(attempt),
                            "maxAttempts", String.valueOf(stage.maxAttempts()),
                            "fenceToken", String.valueOf(lease.fenceToken)));

            StageExecutionResult result = runOnce(stage, trigger, ownerId, lease,
                    attempt, stableBefore);
            switch (result.status()) {
                case SUCCEEDED -> {
                    record(EventType.STAGE_SUCCEEDED, stage.id(), result.finalVersion(),
                            trigger, null, Map.of("attempt", String.valueOf(attempt)));
                    return result;
                }
                case ROLLED_BACK -> {
                    // 回滚成功即终态：版本明确回到稳定版，半发布状态已清除
                    record(EventType.ROLLBACK_SUCCEEDED, stage.id(), result.finalVersion(),
                            trigger, result.reasonCode(),
                            Map.of("rolledBackFrom", stage.desiredVersion(),
                                    "attempt", String.valueOf(attempt)));
                    return result;
                }
                case CANCELLED -> {
                    record(EventType.STAGE_CANCELLED, stage.id(), stage.desiredVersion(),
                            trigger, ReasonCode.EXEC_CANCELLED, result.message() == null
                                    ? Map.of() : Map.of("message", result.message()));
                    return result;
                }
                case TIMED_OUT -> {
                    record(EventType.STAGE_TIMED_OUT, stage.id(), stage.desiredVersion(),
                            trigger, ReasonCode.EXEC_TIMEOUT,
                            Map.of("attempt", String.valueOf(attempt),
                                    "message", String.valueOf(result.message())));
                    lastError = new TimeoutException(result.message());
                    // 超时不重试（外层整体时间预算已耗尽），直接进入失败终态
                    return failTerminal(stage, trigger, ReasonCode.EXEC_TIMEOUT,
                            result.message(), stableBefore, lease, ownerId, attempt);
                }
                case FAILED -> {
                    String failReason = result.reasonCode() == null
                            ? ReasonCode.EXEC_FAILED : result.reasonCode();
                    record(EventType.STAGE_FAILED, stage.id(), stage.desiredVersion(),
                            trigger, failReason,
                            Map.of("attempt", String.valueOf(attempt),
                                    "message", String.valueOf(result.message())));
                    lastError = new RuntimeException(result.message());
                    // 落入下一次尝试
                }
                default -> { }
            }
        }
        return failTerminal(stage, trigger, ReasonCode.RETRIES_EXHAUSTED,
                "bounded retries exhausted (" + stage.maxAttempts() + "), last error: "
                        + (lastError == null ? "unknown" : lastError.getMessage()),
                stableBefore, lease, ownerId, stage.maxAttempts());
    }

    /**
     * 失败终态处理：重试耗尽后若曾经存在稳定版本，为避免半发布残留执行回滚；
     * 无稳定版本则保持 FAILED（目标从未成功发布）。
     */
    private StageExecutionResult failTerminal(ManifestStage stage, String trigger,
                                              String reasonCode, String message,
                                              String stableBefore, Lease lease,
                                              String ownerId, int attempt) {
        if (stableBefore != null) {
            record(EventType.ROLLBACK_STARTED, stage.id(), stableBefore, trigger, reasonCode,
                    Map.of("rolledBackFrom", String.valueOf(stage.desiredVersion()),
                            "cause", reasonCode, "attempt", String.valueOf(attempt)));
            StageExecutionResult rb = rollback(stage, trigger, ownerId, lease, stableBefore,
                    reasonCode);
            if (rb.status() == StageStatus.ROLLED_BACK) {
                record(EventType.ROLLBACK_SUCCEEDED, stage.id(), stableBefore, trigger,
                        reasonCode, Map.of("cause", reasonCode,
                                "attempt", String.valueOf(attempt)));
                return new StageExecutionResult(StageStatus.ROLLED_BACK, reasonCode,
                        "failed then rolled back to " + stableBefore + ": " + message,
                        stableBefore);
            }
            return new StageExecutionResult(StageStatus.FAILED, ReasonCode.EXEC_FAILED,
                    "failure rollback did not complete: " + rb.message(), null);
        }
        return new StageExecutionResult(StageStatus.FAILED, reasonCode, message, null);
    }

    private StageExecutionResult runOnce(ManifestStage stage, String trigger, String ownerId,
                                         Lease lease, int attempt, String stableBefore) {
        CanarySpec canary = stage.canary();
        List<Integer> weights = canary == null
                ? List.of(100) : canary.weights();
        double threshold = canary == null ? 1.0 : canary.healthThreshold();
        long stepWait = canary == null ? 0L : canary.stepWaitMillis();

        for (int i = 0; i < weights.size(); i++) {
            int weight = weights.get(i);

            if (!leaseValid(stage, ownerId, lease)) {
                return new StageExecutionResult(StageStatus.FAILED, ReasonCode.LEASE_STALE,
                        "lease lost/taken over at weight " + weight, null);
            }
            if (platform.isCancelled(stage.id())) {
                return new StageExecutionResult(StageStatus.CANCELLED,
                        ReasonCode.EXEC_CANCELLED, "cancelled at weight " + weight, null);
            }

            DeployResult deploy = deployWithTimeout(stage, weight, lease, trigger);
            if (deploy == null) {
                return new StageExecutionResult(StageStatus.TIMED_OUT, ReasonCode.EXEC_TIMEOUT,
                        "deploy timed out at weight " + weight, null);
            }
            if (!deploy.success()) {
                if (platform.isCancelled(stage.id())) {
                    return new StageExecutionResult(StageStatus.CANCELLED,
                            ReasonCode.EXEC_CANCELLED, "cancelled during deploy", null);
                }
                return new StageExecutionResult(StageStatus.FAILED, ReasonCode.EXEC_FAILED,
                        deploy.message(), null);
            }
            if (canary != null) {
                record(EventType.STAGE_CANARY_STEP, stage.id(), stage.desiredVersion(),
                        trigger, null, Map.of("weight", String.valueOf(weight),
                                "attempt", String.valueOf(attempt)));
            }

            if (weight < 100) {
                if (!sleepInterruptible(stage, lease, ownerId, stepWait)) {
                    return new StageExecutionResult(StageStatus.CANCELLED,
                            ReasonCode.EXEC_CANCELLED,
                            "interrupted while waiting canary health window", null);
                }
            }
            HealthStatus health = platform.health(stage.id());
            if (!health.healthy() || health.successRate() < threshold) {
                // 灰度健康不达标：回滚到上一个稳定版本
                return rollbackOrFail(stage, trigger, ownerId, lease, stableBefore);
            }
        }
        return new StageExecutionResult(StageStatus.SUCCEEDED, null,
                "version " + stage.desiredVersion() + " fully promoted",
                stage.desiredVersion());
    }

    /** 健康不达标路径：有稳定版则回滚，无稳定版明确拒绝回滚并失败。 */
    private StageExecutionResult rollbackOrFail(ManifestStage stage, String trigger,
                                                String ownerId, Lease lease,
                                                String stableBefore) {
        if (stableBefore == null) {
            // 无稳定版可回：由调用方在 FAILED 出口统一记录（原因码可区分）
            return new StageExecutionResult(StageStatus.FAILED, ReasonCode.CANARY_UNHEALTHY,
                    "canary health below threshold; "
                            + ReasonCode.ROLLBACK_NO_STABLE, null);
        }
        record(EventType.ROLLBACK_STARTED, stage.id(), stableBefore, trigger,
                ReasonCode.CANARY_UNHEALTHY,
                Map.of("rolledBackFrom", String.valueOf(stage.desiredVersion()),
                        "cause", ReasonCode.CANARY_UNHEALTHY));
        return rollback(stage, trigger, ownerId, lease, stableBefore,
                ReasonCode.CANARY_UNHEALTHY);
    }

    /** 执行回滚放量到稳定版本（100%）。纯执行，事件由调用方记录，避免重复。 */
    private StageExecutionResult rollback(ManifestStage stage, String trigger, String ownerId,
                                          Lease lease, String stableVersion, String cause) {
        if (!leaseValid(stage, ownerId, lease)) {
            return new StageExecutionResult(StageStatus.FAILED, ReasonCode.LEASE_STALE,
                    "lease lost before rollback", null);
        }
        DeployResult deploy = platform.deploy(stage.id(), stableVersion, 100,
                lease.fenceToken);
        if (!deploy.success()) {
            return new StageExecutionResult(StageStatus.FAILED, ReasonCode.EXEC_FAILED,
                    "rollback deploy failed: " + deploy.message(), null);
        }
        HealthStatus health = platform.health(stage.id());
        // 回滚目标是上一个已知稳定版本：以“承载版本已切回且健康”为成功判据，
        // 不沿用灰度阶段注入的故障曲线
        if (!health.healthy() || !stableVersion.equals(health.version())) {
            return new StageExecutionResult(StageStatus.FAILED, ReasonCode.EXEC_FAILED,
                    "rollback target not confirmed healthy on " + stableVersion, null);
        }
        return new StageExecutionResult(StageStatus.ROLLED_BACK, cause,
                "rolled back to stable " + stableVersion, stableVersion);
    }

    private DeployResult deployWithTimeout(ManifestStage stage, int weight, Lease lease,
                                           String trigger) {
        // 每次部署使用独立线程，超时可脱离主流程判定，不把中断误判为成功
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "deploy-" + stage.id() + "-" + weight);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<DeployResult> future = pool.submit(() -> platform.deploy(
                    stage.id(), stage.desiredVersion(), weight, lease.fenceToken));
            return future.get(stage.executionTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            return new DeployResult(false, null, "deploy error: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean leaseValid(ManifestStage stage, String ownerId, Lease lease) {
        return leases.isValid(stage.id(), ownerId, lease.fenceToken);
    }

    /** 可中断等待：被取消/中断返回 false；周期性续租，续租失败由 leaseValid 拦截。 */
    private boolean sleepInterruptible(ManifestStage stage, Lease lease, String ownerId,
                                       long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        long renewAt = System.currentTimeMillis() + renewIntervalMillis;
        while (System.currentTimeMillis() < deadline) {
            if (platform.isCancelled(stage.id())) {
                return false;
            }
            long step = Math.min(200L, deadline - System.currentTimeMillis());
            if (step <= 0) {
                break;
            }
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (System.currentTimeMillis() >= renewAt) {
                leases.renew(stage.id(), ownerId, lease.fenceToken);
                renewAt = System.currentTimeMillis() + renewIntervalMillis;
            }
        }
        return true;
    }

    private void record(EventType type, String target, String version, String trigger,
                        String reasonCode, Map<String, String> detail) {
        // 事件先落盘，再同步投影；重启以事件重放为唯一真相
        com.github.lechandonga.gitopsdeliveryjava.event.DeliveryEvent e =
                events.append(type, target, version, trigger, reasonCode,
                        detail == null ? Map.of() : new LinkedHashMap<>(detail));
        states.apply(e);
    }
}
