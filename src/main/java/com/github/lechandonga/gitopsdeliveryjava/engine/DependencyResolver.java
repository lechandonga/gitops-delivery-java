package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段与环境间的依赖编排。
 * <ul>
 *   <li>Kahn 拓扑排序给出确定的推进顺序（按清单出现顺序打破并列，结论稳定可复现）；</li>
 *   <li>引用不存在的目标 → MANIFEST_INVALID 明确拒绝；</li>
 *   <li>存在循环依赖 → CIRCULAR_DEPENDENCY，并把环上节点写入异常消息，拒绝而非死锁；</li>
 *   <li>前置未完成由上层依据本顺序检查，得到 PREREQUISITE_NOT_READY，绝不静默跳过。</li>
 * </ul>
 */
@Component
public class DependencyResolver {

    public List<String> topoOrder(Manifest manifest) {
        Map<String, Set<String>> graph = buildGraph(manifest);

        Map<String, Integer> indegree = new HashMap<>();
        graph.keySet().forEach(k -> indegree.put(k, 0));
        for (Map.Entry<String, Set<String>> e : graph.entrySet()) {
            for (String dep : e.getValue()) {
                indegree.merge(dep, 0, (a, b) -> a);
            }
        }
        for (Map.Entry<String, Set<String>> e : graph.entrySet()) {
            // e.getKey() dependsOn each member of e.getValue()
            indegree.put(e.getKey(), e.getValue().size());
        }

        // 按清单声明顺序入队，保证确定性
        Deque<String> queue = new ArrayDeque<>();
        for (String name : manifest.getTargets().keySet()) {
            if (indegree.get(name) == 0) {
                queue.add(name);
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);
            // 找到所有依赖 node 的目标，按声明顺序处理
            for (String candidate : manifest.getTargets().keySet()) {
                if (graph.get(candidate).contains(node)) {
                    int left = indegree.merge(candidate, -1, Integer::sum);
                    if (left == 0) {
                        queue.add(candidate);
                    }
                }
            }
        }

        if (order.size() != graph.size()) {
            List<String> cycle = findCycle(graph);
            throw new DeliveryRejectedException(ReasonCode.CIRCULAR_DEPENDENCY,
                    "检测到循环依赖，环上目标: " + cycle);
        }
        return order;
    }

    private Map<String, Set<String>> buildGraph(Manifest manifest) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (String name : manifest.getTargets().keySet()) {
            graph.put(name, new LinkedHashSet<>());
        }
        manifest.getTargets().forEach((name, target) -> {
            for (String dep : target.getDependsOn()) {
                if (!graph.containsKey(dep)) {
                    throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                            "target=" + name + " 依赖了不存在的目标: " + dep);
                }
                graph.get(name).add(dep);
            }
        });
        return graph;
    }

    /** DFS 找一个具体的环，便于在拒绝原因中解释。 */
    private List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        for (String start : graph.keySet()) {
            List<String> cycle = dfsCycle(start, graph, visited, stack, onStack);
            if (cycle != null) {
                return cycle;
            }
        }
        return List.of();
    }

    private List<String> dfsCycle(String node, Map<String, Set<String>> graph, Set<String> visited,
                                  Deque<String> stack, Set<String> onStack) {
        if (onStack.contains(node)) {
            // Deque 迭代顺序为栈顶 -> 栈底（node 位于栈底），反转得到 node -> ... -> 当前节点
            List<String> path = new ArrayList<>(stack);
            java.util.Collections.reverse(path);
            int start = path.indexOf(node);
            List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(node);
            return cycle;
        }
        if (visited.contains(node)) {
            return null;
        }
        visited.add(node);
        onStack.add(node);
        stack.push(node);
        for (String dep : graph.get(node)) {
            List<String> cycle = dfsCycle(dep, graph, visited, stack, onStack);
            if (cycle != null) {
                return cycle;
            }
        }
        stack.pop();
        onStack.remove(node);
        return null;
    }
}
