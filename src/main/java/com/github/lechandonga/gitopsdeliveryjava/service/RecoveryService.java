package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.engine.DriftDetector;
import com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 进程重启恢复。
 * <p>
 * 启动时扫描所有未终结流程：
 * <ol>
 *   <li>旧租约必然随进程死亡失效 → 以新 fencing token 安全接管（LEASE_TAKEN_OVER），
 *       旧持有者即使复活，其写入也会被 fencing 拒绝；</li>
 *   <li>已完成阶段记录在 completedStages 中，接管后不会重复推进（BUILD 不执行两次）；</li>
 *   <li>处于灰度/构建等半发布相位的，若流程无法继续则回滚到上一个稳定版本，不留残留；</li>
 *   <li>超过流程截止时间的标记为 TIMED_OUT（失败/超时/取消严格区分）；</li>
 *   <li>所有恢复动作写 RECOVERED_AFTER_RESTART 事件，结论可追溯。</li>
 * </ol>
 */
@Service
public class RecoveryService {

    private final StateStore store;
    private final LeaseManager leases;
    private final DeliveryProperties props;
    private final DriftDetector driftDetector;
    private final DeliveryService deliveryService;

    public RecoveryService(StateStore store, LeaseManager leases, DeliveryProperties props,
                           DriftDetector driftDetector, DeliveryService deliveryService) {
        this.store = store;
        this.leases = leases;
        this.props = props;
        this.driftDetector = driftDetector;
        this.deliveryService = deliveryService;
    }

    @PostConstruct
    void recoverOnStartup() {
        recover();
    }

    public int recover() {
        List<FlowRecord> unfinished = store.allFlows().stream()
                .filter(f -> !StateStore.isTerminal(f.getStatus()))
                .toList();
        long maxToken = store.allFlows().stream()
                .mapToLong(FlowRecord::getFencingToken).max().orElse(0L);
        leases.seedHighWaterMark(Math.max(maxToken, store.readFencingHighWaterMark()));
        int recovered = 0;
        for (FlowRecord flow : unfinished) {
            recoverFlow(flow);
            recovered++;
        }
        // 清理没有活动流程但残留半发布相位的实际状态（崩溃发生在状态写入之后）
        for (ActualTargetState actual : store.allActual()) {
            if (driftDetector.isHalfReleased(actual)
                    && store.findActiveFlow(actual.getName()).isEmpty()) {
                cleanupHalfRelease(actual);
                recovered++;
            }
        }
        return recovered;
    }

    private void recoverFlow(FlowRecord flow) {
        String target = flow.getTarget();
        ActualTargetState actual = store.getOrCreateActual(target);
        long now = System.currentTimeMillis();

        // 接管过期租约：拿到新的 fencing token
        long newToken = leases.takeOverExpired(target,
                props.getExecutorId() + ":recovery", props.getLeaseTtlMillis());
        long oldToken = flow.getFencingToken();
        flow.setFencingToken(newToken);
        flow.setLeaseHolder(props.getExecutorId() + ":recovery");
        store.saveFlow(flow);
        record(target, flow, ReasonCode.LEASE_TAKEN_OVER,
                "重启恢复: 接管过期租约, 旧 token=" + oldToken + " 新 token=" + newToken
                        + "，已完成阶段=" + flow.getCompletedStages());

        if (now >= flow.getDeadlineAt()) {
            flow.setStatus(FlowStatus.TIMED_OUT);
            flow.setTerminalReason(ReasonCode.STAGE_TIMEOUT);
            store.saveFlow(flow);
            cleanupHalfRelease(actual, flow);
            record(target, flow, ReasonCode.STAGE_TIMEOUT, "恢复时发现流程已超过截止时间");
            return;
        }

        if (driftDetector.isHalfReleased(actual)) {
            // 崩溃发生在灰度/部署中途：为避免残留半发布，回滚到上一个稳定版本
            cleanupHalfRelease(actual, flow);
        }
        // 清理后的流程标记失败（中断不判成功）；由下一次声明式收敛重新驱动
        if (!StateStore.isTerminal(flow.getStatus())) {
            flow.setStatus(FlowStatus.FAILED);
            flow.setTerminalReason(ReasonCode.RECOVERED_AFTER_RESTART);
            store.saveFlow(flow);
            record(target, flow, ReasonCode.INTERRUPTED_NOT_SUCCESS,
                    "恢复完成：中断流程标记为失败，等待下一轮收敛重新驱动（已完成阶段不重复）");
        }
        leases.release(target, flow.getLeaseHolder(), newToken);
    }

    private void cleanupHalfRelease(ActualTargetState actual) {
        cleanupHalfRelease(actual, null);
    }

    private void cleanupHalfRelease(ActualTargetState actual, FlowRecord flow) {
        String stable = actual.getStableVersion();
        long token = flow == null ? 0L : flow.getFencingToken();
        String flowId = flow == null ? "recovery-cleanup" : flow.getFlowId();
        if (stable == null) {
            actual.setPhase(TargetPhase.FAILED);
            store.saveActual(actual, token);
            record(actual.getName(), flow, ReasonCode.NO_STABLE_VERSION,
                    "恢复清理：无稳定版本可回滚，标记 FAILED");
            return;
        }
        actual.setCanaryVersion(null);
        actual.setCanaryPercent(0);
        actual.setStableVersion(stable);
        actual.setPhase(TargetPhase.ROLLED_BACK);
        store.saveActual(actual, token);
        record(actual.getName(), flow, ReasonCode.ROLLBACK_DONE,
                "恢复清理：回滚到稳定版本 " + stable + "，半发布残留已清空 flowId=" + flowId);
        if (flow != null && !StateStore.isTerminal(flow.getStatus())) {
            flow.setStatus(FlowStatus.ROLLED_BACK);
            flow.setTerminalReason(ReasonCode.ROLLBACK_DONE);
            store.saveFlow(flow);
        }
    }

    private void record(String target, FlowRecord flow, ReasonCode reason, String detail) {
        EventRecord e = new EventRecord();
        e.setTarget(target);
        e.setFlowId(flow == null ? null : flow.getFlowId());
        e.setActor("recovery");
        e.setReason(reason);
        e.setDetail(detail);
        e.setFromState("RECOVERY");
        e.setToState(reason.name());
        store.appendEvent(e, flow == null ? 0L : flow.getFencingToken());
    }
}
