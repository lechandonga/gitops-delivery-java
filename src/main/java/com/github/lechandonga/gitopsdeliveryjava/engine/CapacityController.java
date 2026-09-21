package com.github.lechandonga.gitopsdeliveryjava.engine;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并发与排队上限控制。
 * - maxConcurrency 个执行槽位（公平信号量，按 FIFO 排队）；
 * - 等待者不得超过 maxQueue：tryAcquire 在排队前先检查排队计数，
 *   超限直接失败（QUEUE_FULL 由上层转成拒绝事件），杜绝无界等待；
 * - 等待超时也算获取失败，且必须回退排队计数，不残留占用。
 */
public class CapacityController
        implements com.github.lechandonga.gitopsdeliveryjava.store.CapacityView {

    private final Semaphore slots;
    private final int maxQueue;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();

    public CapacityController(int maxConcurrency, int maxQueue) {
        if (maxConcurrency < 1 || maxQueue < 0) {
            throw new IllegalArgumentException("maxConcurrency>=1 and maxQueue>=0 required");
        }
        this.slots = new Semaphore(maxConcurrency, true);
        this.maxQueue = maxQueue;
    }

    /**
     * @return true 获得槽位；false 排队已满或超时（不残留任何占用）
     */
    public boolean tryAcquire(long timeoutMillis) throws InterruptedException {
        int queued = waiting.incrementAndGet();
        try {
            if (queued > maxQueue) {
                return false; // 有界排队：超出立即拒绝
            }
            boolean got = slots.tryAcquire(1, Math.max(1, timeoutMillis),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (got) {
                active.incrementAndGet();
            }
            return got;
        } finally {
            waiting.decrementAndGet();
        }
    }

    public void release() {
        slots.release();
        active.decrementAndGet();
    }

    public int activeCount() {
        return active.get();
    }

    public int queueLength() {
        return waiting.get();
    }

    public int availableSlots() {
        return slots.availablePermits();
    }
}
