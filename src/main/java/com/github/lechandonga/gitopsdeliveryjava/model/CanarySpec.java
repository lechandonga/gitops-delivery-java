package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 灰度放量策略。缺失字段的默认语义是确定且稳定的：
 * enabled=false 表示不走灰度，直接稳定发布。
 */
public class CanarySpec {

    private boolean enabled = false;
    private int initialPercent = 10;
    private List<Integer> steps = new ArrayList<>(List.of(10, 50, 100));
    private double minHealthyRatio = 0.8d;
    private long maxRolloutSeconds = 60L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getInitialPercent() { return initialPercent; }
    public void setInitialPercent(int initialPercent) { this.initialPercent = initialPercent; }
    public List<Integer> getSteps() { return steps; }
    public void setSteps(List<Integer> steps) { this.steps = steps; }
    public double getMinHealthyRatio() { return minHealthyRatio; }
    public void setMinHealthyRatio(double minHealthyRatio) { this.minHealthyRatio = minHealthyRatio; }
    public long getMaxRolloutSeconds() { return maxRolloutSeconds; }
    public void setMaxRolloutSeconds(long maxRolloutSeconds) { this.maxRolloutSeconds = maxRolloutSeconds; }
}
