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
import com.github.lechandonga.gitopsdeliveryjava.model.FreezeWindow;
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
    private final FreezeGate freezeGate;
    private final VersionHistory versionHistory;

    public DeliveryService(StateStore store, LeaseManager leases, QuotaManager quota,
                           DependencyResolver resolver, DriftDetector driftDetector,
                           StageExecutor stageExecutor, PolicyGate policyGate,
                           SimulatedInfrastructure infra, DeliveryProperties props,
                           FreezeGate freezeGate, VersionHistory versionHistory) {
        this.store = store;
        this.leases = leases;
        this.quota = quota;
        this.resolver = resolver;
        this.driftDetector = driftDetector;
        this.stageExecutor = stageExecutor;
        this.policyGate = policyGate;
        this.infra = infra;
        this.props = props;
        this.freezeGate = freezeGate;
        this.versionHistory = versionHistory;
    }

    /** StateStore 完成初始化后，从旧事件/旧快照重建历史稳定版本目录（老数据不丢、不报错）。 */
    @jakarta.annotation.PostConstruct
    void bootstrapVersionHistory() {
        versionHistory.rebuildFromLegacyData();
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

        // 3.5) 变更冻结窗口（只读）：拦截常规变更推进，但紧急回滚/取消不走这里。
        //      被拦截不产生任何版本状态变更、不占资源；窗口过后下一次触发自然恢复。
        var frozen = freezeGate.activeWindow(desired);
        if (frozen.isPresent()) {
            FreezeWindow w = frozen.get();
            rejectOnly(target, actor, desired.getDesiredVersion(), ReasonCode.FREEZE_WINDOW_BLOCKED,
                    "冻结窗口内禁止常规变更: type=" + w.getType()
                            + " [" + w.getStart() + "," + w.getEnd() + ")", triggerSource);
            return rejected(target, ReasonCode.FREEZE_WINDOW_BLOCKED,
                    "处于变更冻结窗口 [" + w.getStart() + "," + w.getEnd()
                            + ")，常规推进被拦截；紧急回滚/取消仍可用",
                    actual.getStableVersion());
        }

        // 4) 并发合并：进行中的同目标流程不重复创建，阶段不会被执行两次
        Optional<FlowRecord> activeFlow = store.findActiveFlow(target);
        if (activeFlow.isPresent()) {
            FlowRecord f = activeFlow.get();
            // 已暂停且已请求恢复：继续驱动（见 driveFlow）；冻结/排队等情况也由此统一处理
            if (f.getStatus() == FlowStatus.PAUSED && f.isResumeRequested()) {
                return driveFlow(desired, actual, f, actor, triggerSource);
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
                ReasonCode.NONE, "流程开始, 触发源=" + triggerSource, null);
        return runToEnd(desired, actual, flow, triggerSource, true);
    }

    /**
     * 驱动一个已获得额度与租约的流程跑到终态/暂停态。
     * completedStages 保证恢复/续跑不重复阶段；canaryStepIndex 保证灰度步不重放。
     */
    private ReconcileOutcome runToEnd(DesiredTarget desired, ActualTargetState actual,
                                      FlowRecord flow, String triggerSource, boolean releaseOnExit) {
        String target = desired.getName();
        String acquiredHolder = flow.getLeaseHolder();
        long acquiredToken = flow.getFencingToken();
        for (Stage stage : PIPELINE) {
            if (flow.getCompletedStages().contains(stage)) {
                continue;
            }
            flow.setCurrentStage(stage);
            store.saveFlow(flow);

            StageExecutor.StageResult result = stageExecutor.run(desired, actual, flow, stage);
            if (!result.success()) {
                if (result.paused()) {
                    // 暂停路径在 pauseAtCurrentProgress 内自行释放租约与额度
                    return pauseAtCurrentProgress(desired, actual, flow, result.detail(),
                            triggerSource, releaseOnExit);
                }
                // 失败/回滚路径自行释放本次 holder/token，绝不误放恢复续跑的新槽
                return handleStageFailure(desired, actual, flow, result, triggerSource,
                        releaseOnExit);
            }
            flow.getCompletedStages().add(stage);
            store.saveFlow(flow);
        }

        flow.setStatus(FlowStatus.SUCCEEDED);
        flow.setTerminalReason(ReasonCode.NONE);
        store.saveFlow(flow);
        versionHistory.recordStable(target, desired.getDesiredVersion(), flow.getActor(),
                flow.getFlowId(), System.currentTimeMillis());
        leases.release(target, acquiredHolder, acquiredToken);
        String promoted = quota.release(target);
        if (promoted != null) {
            // 有排队目标被 FIFO 提升：由下一轮周期/人工收敛驱动（本地排队策略，见文档）
            recordSystem(promoted, "system", null, "QUOTA", "PROMOTED",
                    ReasonCode.QUOTA_QUEUED, "槽位释放，排队目标具备获槽资格", 0L);
        }
        record(flow, desired, null, TargetPhase.CANARY.name(), FlowStatus.SUCCEEDED.name(),
                ReasonCode.NONE, "已发布稳定版本 " + desired.getDesiredVersion(),
                desired.getDesiredVersion());
        return new ReconcileOutcome(target, flow.getFlowId(), true, true,
                FlowStatus.SUCCEEDED, ReasonCode.NONE,
                "发布成功: " + desired.getDesiredVersion(), desired.getDesiredVersion());
    }

    /**
     * 暂停在当前灰度进度：不判成功/失败，释放租约与并发额度
     * （挂起目标不得长期白占额度），状态 PAUSED 持久化，重启后仍可识别、可恢复。
     */
    private ReconcileOutcome pauseAtCurrentProgress(DesiredTarget desired, ActualTargetState actual,
                                                    FlowRecord flow, String detail,
                                                    String triggerSource, boolean releaseOnExit) {
        flow.setPauseRequested(false); // 刹车已生效
        infra.clearPause(flow.getFlowId());
        flow.setStatus(FlowStatus.PAUSED);
        flow.setTerminalReason(ReasonCode.NONE);
        store.saveFlow(flow);
        leases.release(desired.getName(), flow.getLeaseHolder(), flow.getFencingToken());
        quota.release(desired.getName());
        record(flow, desired, flow.getCurrentStage(), flow.getCurrentStage() == null ? null
                        : flow.getCurrentStage().name(), FlowStatus.PAUSED.name(),
                ReasonCode.FLOW_PAUSED,
                "灰度人工暂停，停在 " + actual.getCanaryPercent() + "%，已释放租约与额度，触发源="
                        + triggerSource + " (" + detail + ")",
                null);
        return new ReconcileOutcome(desired.getName(), flow.getFlowId(), true, false,
                FlowStatus.PAUSED, ReasonCode.FLOW_PAUSED,
                "已暂停在灰度 " + actual.getCanaryPercent() + "%", actual.getStableVersion());
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
                                                String triggerSource, boolean releaseOnExit) {
        if (result.reason() == ReasonCode.LEASE_EXPIRED) {
            // 已失去写资格：标记失败并等待接管，不做额外状态写动作
            flow.setStatus(FlowStatus.FAILED);
            flow.setTerminalReason(ReasonCode.LEASE_EXPIRED);
            store.saveFlow(flow);
            if (releaseOnExit) {
                leases.release(desired.getName(), flow.getLeaseHolder(), flow.getFencingToken());
                quota.release(desired.getName());
            }
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
                cause, "触发自动回滚: " + result.detail(), null);
        ReconcileOutcome out = performRollback(desired, actual, flow, cause, triggerSource,
                actual.getStableVersion());
        if (releaseOnExit) {
            leases.release(desired.getName(), flow.getLeaseHolder(), flow.getFencingToken());
            quota.release(desired.getName());
        }
        return out;
    }

    /** 手动回滚入口：退回上一个稳定版本（冻结窗口内仍可执行——冻结防变更不防恢复）。 */
    public ReconcileOutcome rollback(String target, String actor) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        ActualTargetState actual = store.getOrCreateActual(target);
        return rollbackToVersion(target, actual.getStableVersion(), actor, false);
    }

    /**
     * 回退到指定的历史稳定版本。该版本必须曾在本目标上稳定发布过，
     * 否则以 {@link ReasonCode#TARGET_VERSION_UNKNOWN} 明确拒绝
     * （与“根本没有可回滚版本”的 NO_STABLE_VERSION 区分）。
     * 回退本身是一次可追溯的版本变更：事件记录 from(inputVersion) → to(targetVersion) 与触发者。
     * 冻结窗口内允许执行（恢复动作）；目标存在活动发布流程时以 ROLLBACK_TARGET_BUSY 确定拒绝，
     * 绝不两边同时推进。
     */
    public ReconcileOutcome rollbackTo(String target, String requestedVersion, String actor) {
        return rollbackToVersion(target, requestedVersion, actor, true);
    }

    private ReconcileOutcome rollbackToVersion(String target, String requestedVersion,
                                               String actor, boolean explicitVersion) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        ActualTargetState actual = store.getOrCreateActual(target);

        Optional<FlowRecord> active = store.findActiveFlow(target);
        if (active.isPresent()) {
            FlowRecord f = active.get();
            recordSystem(target, actor, null, "ROLLBACK-CHECK", "REJECTED",
                    ReasonCode.ROLLBACK_TARGET_BUSY,
                    "目标存在活动流程 " + f.getFlowId() + "(" + f.getStatus()
                            + ")，拒绝回退以避免并发推进冲突", 0L, actual.getStableVersion(), null);
            return rejected(target, ReasonCode.ROLLBACK_TARGET_BUSY,
                    "目标存在进行中的发布流程(" + f.getStatus()
                            + ")，请先取消或等待其结束后再回退",
                    actual.getStableVersion());
        }

        String fromVersion = actual.getStableVersion();
        if (requestedVersion == null || requestedVersion.isBlank()) {
            ReasonCode code = explicitVersion
                    ? ReasonCode.TARGET_VERSION_UNKNOWN : ReasonCode.NO_STABLE_VERSION;
            return rejected(target, code,
                    explicitVersion ? "未指定回退目标版本" : "不存在可回滚的上一个稳定版本",
                    fromVersion);
        }
        if (!versionHistory.wasStable(target, requestedVersion)) {
            recordSystem(target, actor, null, "ROLLBACK-CHECK", "REJECTED",
                    ReasonCode.TARGET_VERSION_UNKNOWN,
                    "版本 " + requestedVersion + " 从未在目标 " + target + " 上稳定发布过", 0L,
                    fromVersion, null);
            return rejected(target, ReasonCode.TARGET_VERSION_UNKNOWN,
                    "版本 " + requestedVersion + " 不是该目标曾稳定发布过的历史版本",
                    fromVersion);
        }
        ReasonCode cause = explicitVersion
                ? ReasonCode.ROLLBACK_HISTORY_DONE : ReasonCode.ROLLBACK_DONE;
        return performRollback(desired, actual, null, cause,
                explicitVersion ? "rollback-to:" + actor : "manual:" + actor,
                requestedVersion);
    }

    /**
     * 执行版本切换到 {@code targetVersion}（调用方已完成历史版本合法性校验）。
     * 灰度/构建失败时 PROMOTE 未发生，actual.stableVersion 仍指向上一个稳定版本。
     * 回滚后清空灰度版本与百分比、相位 ROLLED_BACK，杜绝半发布残留；版本变更全程有事件可追溯。
     */
    private ReconcileOutcome performRollback(DesiredTarget desired, ActualTargetState actual,
                                             FlowRecord flow, ReasonCode cause,
                                             String triggerSource, String targetVersion) {
        String target = desired.getName() != null ? desired.getName()
                : (actual != null ? actual.getName() : null);
        if (target != null && desired.getName() == null) {
            desired.setName(target); // 兼容未经 serde 补名的旧清单读取路径
        }
        String stable = targetVersion;
        long token = flow == null ? 0L : flow.getFencingToken();
        String flowId = flow == null ? "rollback-" + UUID.randomUUID() : flow.getFlowId();
        String actor = flow == null ? triggerSource : flow.getActor();

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
                    "不存在可回滚的稳定版本", token, null, null);
            return new ReconcileOutcome(target, flowId, true, false, FlowStatus.FAILED,
                    ReasonCode.NO_STABLE_VERSION, "不存在上一个稳定版本，无法回滚", null);
        }

        String fromVersion = actual.getStableVersion();
        if (stable == null || target == null) {
            throw new DeliveryRejectedException(ReasonCode.NO_STABLE_VERSION,
                    "回退目标版本解析为空: target=" + target + " stable=" + stable);
        }
        infra.rollback(target, stable, flowId);
        actual.setPreviousStableVersion(fromVersion);
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
                "版本变更 " + fromVersion + " -> " + stable
                        + "，灰度残留已清空，触发源=" + triggerSource,
                token, fromVersion, stable);
        return new ReconcileOutcome(target, flowId, true, false, FlowStatus.ROLLED_BACK, cause,
                "已回滚到 " + stable, stable);
    }

    /**
     * 人工暂停灰度中的流程。
     * <ul>
     *   <li>没有活动流程 → PAUSE_NOT_RUNNING（与“已暂停”可区分）；</li>
     *   <li>已经暂停（含重复请求）→ FLOW_ALREADY_PAUSED，幂等无副作用；</li>
     *   <li>运行中：登记暂停请求，执行循环在下一个灰度检查点（步前/步后）停下，
     *       已放出的量保留，未放出的量绝不因刹车多发一次。</li>
     * </ul>
     */
    public ReconcileOutcome pause(String target, String actor) {
        Optional<FlowRecord> active = store.findActiveFlow(target);
        if (active.isEmpty()) {
            return rejected(target, ReasonCode.PAUSE_NOT_RUNNING,
                    "目标没有正在推进的发布流程，无法暂停",
                    store.getOrCreateActual(target).getStableVersion());
        }
        FlowRecord flow = active.get();
        ActualTargetState actual = store.getOrCreateActual(target);
        if (flow.getStatus() == FlowStatus.PAUSED) {
            return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                    FlowStatus.PAUSED, ReasonCode.FLOW_ALREADY_PAUSED,
                    "流程已是暂停状态，重复暂停无副作用", actual.getStableVersion());
        }
        flow.setPauseRequested(true);
        store.saveFlow(flow);
        infra.requestPause(flow.getFlowId());
        recordSystem(target, actor, flow.getFlowId(), flow.getStatus().name(),
                "PAUSE_REQUESTED", ReasonCode.FLOW_PAUSED,
                "收到人工暂停请求，将在下一个灰度检查点停下，不产生新的放量",
                flow.getFencingToken());
        return new ReconcileOutcome(target, flow.getFlowId(), true, false,
                flow.getStatus(), ReasonCode.FLOW_PAUSED,
                "暂停请求已登记，流程将停在最近的灰度检查点", actual.getStableVersion());
    }

    /**
     * 恢复暂停中的灰度流程：从暂停的那一步继续，已放过的灰度步（canaryStepIndex）不重放，
     * 不会多发一次版本。重复恢复无副作用；对非暂停流程以 RESUME_NOT_PAUSED 明确且可区分地拒绝。
     * 额度满时按 FIFO 排队等待（FLOW_RESUME_QUEUED），而不是直接失败；
     * 任何时刻真正推进的目标数都不超过 maxConcurrency。
     */
    public ReconcileOutcome resume(String target, String actor) {
        Manifest manifest = requireManifest();
        DesiredTarget desired = manifest.getTargets().get(target);
        if (desired == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单中不存在目标: " + target);
        }
        ActualTargetState actual = store.getOrCreateActual(target);
        Optional<FlowRecord> active = store.findActiveFlow(target);
        if (active.isEmpty()) {
            return rejected(target, ReasonCode.RESUME_NOT_PAUSED,
                    "目标没有暂停中的发布流程，无法恢复", actual.getStableVersion());
        }
        FlowRecord flow = active.get();
        if (flow.getStatus() != FlowStatus.PAUSED) {
            return new ReconcileOutcome(target, flow.getFlowId(), false, false, flow.getStatus(),
                    ReasonCode.RESUME_NOT_PAUSED,
                    "流程不是暂停状态(" + flow.getStatus() + ")，恢复被拒绝（可区分于无流程）",
                    actual.getStableVersion());
        }
        if (flow.isResumeRequested() && quota.isQueued(target)) {
            // 已排队：槽位若已释放则 FIFO 出队并立即续跑；否则继续排队（重复调用无副作用）
            if (quota.activeCount() < props.getMaxConcurrency()) {
                return driveFlow(desired, actual, flow, actor, "resume:" + actor);
            }
            return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                    FlowStatus.WAITING_QUOTA, ReasonCode.FLOW_RESUME_QUEUED,
                    "恢复仍在 FIFO 队列中等待额度（重复调用无副作用）",
                    actual.getStableVersion());
        }

        QuotaManager.Admission admission = quota.tryAcquire(target);
        if (admission == QuotaManager.Admission.REJECTED) {
            return rejected(target, ReasonCode.QUOTA_REJECTED,
                    "并发槽与队列均已满，恢复请求被拒绝，可稍后重试",
                    actual.getStableVersion());
        }
        if (admission == QuotaManager.Admission.QUEUED) {
            flow.setResumeRequested(true);
            store.saveFlow(flow);
            recordSystem(target, actor, flow.getFlowId(), FlowStatus.PAUSED.name(),
                    FlowStatus.WAITING_QUOTA.name(), ReasonCode.FLOW_RESUME_QUEUED,
                    "恢复时额度已满，按 FIFO 排队等待；槽位释放后继续，已放量步不重放", 0L);
            return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                    FlowStatus.WAITING_QUOTA, ReasonCode.FLOW_RESUME_QUEUED,
                    "已排队等待并发额度", actual.getStableVersion());
        }
        return driveFlow(desired, actual, flow, actor, "resume:" + actor);
    }

    /**
     * 排队中的暂停流程获得槽位后（或 resume 直接获槽时）继续执行：
     * 暂停时租约已释放，此处重新获约（新 holder、新 fencing token，仍单调），
     * completedStages 与 canaryStepIndex 保证已完成动作不重放，从断点接着跑。
     */
    private ReconcileOutcome driveFlow(DesiredTarget desired, ActualTargetState actual,
                                       FlowRecord flow, String actor, String triggerSource) {
        String target = desired.getName();
        // 已排队者：槽位有空时 FIFO 出队占槽；否则继续等（不报错、不重复入队）
        if (quota.isQueued(target)) {
            if (!quota.admitQueued(target)) {
                flow.setResumeRequested(true);
                store.saveFlow(flow);
                return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                        FlowStatus.WAITING_QUOTA, ReasonCode.FLOW_RESUME_QUEUED,
                        "恢复时额度仍满，继续按 FIFO 排队等待额度", actual.getStableVersion());
            }
        } else {
            QuotaManager.Admission again = quota.tryAcquire(target);
            if (again == QuotaManager.Admission.QUEUED) {
                flow.setResumeRequested(true);
                store.saveFlow(flow);
                return new ReconcileOutcome(target, flow.getFlowId(), false, false,
                        FlowStatus.WAITING_QUOTA, ReasonCode.FLOW_RESUME_QUEUED,
                        "恢复时额度已满，按 FIFO 排队等待额度", actual.getStableVersion());
            }
            if (again == QuotaManager.Admission.REJECTED) {
                return rejected(target, ReasonCode.QUOTA_REJECTED, "并发额度与队列均已满",
                        actual.getStableVersion());
            }
        }
        String holder = props.getExecutorId() + ":" + UUID.randomUUID();
        long token = leases.acquire(target, holder, props.getLeaseTtlMillis());
        long oldToken = flow.getFencingToken();
        flow.setLeaseHolder(holder);
        flow.setFencingToken(token);
        flow.setResumeRequested(false);
        flow.setPauseRequested(false);
        infra.clearPause(flow.getFlowId());
        flow.setStatus(FlowStatus.RUNNING);
        store.saveFlow(flow);
        recordSystem(target, actor, flow.getFlowId(), FlowStatus.PAUSED.name(),
                FlowStatus.RUNNING.name(), ReasonCode.FLOW_RESUMED,
                "灰度恢复：新 token=" + token + "(旧=" + oldToken + ") stepIndex="
                        + flow.getCanaryStepIndex() + "，从第 "
                        + (flow.getCanaryStepIndex() + 1) + " 个灰度步继续，已放量步不重放",
                token);
        return runToEnd(desired, actual, flow, triggerSource, true);
    }

    public ReconcileOutcome cancel(String target, String actor) {
        Optional<FlowRecord> active = store.findActiveFlow(target);
        if (active.isEmpty()) {
            return rejected(target, ReasonCode.NONE, "目标没有进行中的流程",
                    store.getOrCreateActual(target).getStableVersion());
        }
        FlowRecord flow = active.get();
        boolean wasPaused = flow.getStatus() == FlowStatus.PAUSED;
        flow.setStatus(FlowStatus.CANCELLED);
        flow.setTerminalReason(ReasonCode.FLOW_CANCELLED);
        store.saveFlow(flow);
        // 暂停时租约/额度已释放；release/dequeue 均为幂等，不会误放别人的槽
        leases.release(target, flow.getLeaseHolder(), flow.getFencingToken());
        String promoted = quota.release(target);
        recordSystem(target, actor, flow.getFlowId(), FlowStatus.RUNNING.name(),
                FlowStatus.CANCELLED.name(), ReasonCode.FLOW_CANCELLED,
                (wasPaused ? "暂停中流程被显式取消" : "流程被显式取消，已释放租约与额度"),
                flow.getFencingToken());
        if (promoted != null) {
            recordSystem(promoted, "system", null, "QUOTA", "PROMOTED",
                    ReasonCode.QUOTA_QUEUED, "槽位释放，排队目标具备获槽资格", 0L);
        }
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
                        String from, String to, ReasonCode reason, String detail,
                        String targetVersion) {
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
        e.setTargetVersion(targetVersion);
        store.appendEvent(e, flow.getFencingToken());
    }

    private void recordSystem(String target, String actor, String flowId, String from, String to,
                              ReasonCode reason, String detail, long token) {
        recordSystem(target, actor, flowId, from, to, reason, detail, token, null, null);
    }

    private void recordSystem(String target, String actor, String flowId, String from, String to,
                              ReasonCode reason, String detail, long token,
                              String inputVersion, String targetVersion) {
        EventRecord e = new EventRecord();
        e.setTarget(target);
        e.setActor(actor);
        e.setFlowId(flowId);
        e.setFromState(from);
        e.setToState(to);
        e.setReason(reason);
        e.setDetail(detail);
        e.setInputVersion(inputVersion);
        e.setTargetVersion(targetVersion);
        store.appendEvent(e, token);
    }
}
