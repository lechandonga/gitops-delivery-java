package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.engine.DependencyResolver;
import com.github.lechandonga.gitopsdeliveryjava.engine.DriftDetector;
import com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager;
import com.github.lechandonga.gitopsdeliveryjava.engine.StageExecutor;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.model.Stage;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 本地持续交付编排核心。所有触发入口（HTTP API、周期收敛、手动调用、重启恢复）
 * 都汇聚到 {@link #reconcileTarget}，保证结论一致且可解释。
 */
@Service
public class DeliveryService {

    private static final List<Stage> PIPELINE =
            List.of(Stage.BUILD, Stage.DEPLOY_CANARY, Stage.PROMOTE_STABLE);

    private final StateStore store;
    private final LeaseManager leases;
    private final QuotaManager quota;
    private final DependencyResolver resolver;
    private final DriftDetector driftDetector;
    private final StageExecutor stageExecutor;
    private final PolicyGate policyGate;
    private final SimulatedInfrastructure infra;
    private final DeliveryProperties props;

    public DeliveryService(StateStore store, LeaseManager leases, QuotaManager quota,
                           DependencyResolver resolver, DriftDetector driftDetector,
                           StageExecutor stageExecutor, PolicyGate policyGate,
                           SimulatedInfrastructure infra, DeliveryProperties props) {
        this.store = store;
        this.leases = leases;
        this.quota = quota;
        this.resolver = resolver;
        this.driftDetector = driftDetector;
        this.stageExecutor = stageExecutor;
        this.policyGate = policyGate;
        this.infra = infra;
        this.props = props;
    }

    public void applyManifest(Manifest manifest) {
        resolver.topoOrder(manifest); // 循环依赖清单不落库
        store.saveManifest(manifest);
        recordSystem(null, "system", null, "MANIFEST", "MANIFEST", ReasonCode.NONE,
                "清单已应用，目标数=" + manifest.getTargets().size(), 0L);
    }

    public ReconcileOutcome reconcileTarget(String target, String actor, Set<String> roles,
                                            boolean approved, String triggerSource) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        return reconcile(desired, manifest, actor, roles == null ? Set.of() : roles, approved, triggerSource);
    }

    public ReconcileOutcome reconcileAll(String actor, Set<String> roles, boolean approved) {
        Manifest manifest = requireManifest();
        List<String> order = resolver.topoOrder(manifest); // 循环依赖在此抛出可区分拒绝
        ReconcileOutcome last = null;
        for (String name : order) {
            last = reconcile(manifest.getTargets().get(name), manifest,
                    actor, roles == null ? Set.of() : roles, approved, "reconcileAll");
        }
        return last;
    }

    private Manifest requireManifest() {
        return store.loadManifest()
                .orElseThrow(() -> new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                        "尚未应用任何期望状态清单"));
    }

    private ReconcileOutcome reconcile(DesiredTarget desired, Manifest manifest, String actor,
                                       Set<String> roles, boolean approved, String triggerSource) {
        String target = desired.getName();
        ActualTargetState actual = store.getOrCreateActual(target);

        // 1) 漂移检测：已收敛 → 无任何副作用（重复收敛幂等）
        if (driftDetector.isConverged(desired, actual)) {
            return new ReconcileOutcome(target, actual.getFlowId(), false, true,
                    FlowStatus.SUCCEEDED, ReasonCode.ALREADY_CONVERGED,
                    "稳定版本 " + actual.getStableVersion() + " 与期望一致，无需动作",
                    actual.getStableVersion());
        }

        // 2) 前置依赖未完成：拒绝且不跳过
        for (String dep : desired.getDependsOn()) {
            DesiredTarget depDesired = manifest.getTargets().get(dep);
            ActualTargetState depActual = store.getOrCreateActual(dep);
            if (!driftDetector.isConverged(depDesired, depActual)) {
                rejectOnly(target, actor, desired.getDesiredVersion(),
                        ReasonCode.PREREQUISITE_NOT_READY, "前置目标 " + dep + " 尚未收敛", triggerSource);
                return rejected(target, ReasonCode.PREREQUISITE_NOT_READY,
                        "前置未完成: " + dep, actual.getStableVersion());
            }
        }

        // 3) 门禁（只读）：拒绝不改版本状态、不占资源
        ReasonCode gate = policyGate.check(desired, actor, roles, approved);
        if (gate != ReasonCode.NONE) {
            rejectOnly(target, actor, desired.getDesiredVersion(), gate,
                    "推进门禁拒绝: " + gate.getDefaultMessage(), triggerSource);
            return rejected(target, gate, gate.getDefaultMessage(), actual.getStableVersion());
        }

        // 4) 并发合并：进行中的同目标流程不重复创建，阶段不会被执行两次
        Optional<FlowRecord> activeFlow = store.findActiveFlow(target);
        if (activeFlow.isPresent()) {
            FlowRecord f = activeFlow.get();
            return new ReconcileOutcome(target, f.getFlowId(), false, false, f.getStatus(),
                    ReasonCode.DUPLICATE_TRIGGER_COALESCED,
                    "已有进行中的流程 " + f.getFlowId() + "(" + f.getStatus() + ")，触发已合并",
                    actual.getStableVersion());
        }

        // 5) 额度：有界排队或拒绝
        QuotaManager.Admission admission = quota.tryAcquire(target);
        if (admission == QuotaManager.Admission.REJECTED) {
            rejectOnly(target, actor, desired.getDesiredVersion(), ReasonCode.QUOTA_REJECTED,
                    "并发槽与队列均已满", triggerSource);
            return rejected(target, ReasonCode.QUOTA_REJECTED, "并发额度与队列均已满",
                    actual.getStableVersion());
        }
        if (admission == QuotaManager.Admission.QUEUED) {
            return new ReconcileOutcome(target, null, false, false, FlowStatus.WAITING_QUOTA,
                    ReasonCode.QUOTA_QUEUED, "已进入有界等待队列", actual.getStableVersion());
        }

        // 6) 租约：过期可接管；被占用是明确拒绝而非死锁
        String holder = props.getExecutorId() + ":" + UUID.randomUUID();
        long token;
        try {
            token = leases.acquire(target, holder, props.getLeaseTtlMillis());
        } catch (DeliveryRejectedException e) {
            quota.release(target);
            return rejected(target, e.getReasonCode(), e.getMessage(), actual.getStableVersion());
        }

        // 7) 建立并持久化流程（重启恢复依据）
        FlowRecord flow = newFlow(desired, actor, holder, token, actual);
        record(flow, desired, null, actual.getPhase().name(), FlowStatus.RUNNING.name(),
                ReasonCode.NONE, "流程开始, 触发源=" + triggerSource);

        try {
            // 8) 顺序执行；completedStages 保证恢复后不重复推进
            for (Stage stage : PIPELINE) {
                if (flow.getCompletedStages().contains(stage)) {
                    continue;
                }
                flow.setCurrentStage(stage);
                store.saveFlow(flow);

                StageExecutor.StageResult result = stageExecutor.run(desired, actual, flow, stage);
                if (!result.success()) {
                    return handleStageFailure(desired, actual, flow, result, triggerSource);
                }
                flow.getCompletedStages().add(stage);
                store.saveFlow(flow);
            }
        } finally {
            leases.release(target, holder, token);
            quota.release(target);
        }

        // 9) 成功收敛
        flow.setStatus(FlowStatus.SUCCEEDED);
        flow.setTerminalReason(ReasonCode.NONE);
        store.saveFlow(flow);
        record(flow, desired, null, TargetPhase.CANARY.name(), FlowStatus.SUCCEEDED.name(),
                ReasonCode.NONE, "已发布稳定版本 " + desired.getDesiredVersion());
        return new ReconcileOutcome(target, flow.getFlowId(), true, true,
                FlowStatus.SUCCEEDED, ReasonCode.NONE,
                "发布成功: " + desired.getDesiredVersion(), desired.getDesiredVersion());
    }

    private FlowRecord newFlow(DesiredTarget desired, String actor, String holder, long token,
                               ActualTargetState actual) {
        FlowRecord flow = new FlowRecord();
        flow.setFlowId("flow-" + UUID.randomUUID());
        flow.setTarget(desired.getName());
        flow.setDesiredVersion(desired.getDesiredVersion());
        flow.setActor(actor);
        flow.setStatus(FlowStatus.RUNNING);
        flow.setStableVersionBefore(actual.getStableVersion());
        flow.setCreatedAt(System.currentTimeMillis());
        flow.setDeadlineAt(System.currentTimeMillis() + desired.getTimeoutSeconds() * 1000L);
        flow.setLeaseHolder(holder);
        flow.setFencingToken(token);
        store.saveFlow(flow);
        return flow;
    }

    private ReconcileOutcome handleStageFailure(DesiredTarget desired, ActualTargetState actual,
                                                FlowRecord flow, StageExecutor.StageResult result,
                                                String triggerSource) {
        if (result.reason() == ReasonCode.LEASE_EXPIRED) {
            // 已失去写资格：标记失败并等待接管，不做额外状态写动作
            flow.setStatus(FlowStatus.FAILED);
            flow.setTerminalReason(ReasonCode.LEASE_EXPIRED);
            store.saveFlow(flow);
            return new ReconcileOutcome(desired.getName(), flow.getFlowId(), true, false,
                    FlowStatus.FAILED, ReasonCode.LEASE_EXPIRED, result.detail(),
                    actual.getStableVersion());
        }
        ReasonCode cause = switch (result.reason()) {
            case HEALTH_CHECK_FAILED -> ReasonCode.HEALTH_CHECK_FAILED;
            case STAGE_TIMEOUT -> ReasonCode.STAGE_TIMEOUT;
            default -> ReasonCode.STAGE_FAILED;
        };
        record(flow, desired, flow.getCurrentStage(), actual.getPhase().name(), "ROLLING_BACK",
                cause, "触发自动回滚: " + result.detail());
        return performRollback(desired, actual, flow, cause, triggerSource);
    }

    /** 手动回滚入口。 */
    public ReconcileOutcome rollback(String target, String actor) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        return performRollback(desired, store.getOrCreateActual(target), null,
                ReasonCode.ROLLBACK_DONE, "manual:" + actor);
    }

    /**
     * 回滚到“当前仍稳定的上一个版本”。
     * 灰度/构建失败时 PROMOTE 未发生，actual.stableVersion 仍指向上一个稳定版本。
     * 回滚后清空灰度版本与百分比、相位 ROLLED_BACK，杜绝半发布残留；版本变更全程有事件可追溯。
     */
    private ReconcileOutcome performRollback(DesiredTarget desired, ActualTargetState actual,
                                             FlowRecord flow, ReasonCode cause, String triggerSource) {
        String target = desired.getName();
        String stable = actual.getStableVersion();
        long token = flow == null ? 0L : flow.getFencingToken();
        String flowId = flow == null ? "rollback-" + UUID.randomUUID() : flow.getFlowId();
        String actor = flow == null ? "manual" : flow.getActor();

        if (stable == null) {
            if (flow != null) {
                flow.setStatus(FlowStatus.FAILED);
                flow.setTerminalReason(ReasonCode.NO_STABLE_VERSION);
                store.saveFlow(flow);
            }
            actual.setPhase(TargetPhase.FAILED);
            store.saveActual(actual, token);
            recordSystem(target, actor, flowId, actual.getPhase().name(),
                    TargetPhase.FAILED.name(), ReasonCode.NO_STABLE_VERSION,
                    "不存在可回滚的上一个稳定版本", token);
            return new ReconcileOutcome(target, flowId, true, false, FlowStatus.FAILED,
                    ReasonCode.NO_STABLE_VERSION, "不存在上一个稳定版本，无法回滚", null);
        }

        infra.rollback(target, stable, flowId);
        actual.setPreviousStableVersion(actual.getCanaryVersion());
        actual.setCanaryVersion(null);
        actual.setCanaryPercent(0);
        actual.setStableVersion(stable);
        actual.setPhase(TargetPhase.ROLLED_BACK);
        store.saveActual(actual, token);

        if (flow != null) {
            flow.setStatus(FlowStatus.ROLLED_BACK);
            flow.setTerminalReason(cause);
            store.saveFlow(flow);
        }
        recordSystem(target, actor, flowId, "HALF/FAILED", TargetPhase.ROLLED_BACK.name(), cause,
                "已回滚到稳定版本 " + stable + "，灰度残留已清空，触发源=" + triggerSource, token);
        return new ReconcileOutcome(target, flowId, true, false, FlowStatus.ROLLED_BACK, cause,
                "已回滚到 " + stable, stable);
    }

    public ReconcileOutcome cancel(String target, String actor) {
        Optional<FlowRecord> active = store.findActiveFlow(target);
        if (active.isEmpty()) {
            return rejected(target, ReasonCode.NONE, "目标没有进行中的流程",
                    store.getOrCreateActual(target).getStableVersion());
        }
        FlowRecord flow = active.get();
        flow.setStatus(FlowStatus.CANCELLED);
        flow.setTerminalReason(ReasonCode.FLOW_CANCELLED);
        store.saveFlow(flow);
        leases.release(target, flow.getLeaseHolder(), flow.getFencingToken());
        quota.release(target);
        recordSystem(target, actor, flow.getFlowId(), FlowStatus.RUNNING.name(),
                FlowStatus.CANCELLED.name(), ReasonCode.FLOW_CANCELLED,
                "流程被显式取消，已释放租约与额度", flow.getFencingToken());
        return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                FlowStatus.CANCELLED, ReasonCode.FLOW_CANCELLED, "流程已取消",
                store.getOrCreateActual(target).getStableVersion());
    }

    private ReconcileOutcome rejected(String target, ReasonCode reason, String message,
                                      String stableVersion) {
        return new ReconcileOutcome(target, null, false, false,
                FlowStatus.REJECTED, reason, message, stableVersion);
    }

    private void rejectOnly(String target, String actor, String inputVersion, ReasonCode reason,
                            String detail, String triggerSource) {
        recordSystem(target, actor, null, "REJECT-CHECK", "REJECTED", reason,
                detail + " (触发源=" + triggerSource + ")", 0L);
    }

    private void record(FlowRecord flow, DesiredTarget desired, Stage stage,
                        String from, String to, ReasonCode reason, String detail) {
        EventRecord e = new EventRecord();
        e.setFlowId(flow.getFlowId());
        e.setTarget(desired.getName());
        e.setActor(flow.getActor());
        e.setStage(stage);
        e.setFromState(from);
        e.setToState(to);
        e.setReason(reason);
        e.setDetail(detail);
        e.setInputVersion(desired.getDesiredVersion());
        store.appendEvent(e, flow.getFencingToken());
    }

    private void recordSystem(String target, String actor, String flowId, String from, String to,
                              ReasonCode reason, String detail, long token) {
        EventRecord e = new EventRecord();
        e.setTarget(target);
        e.setActor(actor);
        e.setFlowId(flowId);
        e.setFromState(from);
        e.setToState(to);
        e.setReason(reason);
        e.setDetail(detail);
        store.appendEvent(e, token);
    }
}
