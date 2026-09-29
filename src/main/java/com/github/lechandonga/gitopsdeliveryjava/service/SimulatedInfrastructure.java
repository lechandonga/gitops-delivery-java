package com.github.lechandonga.gitopsdeliveryjava.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟的执行/部署基础设施（替代真实 CI/CD 与集群）。
 * <p>
 * 关键性质：
 * <ul>
 *   <li>幂等：以 (flowId, stage, target) 为键，同一阶段同一流程只真正执行一次，
 *       重试/接管/重复收敛不会让一个阶段被执行多次；</li>
 *   <li>可解释、可注入：测试可预置某版本健康率（触发自动回滚）、构建失败一次（触发重试）；</li>
 *   <li>记录真实部署状态：当前稳定版本与灰度版本（百分比），回滚后灰度版本必须清空。</li>
 * </ul>
 */
@Component
public class SimulatedInfrastructure {

    public record Deployment(String version, int canaryPercent) {
    }

    private final Map<String, Deployment> deployments = new ConcurrentHashMap<>();
    private final Set<String> executedStageKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> stageCounters = new ConcurrentHashMap<>();
    private final Map<String, Double> healthScripts = new ConcurrentHashMap<>();
    private final Set<String> failBuildOnce = ConcurrentHashMap.newKeySet();
    private final Set<String> failDeployOnce = ConcurrentHashMap.newKeySet();
    private volatile java.util.function.Consumer<String> canaryBarrier = percentKey -> { };
    /** 运行期人工刹车信号（按 flowId）：任何入口设置后，执行器的下一个检查点立即可见。 */
    private final Set<String> pauseSignals = ConcurrentHashMap.newKeySet();

    private String key(String target, String flowId, String stage) {
        return target + "|" + flowId + "|" + stage;
    }

    private boolean executeOnce(String target, String flowId, String stage) {
        boolean first = executedStageKeys.add(key(target, flowId, stage));
        stageCounters.computeIfAbsent(target + "|" + stage, k -> new AtomicInteger())
                .addAndGet(first ? 1 : 0);
        return first;
    }

    public void buildArtifact(String target, String version, String flowId) {
        if (!executeOnce(target, flowId, "BUILD")) {
            return; // 幂等：同一流程的构建已执行过
        }
        String failKey = target + "@" + version;
        if (failBuildOnce.remove(failKey)) {
            throw new RuntimeException("模拟构建失败(一次性): " + failKey);
        }
    }

    /** 部署（或调整灰度百分比）。返回当前灰度百分比。 */
    public int deployCanary(String target, String version, String flowId, int percent) {
        String stage = "DEPLOY:" + percent;
        if (executeOnce(target, flowId, stage)) {
            String failKey = target + "@" + version + ":" + percent;
            if (failDeployOnce.remove(failKey)) {
                // 一次性失败：该 (flowId,percent) 的幂等键已消费，失败动作不得被当成已完成。
                // 下一次重试会以新流程（新 flowId）重新真正部署该百分比。
                executedStageKeys.remove(key(target, flowId, stage));
                stageCounters.computeIfAbsent(target + "|" + stage, k -> new AtomicInteger())
                        .decrementAndGet();
                throw new RuntimeException("模拟灰度部署失败(一次性): " + failKey);
            }
        }
        deployments.put(target, new Deployment(version, percent));
        return percent;
    }

    /**
     * 灰度放量步间栅栏：每次真正部署一个百分比后回调。
     * 测试用它在“放过部分量之后、下一部分量之前”确定性地踩下暂停刹车。
     */
    public void setCanaryBarrier(java.util.function.Consumer<String> barrier) {
        this.canaryBarrier = barrier == null ? p -> { } : barrier;
    }

    public void fireCanaryBarrier(String target, int percent) {
        canaryBarrier.accept(target + ":" + percent);
    }

    /** 登记人工暂停信号（幂等），对正在执行的同一流程立即生效。 */
    public void requestPause(String flowId) {
        pauseSignals.add(flowId);
    }

    /** 暂停落地/恢复后清除刹车信号。 */
    public void clearPause(String flowId) {
        pauseSignals.remove(flowId);
    }

    /** 执行器在灰度检查点调用：该流程是否被要求暂停。 */
    public boolean isPauseRequested(String flowId) {
        return pauseSignals.contains(flowId);
    }

    /** 模拟健康判定：返回 [0,1] 的健康比例，未配置脚本则完全健康。 */
    public double healthRatio(String target, String version) {
        Deployment d = deployments.get(target);
        if (d == null || !d.version().equals(version)) {
            return 0.0d; // 版本根本未部署，判定不健康
        }
        return healthScripts.getOrDefault(target + "@" + version, 1.0d);
    }

    public void promoteStable(String target, String version, String flowId) {
        if (!executeOnce(target, flowId, "PROMOTE")) {
            return;
        }
        deployments.put(target, new Deployment(version, 100));
    }

    /** 回滚到指定（上一个稳定）版本：切换稳定并清空灰度，杜绝半发布残留。 */
    public void rollback(String target, String version, String flowId) {
        executeOnce(target, flowId, "ROLLBACK");
        deployments.put(target, new Deployment(version, 100));
    }

    public Deployment currentDeployment(String target) {
        return deployments.get(target);
    }

    public void setHealthScript(String target, String version, double ratio) {
        healthScripts.put(target + "@" + version, ratio);
    }

    public void failBuildOnce(String target, String version) {
        failBuildOnce.add(target + "@" + version);
    }

    /** 让该目标该版本在指定灰度百分比的第一次部署抛错（仅一次），验证失败动作必须重放。 */
    public void failDeployCanaryOnce(String target, String version, int percent) {
        failDeployOnce.add(target + "@" + version + ":" + percent);
    }

    public int executionCount(String target, String stage) {
        return stageCounters.getOrDefault(target + "|" + stage, new AtomicInteger()).get();
    }

    public synchronized void reset() {
        deployments.clear();
        executedStageKeys.clear();
        stageCounters.clear();
        healthScripts.clear();
        failBuildOnce.clear();
        failDeployOnce.clear();
        canaryBarrier = p -> { };
        pauseSignals.clear();
    }
}
