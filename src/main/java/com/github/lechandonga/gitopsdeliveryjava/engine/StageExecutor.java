package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.Stage;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import com.github.lechandonga.gitopsdeliveryjava.service.SimulatedInfrastructure;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import org.springframework.stereotype.Component;

/**
 * 单个阶段的执行器。
 * <ul>
 *   <li>有界重试：最多 maxAttempts 次，重试本身幂等（模拟层按 flowId 去重）；</li>
 *   <li>超时区分：每次尝试检查流程截止时间，超时返回 TIMED_OUT，失败绝不判定成功；</li>
 *   <li>租约保护：执行前校验仍持有租约与 fencing，过期则停止（LEASE_EXPIRED）；</li>
 *   <li>灰度：DEPLOY_CANARY 按步长放量，任一步健康率不达标返回 HEALTH_CHECK_FAILED；</li>
 *   <li>每个判定点写审计事件（输入版本、阶段、原因）。</li>
 * </ul>
 */
@Component
public class StageExecutor {

    public record StageResult(boolean success, ReasonCode reason, String detail) {
        public static StageResult ok() {
            return new StageResult(true, ReasonCode.NONE, null);
        }

        public static StageResult fail(ReasonCode code, String detail) {
            return new StageResult(false, code, detail);
        }

        public boolean paused() {
            return reason == ReasonCode.FLOW_PAUSED;
        }
    }

    private final SimulatedInfrastructure infra;
    private final StateStore store;
    private final LeaseManager leases;
    private final DeliveryProperties props;

    public StageExecutor(SimulatedInfrastructure infra, StateStore store,
                         LeaseManager leases, DeliveryProperties props) {
        this.infra = infra;
        this.store = store;
        this.leases = leases;
        this.props = props;
    }

    public boolean executeStage(DesiredTarget desired, ActualTargetState actual, FlowRecord flow, Stage stage) {
        return run(desired, actual, flow, stage).success();
    }

    public StageResult run(DesiredTarget desired, ActualTargetState actual, FlowRecord flow, Stage stage) {
        int maxAttempts = Math.max(1, desired.getMaxAttempts());
        RuntimeException lastError = null;
        // 暂停期间不计入流程 deadline：恢复后续跑必须拥有完整的超时预算
        long deadline = flow.getDeadlineAt();
        if (stage == Stage.DEPLOY_CANARY && flow.getCanaryStepIndex() > 0
                && flow.getStatus() == FlowStatus.RUNNING) {
            deadline = System.currentTimeMillis() + desired.getTimeoutSeconds() * 1000L;
            flow.setDeadlineAt(deadline);
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            flow.setAttempts(attempt);
            // 人工暂停优先于一切：不重试、不判失败，停在当前进度
            if (flow.isPauseRequested() || infra.isPauseRequested(flow.getFlowId())) {
                return StageResult.fail(ReasonCode.FLOW_PAUSED,
                        "阶段=" + stage + " 执行前流程已被要求暂停");
            }
            // 超时判定：被中断/超时的流程不得判定为成功
            if (System.currentTimeMillis() >= deadline) {
                record(flow, desired, stage, actual.getPhase().name(), actual.getPhase().name(),
                        ReasonCode.STAGE_TIMEOUT, "阶段=" + stage + " 超过流程截止时间", actual);
                return StageResult.fail(ReasonCode.STAGE_TIMEOUT, "stage=" + stage);
            }
            // 租约判定：租约过期或被接管后必须立即停止，不产生双写
            if (!leases.isHolder(desired.getName(), flow.getLeaseHolder(), flow.getFencingToken())) {
                record(flow, desired, stage, actual.getPhase().name(), actual.getPhase().name(),
                        ReasonCode.LEASE_EXPIRED, "阶段=" + stage + " 执行前发现租约已失效", actual);
                return StageResult.fail(ReasonCode.LEASE_EXPIRED, "stage=" + stage);
            }
            try {
                StageResult result = doExecute(desired, actual, flow, stage);
                if (result.success()) {
                    persist(actual, flow);
                    record(flow, desired, stage, null, actual.getPhase().name(),
                            ReasonCode.NONE, "阶段成功 attempt=" + attempt, actual);
                    return result;
                }
                // 健康检查不达标属于“结论性失败”，重试无意义，直接交由上层回滚
                if (result.reason() == ReasonCode.HEALTH_CHECK_FAILED) {
                    record(flow, desired, stage, actual.getPhase().name(), actual.getPhase().name(),
                            result.reason(), result.detail(), actual);
                    return result;
                }
                // 人工暂停是结论性停机，不是失败：绝不重试、绝不再放下一个百分比
                if (result.paused()) {
                    return result;
                }
                lastError = new RuntimeException(result.detail());
            } catch (RuntimeException ex) {
                lastError = ex;
            }
            if (attempt < maxAttempts) {
                renewLease(flow);
                try {
                    Thread.sleep(10L); // 本地模拟：有界重试之间的极小退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return StageResult.fail(ReasonCode.FLOW_CANCELLED, "重试等待被中断");
                }
            }
        }
        record(flow, desired, stage, actual.getPhase().name(), TargetPhase.FAILED.name(),
                ReasonCode.RETRIES_EXHAUSTED,
                "阶段=" + stage + " 有界重试耗尽(" + maxAttempts + "), 末次错误="
                        + (lastError == null ? "" : lastError.getMessage()), actual);
        return StageResult.fail(ReasonCode.RETRIES_EXHAUSTED,
                "stage=" + stage + " attempts=" + maxAttempts);
    }

    private StageResult doExecute(DesiredTarget desired, ActualTargetState actual,
                                  FlowRecord flow, Stage stage) {
        String target = desired.getName();
        String version = desired.getDesiredVersion();
        switch (stage) {
            case BUILD -> {
                infra.buildArtifact(target, version, flow.getFlowId());
                actual.setPhase(TargetPhase.BUILT);
                actual.setBuildExecutions(infra.executionCount(target, "BUILD"));
                return StageResult.ok();
            }
            case DEPLOY_CANARY -> {
                if (!desired.getCanary().isEnabled()) {
                    // 无灰度策略：全量部署为灰度相位，下一步直接推广
                    infra.deployCanary(target, version, flow.getFlowId(), 100);
                    applyCanaryState(actual, version, 100);
                    return StageResult.ok();
                }
                java.util.List<Integer> steps = desired.getCanary().getSteps();
                for (int i = 0; i < steps.size(); i++) {
                    int percent = steps.get(i);
                    // 续跑：暂停前已放过的百分比绝不重放（i < canaryStepIndex 的步已完成）
                    if (i < flow.getCanaryStepIndex()) {
                        continue;
                    }
                    if (flow.isPauseRequested() || infra.isPauseRequested(flow.getFlowId())) {
                        return StageResult.fail(ReasonCode.FLOW_PAUSED,
                                "灰度=" + percent + "% 步前收到暂停，停在已完成 "
                                        + flow.getCanaryStepIndex() + " 步");
                    }
                    if (!leases.isHolder(target, flow.getLeaseHolder(), flow.getFencingToken())) {
                        return StageResult.fail(ReasonCode.LEASE_EXPIRED, "灰度放量中途租约失效");
                    }
                if (System.currentTimeMillis() >= flow.getDeadlineAt()) {
                        return StageResult.fail(ReasonCode.STAGE_TIMEOUT, "灰度放量超时");
                    }
                    infra.deployCanary(target, version, flow.getFlowId(), percent);
                    applyCanaryState(actual, version, percent);
                    persist(actual, flow);
                    double ratio = infra.healthRatio(target, version);
                    if (ratio < desired.getCanary().getMinHealthyRatio()) {
                        return StageResult.fail(ReasonCode.HEALTH_CHECK_FAILED,
                                "灰度=" + percent + "% 健康率=" + ratio
                                        + " 低于阈值=" + desired.getCanary().getMinHealthyRatio());
                    }
                    // 记录本步已完成（崩溃/接管后据此跳过，绝不重放）
                    flow.setCanaryStepIndex(i + 1);
                    store.saveFlow(flow);
                    // 步后栅栏：确定性注入“刚放过 percent% 立刻人工暂停”的场景
                    infra.fireCanaryBarrier(target, percent);
                    if (flow.isPauseRequested() || infra.isPauseRequested(flow.getFlowId())) {
                        return StageResult.fail(ReasonCode.FLOW_PAUSED,
                                "灰度=" + percent + "% 完成后收到暂停，停在当前进度");
                    }
                }
                return StageResult.ok();
            }
            case PROMOTE_STABLE -> {
                infra.promoteStable(target, version, flow.getFlowId());
                // 版本可追溯：保留上一个稳定版本
                actual.setPreviousStableVersion(actual.getStableVersion());
                actual.setStableVersion(version);
                actual.setCanaryVersion(null);
                actual.setCanaryPercent(0);
                actual.setPhase(TargetPhase.STABLE);
                actual.setDeployExecutions(infra.executionCount(target, "PROMOTE"));
                return StageResult.ok();
            }
            default -> {
                return StageResult.fail(ReasonCode.STAGE_FAILED, "未知阶段: " + stage);
            }
        }
    }

    private void applyCanaryState(ActualTargetState actual, String version, int percent) {
        actual.setCanaryVersion(version);
        actual.setCanaryPercent(percent);
        actual.setPhase(TargetPhase.CANARY);
        actual.setDeployExecutions(actual.getDeployExecutions() + 1);
    }

    private void persist(ActualTargetState actual, FlowRecord flow) {
        actual.setFlowId(flow.getFlowId());
        store.saveActual(actual, flow.getFencingToken());
    }

    private void renewLease(FlowRecord flow) {
        leases.renew(flow.getTarget(), flow.getLeaseHolder(), flow.getFencingToken(),
                props.getLeaseTtlMillis());
    }

    private void record(FlowRecord flow, DesiredTarget desired, Stage stage,
                        String from, String to, ReasonCode reason, String detail,
                        ActualTargetState actual) {
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
        try {
            store.appendEvent(e, flow.getFencingToken());
        } catch (RuntimeException ex) {
            // fencing 过期写入失败本身即结论：停止执行
            throw ex;
        }
        if (actual != null && reason == ReasonCode.RETRIES_EXHAUSTED) {
            actual.setPhase(TargetPhase.FAILED);
            persist(actual, flow);
        }
    }
}
