package com.github.lechandonga.gitopsdeliveryjava.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 灰度人工暂停的进程内协作点。
 * <p>
 * 暂停是<strong>协作式</strong>的：执行器只在放量边界（灰度步骤之间、阶段之间）
 * 检查暂停标记，因此暂停必然停在一个已持久化的确定进度上，绝不中断半次放量。
 * 暂停状态的权威记录是流程快照（{@code FlowStatus.PAUSED}），本组件只负责
 * “请求 → 执行器在边界确认”的实时握手；进程重启后请求随 JVM 清空，
 * 已 PAUSED 的流程仍可凭快照恢复入口继续。
 */
@Component
public class PauseControl {

    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CountDownLatch> acknowledged = new ConcurrentHashMap<>();

    /** 请求暂停目标的活动流程，并登记确认点。 */
    public void request(String target) {
        acknowledged.computeIfAbsent(target, k -> new CountDownLatch(1));
        requested.add(target);
    }

    /**
     * 执行器在放量边界调用。
     *
     * @return true 表示本流程必须在当前进度暂停
     */
    public boolean pauseRequested(String target) {
        return requested.contains(target);
    }

    /** 执行器已在边界停住并完成状态持久化，通知请求方。 */
    public void acknowledge(String target) {
        requested.remove(target);
        CountDownLatch latch = acknowledged.get(target);
        if (latch != null) {
            latch.countDown();
        }
    }

    /** 恢复/终结时清除所有握手状态。 */
    public void clear(String target) {
        requested.remove(target);
        CountDownLatch latch = acknowledged.remove(target);
        if (latch != null) {
            latch.countDown();
        }
    }

    /** 等待执行器在放量边界确认暂停（超时只是不再等待，不改变结论）。 */
    public boolean awaitAcknowledged(String target, long timeoutMillis) {
        CountDownLatch latch = acknowledged.get(target);
        if (latch == null) {
            return false;
        }
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
