package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.List;

/** 灰度放量步骤描述：权重序列（百分比）与每步健康观察时间、健康阈值。 */
public record CanarySpec(List<Integer> weights, long stepWaitMillis, double healthThreshold) {
    public static CanarySpec none() { return null; }
}
