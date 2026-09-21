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
        executeOnce(target, flowId, "DEPLOY:" + percent);
        deployments.put(target, new Deployment(version, percent));
        return percent;
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

    public int executionCount(String target, String stage) {
        return stageCounters.getOrDefault(target + "|" + stage, new AtomicInteger()).get();
    }

    public synchronized void reset() {
        deployments.clear();
        executedStageKeys.clear();
        stageCounters.clear();
        healthScripts.clear();
        failBuildOnce.clear();
    }
}
