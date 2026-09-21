package com.github.lechandonga.gitopsdeliveryjava.store;

/** 额度只读视图（供 API 观测，不暴露获取/释放能力）。 */
public interface CapacityView {
    int activeCount();

    int queueLength();
}
