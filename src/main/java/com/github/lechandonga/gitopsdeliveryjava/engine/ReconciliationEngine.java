package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.event.EventLog;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.infra.LocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ManifestStage;
import com.github.lechandonga.gitopsdeliveryjava.model.StageState;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import com.github.lechandonga.gitopsdeliveryjava.store.ManifestStore;
import com.github.lechandonga.gitopsdeliveryjava.store.StateRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 收敛主循环（唯一编排路径：API/定时/事件/恢复入口都调用 converge）。
 * 对期望清单按 DAG 顺序逐阶段判定：门禁 -> 前置 -> 漂移 -> 额度 -> 执行，
 * 每一种拒绝/跳过都有独立原因码与事件，结论一致且可解释。
 */
public class ReconciliationEngine {

    private final ManifestStore manifestStore;
    private final StateRepository states;
    private final LeaseStore leases;
    private final EventLog events;
    private final LocalPlatform platform;
    private final GateKeeper gate;
    private final CapacityController capacity;
    private final StageExecutor executor;
    private final long capacityWaitMillis;
    private final AtomicLong runCounter = new AtomicLong();

    public ReconciliationEngine(ManifestStore manifestStore, StateRepository states,
                                LeaseStore leases, EventLog events, LocalPlatform platform,
                                GateKeeper gate, CapacityController capacity,
                                StageExecutor executor, long capacityWaitMillis) {
        this.manifestStore = manifestStore;
        this.states = states;
        this.leases = leases;
        this.events = events;
        this.platform = platform;
        this.gate = gate;
        this.capacity = capacity;
        this.executor = executor;
        this.capacityWaitMillis = capacityWaitMillis;
    }

    /** 应用新期望清单：严格校验 + 环检测，通过才落盘；拒绝不改变任何已发布版本。 */
    public synchronized void applyManifest(DeliveryManifest manifest, String trigger,
                                           boolean approved) {
        // 先做结构校验（ManifestStore 已解析默认值），再做 DAG 环检测
        DependencyGraph graph = new DependencyGraph(manifest.stages());
        graph.topologicalOrder(); // 有环抛 CYCLIC_DEPENDENCY
        states.bindManifest(manifest);
        manifestStore.save(manifest);
        recordManifestApplied(manifest, trigger, approved);
    }

    private void recordManifestApplied(DeliveryManifest manifest, String trigger,
                                       boolean approved) {
        var event = events.append(EventType.MANIFEST_APPLIED, manifest.name(), null, trigger,
                null, Map.of("protectedEnv", String.valueOf(manifest.protectedEnv()),
                        "requireApproval", String.valueOf(manifest.requireApproval()),
                        "approved", String.valueOf(approved),
                        "stageCount", String.valueOf(manifest.stages().size())));
        states.apply(event);
    }

    /** 授予受保护环境推进审批（显式动作，持久化为事件）。 */
    public void grantApproval(String env, String trigger) {
        if (trigger == null || !GateKeeper.KNOWN_TRIGGERS.contains(trigger)) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNAUTHORIZED_TRIGGER,
                    "approval trigger not authorized: " + trigger);
        }
        var event = events.append(EventType.APPROVAL_GRANTED, env, null, trigger, null,
                Map.of("message", "promotion approval granted"));
        states.apply(event);
    }

    /** 取消某阶段：置取消标记并记录事件；正在执行的阶段在检查点退出，不判成功。 */
    public void cancel(String stageId, String trigger) {
        platform.cancel(stageId);
        events.append(EventType.STAGE_CANCELLED, stageId, null, trigger,
                ReasonCode.EXEC_CANCELLED,
                Map.of("message", "cancel requested"));
    }

    /** 触发一次收敛。所有入口共用，保证结论一致。 */
    public ConvergenceReport converge(String trigger) {
        // 触发入口合法性是请求级前置条件：未知入口立即拒绝（可区分），不进入逐阶段处理
        if (trigger == null || !GateKeeper.KNOWN_TRIGGERS.contains(trigger)) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNAUTHORIZED_TRIGGER,
                    "trigger not authorized: " + trigger);
        }
        DeliveryManifest manifest = manifestStore.load();
        if (manifest == null) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "no manifest applied yet");
        }
        states.bindManifest(manifest);
        DependencyGraph graph = new DependencyGraph(manifest.stages());
        List<String> order = graph.topologicalOrder();
        Map<String, ManifestStage> byId = new LinkedHashMap<>();
        manifest.stages().forEach(s -> byId.put(s.id(), s));

        String ownerId = "converge-" + runCounter.incrementAndGet() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        List<ConvergenceReport.StageOutcome> outcomes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        boolean anyDrift = false;
        boolean allConverged = true;

        for (String stageId : order) {
            ManifestStage stage = byId.get(stageId);
            StageState before = states.stage(stageId);
            String fromStatus = before == null ? "NONE" : before.status.name();

            // 1) 终态幂等：仅 SUCCEEDED 且版本匹配才算收敛。
            //    ROLLED_BACK 表示回到稳定旧版，相对期望仍是漂移，必须允许重新推进。
            if (before != null && before.status == StageStatus.SUCCEEDED
                    && stage.desiredVersion().equals(before.appliedVersion)) {
                events.append(EventType.CONVERGE_SKIPPED_IDEMPOTENT, stageId,
                        before.appliedVersion, trigger,
                        ReasonCode.IDEMPOTENT_ALREADY_DONE,
                        Map.of("status", before.status.name()));
                outcomes.add(new ConvergenceReport.StageOutcome(stageId, fromStatus,
                        before.status.name(), before.appliedVersion,
                        ReasonCode.IDEMPOTENT_ALREADY_DONE, "already converged"));
                continue;
            }
            if (before != null && (before.status == StageStatus.SUCCEEDED
                    || before.status == StageStatus.ROLLED_BACK)) {
                // 成功但版本不匹配，或已回滚：仍有漂移，继续往下走门禁/前置/执行
                anyDrift = true;
            }

            // 2) 漂移检测：物理实际版本 vs 期望版本
            String physical = platform.currentVersion(stageId);
            boolean drift = physical == null
                    || !physical.equals(stage.desiredVersion())
                    || (before != null && before.status != StageStatus.SUCCEEDED);
            if (drift) {
                anyDrift = true;
            }

            // 3) 门禁：未授权入口 / 受保护环境未审批 -> 拒绝且不改版本
            boolean approved = states.envApproved(stage.env());
            try {
                gate.check(manifest, stage.env(), trigger, approved);
            } catch (DeliveryRejectedException rej) {
                events.append(EventType.GATE_REJECTED, stageId, stage.desiredVersion(),
                        trigger, rej.reasonCode(),
                        Map.of("message", String.valueOf(rej.getMessage()),
                                "env", stage.env()));
                reasons.add(stageId + ":" + rej.reasonCode());
                outcomes.add(new ConvergenceReport.StageOutcome(stageId, fromStatus,
                        fromStatus, physical, rej.reasonCode(), rej.getMessage()));
                allConverged = false;
                continue;
            }

            // 4) 前置依赖：前置必须 SUCCEEDED 且版本匹配期望，否则可区分拒绝（不静默跳过）
            List<String> missing = graph.missingPrerequisites(stageId, dep -> {
                StageState d = states.stage(dep);
                ManifestStage dm = byId.get(dep);
                return d != null && d.status == StageStatus.SUCCEEDED
                        && dm != null && dm.desiredVersion().equals(d.appliedVersion);
            });
            if (!missing.isEmpty()) {
                events.append(EventType.GATE_REJECTED, stageId, stage.desiredVersion(),
                        trigger, ReasonCode.PREREQUISITE_NOT_DONE,
                        Map.of("missing", String.join(",", missing)));
                reasons.add(stageId + ":" + ReasonCode.PREREQUISITE_NOT_DONE);
                outcomes.add(new ConvergenceReport.StageOutcome(stageId, fromStatus,
                        fromStatus, physical, ReasonCode.PREREQUISITE_NOT_DONE,
                        "prerequisites not done: " + missing));
                allConverged = false;
                continue;
            }

            // 5) 额度：有界排队，超时/队列满 -> QUEUE_FULL，不残留占用
            boolean acquired;
            try {
                acquired = capacity.tryAcquire(capacityWaitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                events.append(EventType.QUEUE_REJECTED, stageId, stage.desiredVersion(),
                        trigger, ReasonCode.EXEC_CANCELLED,
                        Map.of("message", "interrupted while waiting capacity"));
                outcomes.add(new ConvergenceReport.StageOutcome(stageId, fromStatus,
                        fromStatus, physical, ReasonCode.EXEC_CANCELLED,
                        "interrupted waiting capacity"));
                allConverged = false;
                continue;
            }
            if (!acquired) {
                events.append(EventType.QUEUE_REJECTED, stageId, stage.desiredVersion(),
                        trigger, ReasonCode.QUEUE_FULL,
                        Map.of("queueLength", String.valueOf(capacity.queueLength())));
                reasons.add(stageId + ":" + ReasonCode.QUEUE_FULL);
                outcomes.add(new ConvergenceReport.StageOutcome(stageId, fromStatus,
                        fromStatus, physical, ReasonCode.QUEUE_FULL,
                        "concurrency/queue capacity exhausted"));
                allConverged = false;
                continue;
            }

            // 6) 执行（内部再做租约互斥 + 幂等 + 灰度/回滚 + 有界重试）
            StageExecutionResult result;
            try {
                result = executor.execute(stage, trigger, ownerId);
            } finally {
                capacity.release();
            }
            StageState after = states.stage(stageId);
            String toStatus = after == null ? result.status().name() : after.status.name();
            outcomes.add(new ConvergenceReport.StageOutcome(stageId, fromStatus, toStatus,
                    result.finalVersion(), result.reasonCode(), result.message()));
            if (result.reasonCode() != null) {
                reasons.add(stageId + ":" + result.reasonCode());
            }
            if (result.status() != StageStatus.SUCCEEDED) {
                allConverged = false;
            }
        }

        return new ConvergenceReport(manifest.name(), allConverged, anyDrift,
                List.copyOf(outcomes), List.copyOf(reasons));
    }
}
