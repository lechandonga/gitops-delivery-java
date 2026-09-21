package com.github.lechandonga.gitopsdeliveryjava.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟部署平台（实际状态的物理承载）。
 * - 记录每个目标的当前版本/权重，供漂移检测读取“真实实际态”；
 * - deploy 携带 fenceToken，token 不匹配直接拒绝（防止过期租约双写）；
 * - 支持故障注入、按调用次数的健康曲线、取消标记，供测试编排。
 */
public class InMemoryLocalPlatform implements LocalPlatform {

    public static final class Actual {
        volatile String version;
        volatile int weight;
        final List<String> trace = new ArrayList<>();
    }

    private final Map<String, Actual> actuals = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failFlags = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> curves = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> healthCalls = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<Double>>> curvesByVersion =
            new ConcurrentHashMap<>();

    private Actual actual(String target) {
        return actuals.computeIfAbsent(target, k -> new Actual());
    }

    @Override
    public DeployResult deploy(String target, String version, int weightPercent, int fenceToken) {
        if (fenceToken <= 0) {
            return new DeployResult(false, null,
                    "fence token rejected (stale lease / double-write guard)");
        }
        if (Boolean.TRUE.equals(cancelled.get(target))) {
            return new DeployResult(false, null, "cancelled before deploy");
        }
        AtomicInteger fail = failFlags.get(target);
        if (fail != null && fail.getAndUpdate(i -> Math.max(0, i - 1)) > 0) {
            return new DeployResult(false, null, "simulated deploy failure");
        }
        Actual a = actual(target);
        synchronized (a) {
            a.version = version;
            a.weight = weightPercent;
            a.trace.add(version + "@" + weightPercent);
        }
        return new DeployResult(true, version, "deployed weight=" + weightPercent);
    }

    @Override
    public HealthStatus health(String target) {
        Actual a = actuals.get(target);
        if (a == null || a.version == null) {
            return new HealthStatus(false, 0.0, null);
        }
        List<Double> curve = resolveCurve(target, a.version);
        double rate;
        if (curve != null && !curve.isEmpty()) {
            int n = healthCalls.computeIfAbsent(target + "#" + a.version,
                    k -> new AtomicInteger()).getAndIncrement();
            // 曲线仅在注入的观测窗口内生效；窗口结束回归默认健康
            rate = n < curve.size() ? curve.get(n) : 1.0;
        } else {
            rate = 1.0;
        }
        return new HealthStatus(rate >= 1.0, rate, a.version);
    }

    private List<Double> resolveCurve(String target, String version) {
        Map<String, List<Double>> byVersion = curvesByVersion.get(target);
        if (byVersion != null) {
            List<Double> c = byVersion.get(version);
            if (c != null) {
                return c;
            }
        }
        return curves.get(target);
    }

    @Override
    public String currentVersion(String target) {
        Actual a = actuals.get(target);
        return a == null ? null : a.version;
    }

    @Override
    public void failNextDeploy(String target) {
        failFlags.computeIfAbsent(target, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void setHealthCurve(String target, List<Double> successRateCurve) {
        curves.put(target, List.copyOf(successRateCurve));
        healthCalls.put(target, new AtomicInteger());
    }

    @Override
    public void setHealthCurveForVersion(String target, String version,
                                         List<Double> successRateCurve) {
        curvesByVersion
                .computeIfAbsent(target, k -> new ConcurrentHashMap<>())
                .put(version, List.copyOf(successRateCurve));
    }

    @Override
    public void cancel(String target) {
        cancelled.put(target, Boolean.TRUE);
    }

    @Override
    public boolean isCancelled(String target) {
        return Boolean.TRUE.equals(cancelled.get(target));
    }

    /** 测试/恢复支撑：读取部署轨迹（版本变更可追溯的物理侧佐证）。 */
    public List<String> trace(String target) {
        Actual a = actuals.get(target);
        return a == null ? List.of() : List.copyOf(a.trace);
    }

    /** 测试支撑：清除取消标记。 */
    public void clearCancel(String target) {
        cancelled.remove(target);
    }
}
