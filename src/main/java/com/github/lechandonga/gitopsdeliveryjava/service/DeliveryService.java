package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.engine.DependencyResolver;
import com.github.lechandonga.gitopsdeliveryjava.engine.DriftDetector;
import com.github.lechandonga.gitopsdeliveryjava.engine.QuotaManager;
import com.github.lechandonga.gitopsdeliveryjava.engine.StageExecutor;
import com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory;
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
import jakarta.annotation.PostConstruct;
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
    private final FreezeEvaluator freezeEvaluator;
    private final PauseControl pauseControl;
    private final VersionHistory versionHistory;
    private final DeliveryProperties props;

    public DeliveryService(StateStore store, LeaseManager leases, QuotaManager quota,
                           DependencyResolver resolver, DriftDetector driftDetector,
                           StageExecutor stageExecutor, PolicyGate policyGate,
                           SimulatedInfrastructure infra, DeliveryProperties props,
                           FreezeEvaluator freezeEvaluator, PauseControl pauseControl,
                           VersionHistory versionHistory) {
        this.store = store;
        this.leases = leases;
        this.quota = quota;
        this.resolver = resolver;
        this.driftDetector = driftDetector;
        this.stageExecutor = stageExecutor;
        this.policyGate = policyGate;
        this.infra = infra;
        this.freezeEvaluator = freezeEvaluator;
        this.pauseControl = pauseControl;
        this.versionHistory = versionHistory;
        this.props = props;
    }

    @PostConstruct
    void wireQuotaPromotion() {
        // 任何来源释放槽位（正常完成/失败回滚/取消/暂停）都 FIFO 提升等待者并就地续跑
        quota.setPromotionSink(promoted -> continuePromotedTarget(promoted, "quota-promotion"));
    }

    /** 测试手工装配（无 Spring）时等价于 PostConstruct 回调。 */
    public void wireQuotaPromotionForTest() {
        wireQuotaPromotion();
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

        // 3.5) 变更冻结窗口（只读）：冻结防“变更”，紧急回滚与取消走独立入口不经此判定。
        // 拒绝不改任何已发布版本状态、不占资源；窗口到 end 时刻（左闭右开）自动恢复。
        if (freezeEvaluator.isFrozen(desired)) {
            rejectOnly(target, actor, desired.getDesiredVersion(), ReasonCode.FREEZE_WINDOW_BLOCKED,
                    "目标处于变更冻结窗口，常规推进被禁止", triggerSource);
            return rejected(target, ReasonCode.FREEZE_WINDOW_BLOCKED,
                    "变更冻结窗口内，禁止常规发布推进（紧急回滚/取消不受限）",
                    actual.getStableVersion());
        }

        // 4) 并发合并：进行中的同目标流程不重复创建，阶段不会被执行两次
        Optional<FlowRecord> activeFlow = store.findActiveFlow(target);
        if (activeFlow.isPresent()) {
            FlowRecord f = activeFlow.get();
            if (f.getStatus() == FlowStatus.PAUSED && !quota.isQueued(target)) {
                // 暂停中的流程不会因为新触发自动恢复：必须走显式 resume 入口
                return new ReconcileOutcome(target, f.getFlowId(), false, false, f.getStatus(),
                        ReasonCode.FLOW_PAUSED,
                        "流程 " + f.getFlowId() + " 处于人工暂停，需显式恢复",
                        actual.getStableVersion());
            }
            if (f.getStatus() == FlowStatus.PAUSED) {
                if (quota.isQueued(target) && !f.isResumeRequested()) {
                    // 排队中的暂停流程被再次触发：视为恢复意图，允许槽位提升后续跑
                    f.setResumeRequested(true);
                    store.saveFlow(f);
                }
                // 已显式恢复、正在排队等额度的目标：触发合并，绝不重复建流程
                return new ReconcileOutcome(target, f.getFlowId(), false, false,
                        FlowStatus.WAITING_QUOTA, ReasonCode.QUOTA_QUEUED,
                        "暂停流程已恢复，正在等待并发额度", actual.getStableVersion());
            }
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

        return runPipelineAndFinish(desired, actual, flow, triggerSource, holder, token);
    }

    /**
     * 顺序执行流水线（新建与暂停恢复共用同一条路径）。
     * completedStages 与 canaryStepIndex 均为持久化进度：已完成的阶段/放量步骤跳过，
     * 没做完或失败的步骤绝不算完成，重放时必须重做。
     * 正常结束释放槽位并按 FIFO 续跑被提升的等待目标；暂停不占并发槽（由调用方处理）。
     */
    private ReconcileOutcome runPipelineAndFinish(DesiredTarget desired, ActualTargetState actual,
                                                  FlowRecord flow, String triggerSource,
                                                  String holder, long token) {
        String target = desired.getName();
        try {
            for (Stage stage : PIPELINE) {
                if (flow.getCompletedStages().contains(stage)) {
                    continue;
                }
                // 阶段边界只在“推广稳定版本之前”暂停：
                // BUILD 前/灰度第一步前没有有意义的灰度进度，暂停点一律落在放量边界，
                // 保证暂停时已有确定且已持久化的放量百分比。
                if (stage == Stage.PROMOTE_STABLE && pauseControl.pauseRequested(target)) {
                    return settlePaused(desired, actual, flow, stage, holder, token);
                }
                flow.setCurrentStage(stage);
                store.saveFlow(flow);

                StageExecutor.StageResult result = stageExecutor.run(desired, actual, flow, stage);
                if (!result.success()) {
                    if (result.reason() == ReasonCode.FLOW_PAUSED) {
                        return settlePaused(desired, actual, flow, stage, holder, token);
                    }
                    return handleStageFailure(desired, actual, flow, result, triggerSource);
                }
                // 启用灰度时，DEPLOY_CANARY 只有在全部放量步骤走完后才算完成：
                // 暂停在中途不能把阶段入 completedStages，否则恢复会跳过剩余放量（含 100%）。
                if (stage != Stage.DEPLOY_CANARY || !desired.getCanary().isEnabled()
                        || flow.getCanaryStepIndex() >= desired.getCanary().getSteps().size() - 1) {
                    flow.getCompletedStages().add(stage);
                    store.saveFlow(flow);
                }
            }
        } finally {
            leases.release(target, holder, token);
            if (flow.getStatus() != FlowStatus.PAUSED) {
                quota.release(target); // 提升+续跑由 promotionSink 完成
            }
        }

        // 成功收敛
        flow.setStatus(FlowStatus.SUCCEEDED);
        flow.setTerminalReason(ReasonCode.NONE);
        store.saveFlow(flow);
        pauseControl.clear(target);
        record(flow, desired, null, TargetPhase.CANARY.name(), FlowStatus.SUCCEEDED.name(),
                ReasonCode.NONE, "已发布稳定版本 " + desired.getDesiredVersion());
        return new ReconcileOutcome(target, flow.getFlowId(), true, true,
                FlowStatus.SUCCEEDED, ReasonCode.NONE,
                "发布成功: " + desired.getDesiredVersion(), desired.getDesiredVersion());
    }

    /** 执行器在放量/阶段边界停住：落 PAUSED 快照、释放租约与并发槽（暂停不长期占额度）。 */
    private ReconcileOutcome settlePaused(DesiredTarget desired, ActualTargetState actual,
                                          FlowRecord flow, Stage stage, String holder, long token) {
        String target = desired.getName();
        flow.setStatus(FlowStatus.PAUSED);
        flow.setCurrentStage(stage);
        flow.setTerminalReason(ReasonCode.NONE);
        store.saveFlow(flow);
        leases.release(target, holder, token);
        quota.release(target); // 暂停立即释放并发槽，等待者由 promotionSink 续跑
        pauseControl.acknowledge(target);
        recordSystem(target, flow.getActor(), flow.getFlowId(), FlowStatus.RUNNING.name(),
                FlowStatus.PAUSED.name(), ReasonCode.FLOW_PAUSED,
                "灰度人工暂停，停在 " + stage + " 放量下标=" + flow.getCanaryStepIndex()
                        + "，并发槽已释放", token);
        return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                FlowStatus.PAUSED, ReasonCode.FLOW_PAUSED,
                "已暂停在灰度进度 " + actual.getCanaryPercent() + "%",
                actual.getStableVersion());
    }

    /**
     * 续跑被提升的暂停目标。冻结/门禁在排队期间变了而无法续跑时，确定性结论是重新入队，
     * 既不直接失败也不让槽位空占。
     */
    private void continuePromotedTarget(String promotedTarget, String source) {
        try {
            Manifest manifest = requireManifest();
            DesiredTarget desired = manifest.getTargets().get(promotedTarget);
            if (desired == null) {
                quota.dequeue(promotedTarget);
                return;
            }
            FlowRecord flow = store.findActiveFlow(promotedTarget).orElse(null);
            if (flow == null || flow.getStatus() != FlowStatus.PAUSED
                    || !flow.isResumeRequested()) {
                // 等待者不是暂停恢复（例如旧的纯排队触发，无活动流程）：保持排队语义，不自动建流程
                quota.requeue(promotedTarget);
                return;
            }
            if (freezeEvaluator.isFrozen(desired)) {
                quota.requeue(promotedTarget);
                recordSystem(promotedTarget, flow.getActor(), flow.getFlowId(),
                        FlowStatus.PAUSED.name(), FlowStatus.PAUSED.name(),
                        ReasonCode.QUOTA_QUEUED,
                        "轮到额度但处于变更冻结窗口，保持暂停并重新排队", 0L);
                return;
            }
            resumeHeldFlow(desired, flow, flow.getActor(), source);
        } catch (RuntimeException ex) {
            // 续跑本身失败不得吞掉：重新入队等待下一次提升
            quota.requeue(promotedTarget);
        }
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

    /**
     * 灰度人工暂停。
     * <ul>
     *   <li>RUNNING：登记暂停请求，执行器在下一个放量边界停住（幂等：重复暂停同一 PAUSED
     *       流程直接返回原结论，无副作用）；</li>
     *   <li>WAITING_QUOTA（已在排队等额度，尚未占槽）：直接落 PAUSED 并退出队列；</li>
     *   <li>已 PAUSED：幂等返回 FLOW_PAUSED；</li>
     *   <li>无活动流程：PAUSE_NOT_RUNNING，与“恢复非暂停流程”可区分。</li>
     * </ul>
     * 暂停一旦生效立即释放并发槽：挂起目标不长期占用并发额度。
     */
    public ReconcileOutcome pause(String target, String actor) {
        FlowRecord flow = store.findActiveFlow(target).orElse(null);
        if (flow == null) {
            return rejected(target, ReasonCode.PAUSE_NOT_RUNNING,
                    "目标没有进行中的流程，无法暂停",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (flow.getStatus() == FlowStatus.PAUSED) {
            return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                    FlowStatus.PAUSED, ReasonCode.FLOW_PAUSED,
                    "流程已处于暂停状态，重复暂停无副作用",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (flow.getStatus() == FlowStatus.WAITING_QUOTA || quota.isQueued(target)) {
            flow.setStatus(FlowStatus.PAUSED);
            flow.setTerminalReason(ReasonCode.NONE);
            store.saveFlow(flow);
            quota.dequeue(target);
            pauseControl.clear(target);
            recordSystem(target, actor, flow.getFlowId(), FlowStatus.WAITING_QUOTA.name(),
                    FlowStatus.PAUSED.name(), ReasonCode.FLOW_PAUSED,
                    "排队等待额度期间人工暂停，已退出等待队列，不占并发槽", 0L);
            return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                    FlowStatus.PAUSED, ReasonCode.FLOW_PAUSED, "已在排队点暂停",
                    store.getOrCreateActual(target).getStableVersion());
        }
        // RUNNING：协作式请求，等待执行器在放量边界确认（同步本地编排，边界很快到达）
        pauseControl.request(target);
        pauseControl.awaitAcknowledged(target, 5_000L);
        FlowRecord latest = store.findFlow(flow.getFlowId()).orElse(flow);
        ActualTargetState actual = store.getOrCreateActual(target);
        if (latest.getStatus() == FlowStatus.PAUSED) {
            return new ReconcileOutcome(target, latest.getFlowId(), false, false,
                    FlowStatus.PAUSED, ReasonCode.FLOW_PAUSED,
                    "已暂停在灰度进度 " + actual.getCanaryPercent() + "%",
                    actual.getStableVersion());
        }
        // 暂停请求到达时流程恰好已越过最后边界走到终结：不留暂停标记，给出确定结论
        pauseControl.clear(target);
        return new ReconcileOutcome(target, latest.getFlowId(), false,
                latest.getStatus() == FlowStatus.SUCCEEDED, latest.getStatus(),
                latest.getTerminalReason() == ReasonCode.NONE
                        ? ReasonCode.NONE : latest.getTerminalReason(),
                "暂停请求到达时流程已结束于 " + latest.getStatus(), actual.getStableVersion());
    }

    /**
     * 灰度恢复：从暂停所在步骤接着往下走。已放量步骤按 canaryStepIndex 跳过，
     * 不重放、不多发一次版本。额度满时按策略排队（QUOTA_QUEUED），不直接失败；
     * 冻结窗口内恢复属于“变更”，必须被 FREEZE_WINDOW_BLOCKED 挡下。
     */
    public ReconcileOutcome resume(String target, String actor, Set<String> roles, boolean approved) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        FlowRecord flow = store.findActiveFlow(target).orElse(null);
        if (flow == null) {
            return rejected(target, ReasonCode.RESUME_NOT_PAUSED,
                    "目标没有暂停中的流程，无法恢复",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (flow.getStatus() != FlowStatus.PAUSED) {
            return rejected(target, ReasonCode.RESUME_NOT_PAUSED,
                    "流程当前状态=" + flow.getStatus() + "，不是暂停状态",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (freezeEvaluator.isFrozen(desired)) {
            recordSystem(target, actor, flow.getFlowId(), FlowStatus.PAUSED.name(),
                    FlowStatus.PAUSED.name(), ReasonCode.FREEZE_WINDOW_BLOCKED,
                    "冻结窗口内恢复（变更）被拒绝，暂停状态不变", 0L);
            return rejected(target, ReasonCode.FREEZE_WINDOW_BLOCKED,
                    "变更冻结窗口内不能恢复推进（紧急回滚/取消不受限）",
                    store.getOrCreateActual(target).getStableVersion());
        }
        ReasonCode gate = policyGate.check(desired, actor, roles, approved);
        if (gate != ReasonCode.NONE) {
            return rejected(target, gate, gate.getDefaultMessage(),
                    store.getOrCreateActual(target).getStableVersion());
        }

        // 额度：有槽立刻续跑；槽满进入有界 FIFO 队列排队，槽释放后自动续跑
        QuotaManager.Admission admission = quota.tryAcquire(target);
        if (admission == QuotaManager.Admission.REJECTED) {
            return rejected(target, ReasonCode.QUOTA_REJECTED,
                    "并发槽与队列均已满，恢复请求被拒绝",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (admission == QuotaManager.Admission.QUEUED) {
            flow.setResumeRequested(true);
            store.saveFlow(flow);
            recordSystem(target, actor, flow.getFlowId(), FlowStatus.PAUSED.name(),
                    FlowStatus.WAITING_QUOTA.name(), ReasonCode.QUOTA_QUEUED,
                    "恢复请求已排队等待并发额度，暂停进度保留", 0L);
            return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                    FlowStatus.WAITING_QUOTA, ReasonCode.QUOTA_QUEUED,
                    "已恢复并进入额度等待队列，将从暂停步骤续跑",
                    store.getOrCreateActual(target).getStableVersion());
        }
        return resumeHeldFlow(desired, flow, actor, "manual-resume:" + actor);
    }

    /** 已拿到并发槽：获取租约并从持久化进度续跑。 */
    private ReconcileOutcome resumeHeldFlow(DesiredTarget desired, FlowRecord flow,
                                            String actor, String triggerSource) {
        String target = desired.getName();
        ActualTargetState actual = store.getOrCreateActual(target);
        String holder = props.getExecutorId() + ":resume:" + UUID.randomUUID();
        long token;
        try {
            token = leases.acquire(target, holder, props.getLeaseTtlMillis());
        } catch (DeliveryRejectedException e) {
            quota.release(target);
            return rejected(target, e.getReasonCode(), e.getMessage(), actual.getStableVersion());
        }
        flow.setLeaseHolder(holder);
        flow.setFencingToken(token);
        flow.setStatus(FlowStatus.RUNNING);
        flow.setResumeRequested(false);
        store.saveFlow(flow);
        pauseControl.clear(target);
        recordSystem(target, actor, flow.getFlowId(), FlowStatus.PAUSED.name(),
                FlowStatus.RUNNING.name(), ReasonCode.FLOW_RESUMED,
                "流程恢复，从阶段=" + flow.getCurrentStage()
                        + " 放量下标=" + flow.getCanaryStepIndex() + " 续跑（已放步骤不重放）", token);
        return runPipelineAndFinish(desired, actual, flow, triggerSource, holder, token);
    }

    /**
     * 回退到指定的历史稳定版本（一次可追溯的版本变更）。
     * <ul>
     *   <li>版本从未在该目标发布过 → VERSION_NOT_PUBLISHED；发布过但从未稳定 →
       VERSION_NEVER_STABLE；与 NO_STABLE_VERSION 严格区分；</li>
     *   <li>目标有进行中流程（含暂停）→ ACTIVE_FLOW_CONFLICT，不允许两边同时推进；</li>
     *   <li>不受变更冻结窗口限制（恢复语义），不留半发布残留；</li>
     *   <li>事件记录 fromVersion→toVersion 与 actor；之后若清单期望版本更高，照常重新漂移推进。</li>
     * </ul>
     */
    public ReconcileOutcome rollbackToVersion(String target, String version, String actor) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        if (version == null || version.isBlank()) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "必须指定要回退到的历史版本");
        }
        if (store.findActiveFlow(target).isPresent()) {
            return rejected(target, ReasonCode.ACTIVE_FLOW_CONFLICT,
                    "目标存在进行中的流程，不能回退到指定版本，请先取消或等待其结束",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (!versionHistory.publishedVersions(target).contains(version)) {
            rejectOnly(target, actor, version, ReasonCode.VERSION_NOT_PUBLISHED,
                    "指定版本从未在该目标发布过: " + version, "rollback-to:" + actor);
            return rejected(target, ReasonCode.VERSION_NOT_PUBLISHED,
                    "版本 " + version + " 从未在目标 " + target + " 上发布过",
                    store.getOrCreateActual(target).getStableVersion());
        }
        if (!versionHistory.stableVersions(target).contains(version)) {
            rejectOnly(target, actor, version, ReasonCode.VERSION_NEVER_STABLE,
                    "指定版本发布过但从未稳定: " + version, "rollback-to:" + actor);
            return rejected(target, ReasonCode.VERSION_NEVER_STABLE,
                    "版本 " + version + " 从未在目标 " + target + " 上稳定发布过，不能回退",
                    store.getOrCreateActual(target).getStableVersion());
        }

        ActualTargetState actual = store.getOrCreateActual(target);
        String fromVersion = actual.getStableVersion();
        String flowId = "rollback-to-" + UUID.randomUUID();
        FlowRecord rollbackFlow = new FlowRecord();
        rollbackFlow.setFlowId(flowId);
        rollbackFlow.setTarget(target);
        rollbackFlow.setDesiredVersion(version);
        rollbackFlow.setKind("ROLLBACK");
        rollbackFlow.setRollbackToVersion(version);
        rollbackFlow.setActor(actor);
        rollbackFlow.setStatus(FlowStatus.RUNNING);
        rollbackFlow.setStableVersionBefore(fromVersion);
        rollbackFlow.setCreatedAt(System.currentTimeMillis());
        rollbackFlow.setDeadlineAt(System.currentTimeMillis()
                + desired.getTimeoutSeconds() * 1000L);
        store.saveFlow(rollbackFlow);

        infra.rollback(target, version, flowId);
        actual.setPreviousStableVersion(fromVersion);
        actual.setCanaryVersion(null);
        actual.setCanaryPercent(0);
        actual.setStableVersion(version);
        actual.setPhase(TargetPhase.ROLLED_BACK);
        store.saveActual(actual, 0L);

        rollbackFlow.setStatus(FlowStatus.ROLLED_BACK);
        rollbackFlow.setTerminalReason(ReasonCode.TARGET_ROLLBACK_DONE);
        store.saveFlow(rollbackFlow);

        EventRecord e = new EventRecord();
        e.setTarget(target);
        e.setActor(actor);
        e.setFlowId(flowId);
        e.setFromState(TargetPhase.CANARY.name());
        e.setToState(TargetPhase.ROLLED_BACK.name());
        e.setReason(ReasonCode.TARGET_ROLLBACK_DONE);
        e.setInputVersion(version);
        e.setFromVersion(fromVersion);
        e.setToVersion(version);
        e.setDetail("指定历史版本回退: " + fromVersion + " -> " + version
                + "，半发布残留已清空；后续按清单期望版本重新漂移收敛");
        store.appendEvent(e, 0L);

        return new ReconcileOutcome(target, flowId, true, false, FlowStatus.ROLLED_BACK,
                ReasonCode.TARGET_ROLLBACK_DONE,
                "已回退到历史稳定版本 " + version, version);
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
        pauseControl.clear(target);
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
