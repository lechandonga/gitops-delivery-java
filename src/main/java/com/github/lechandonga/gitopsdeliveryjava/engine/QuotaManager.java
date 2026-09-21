package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 并发与排队上限控制。
 * <ul>
 *   <li>最多 maxConcurrency 个目标同时推进，超出进入有界 FIFO 队列；</li>
 *   <li>队列长度达到 maxQueue 时明确拒绝（QUOTA_REJECTED），队列不会无界增长；</li>
 *   <li>同一目标重复排队/占槽按幂等处理（QUEUED 合并），不重复占用；</li>
 *   <li>槽位释放后按 FIFO 提升一个排队目标。</li>
 * </ul>
 * 纯内存组件：崩溃未释放的槽位由租约 TTL + 恢复流程兜底（进程重启即重建）。
 */
@Component
public class QuotaManager {

    public enum Admission { GRANTED, QUEUED, REJECTED }

    private final DeliveryProperties props;
    private final Set<String> active = new HashSet<>();
    private final Deque<String> waiting = new ArrayDeque<>();

    public QuotaManager(DeliveryProperties props) {
        this.props = props;
    }

    public synchronized Admission tryAcquire(String target) {
        if (active.contains(target)) {
            return Admission.GRANTED; // 已持槽（重试/重复触发），幂等
        }
        if (waiting.contains(target)) {
            return Admission.QUEUED; // 已在队列，重复触发合并
        }
        if (active.size() < props.getMaxConcurrency()) {
            active.add(target);
            return Admission.GRANTED;
        }
        if (waiting.size() < props.getMaxQueue()) {
            waiting.add(target);
            return Admission.QUEUED;
        }
        return Admission.REJECTED;
    }

    /** 释放槽位并返回被提升（从队列进入执行）的目标，无则 null。 */
    public synchronized String release(String target) {
        active.remove(target);
        waiting.remove(target);
        if (active.size() < props.getMaxConcurrency() && !waiting.isEmpty()) {
            String promoted = waiting.poll();
            active.add(promoted);
            return promoted;
        }
        return null;
    }

    /** 仅取消排队，不释放槽位。 */
    public synchronized void dequeue(String target) {
        waiting.remove(target);
    }

    public synchronized boolean isQueued(String target) {
        return waiting.contains(target);
    }

    public synchronized int activeCount() {
        return active.size();
    }

    public synchronized int queueLength() {
        return waiting.size();
    }
}
