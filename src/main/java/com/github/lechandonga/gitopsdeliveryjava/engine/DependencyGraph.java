package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.model.ManifestStage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 阶段依赖 DAG：
 * - 构造时校验重复 id、指向不存在阶段的依赖（清单非法，拒绝原因可区分）；
 * - topologicalOrder 使用 DFS 检测环，环路径写入异常消息，不静默、不死锁；
 * - 前置判定独立于拓扑，用于“前置未完成”的可区分拒绝。
 */
public class DependencyGraph {

    private final Map<String, ManifestStage> byId = new LinkedHashMap<>();
    private final Map<String, List<String>> deps = new HashMap<>();

    public DependencyGraph(List<ManifestStage> stages) {
        for (ManifestStage s : stages) {
            if (byId.put(s.id(), s) != null) {
                throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                        "duplicate stage id: " + s.id());
            }
        }
        for (ManifestStage s : stages) {
            List<String> list = new ArrayList<>();
            for (String dep : s.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                            "stage " + s.id() + " depends on unknown stage: " + dep);
                }
                list.add(dep);
            }
            deps.put(s.id(), list);
        }
    }

    /** Kahn 算法拓扑排序；有环时抛出携带环路径的 CYCLIC_DEPENDENCY。 */
    public List<String> topologicalOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        byId.keySet().forEach(id -> {
            indegree.put(id, 0);
            dependents.put(id, new ArrayList<>());
        });
        for (Map.Entry<String, List<String>> e : deps.entrySet()) {
            indegree.put(e.getKey(), e.getValue().size());
            for (String dep : e.getValue()) {
                dependents.get(dep).add(e.getKey());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String next : dependents.get(id)) {
                int d = indegree.merge(next, -1, Integer::sum);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }
        if (order.size() != byId.size()) {
            List<String> cycle = findCycle();
            throw new DeliveryRejectedException(ReasonCode.CYCLIC_DEPENDENCY,
                    "cyclic dependency detected among stages: " + cycle);
        }
        return order;
    }

    public boolean prerequisitesDone(String stageId, Predicate<String> isSucceeded) {
        return missingPrerequisites(stageId, isSucceeded).isEmpty();
    }

    public List<String> missingPrerequisites(String stageId, Predicate<String> isSucceeded) {
        List<String> missing = new ArrayList<>();
        for (String dep : deps.getOrDefault(stageId, List.of())) {
            if (!isSucceeded.test(dep)) {
                missing.add(dep);
            }
        }
        return missing;
    }

    /** 标准三色 DFS 找一条环路径（灰点遇到灰点即回边），仅在异常时使用。 */
    private List<String> findCycle() {
        Map<String, Integer> color = new HashMap<>();
        byId.keySet().forEach(id -> color.put(id, 0)); // 0 白 1 灰 2 黑
        for (String id : byId.keySet()) {
            if (color.get(id) == 0) {
                List<String> cyc = dfsColor(id, color, new ArrayDeque<>());
                if (cyc != null) {
                    return cyc;
                }
            }
        }
        return List.of();
    }

    private List<String> dfsColor(String id, Map<String, Integer> color, Deque<String> path) {
        color.put(id, 1);
        path.push(id);
        for (String dep : deps.getOrDefault(id, List.of())) {
            int c = color.get(dep);
            if (c == 1) {
                // 回边：当前路径（栈顶=当前 id，栈底=根）反转得到依赖方向，再接回环起点
                List<String> seg = new ArrayList<>(path);
                java.util.Collections.reverse(seg);
                seg.add(dep);
                return seg;
            }
            if (c == 0) {
                List<String> cyc = dfsColor(dep, color, path);
                if (cyc != null) {
                    return cyc;
                }
            }
        }
        path.pop();
        color.put(id, 2);
        return null;
    }
}
