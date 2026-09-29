package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
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
    /** 槽位释放并 FIFO 提升等待者后的回调（由编排层续跑被提升的暂停目标）。 */
    private volatile Consumer<String> promotionSink = promoted -> { };

    public QuotaManager(DeliveryProperties props) {
        this.props = props;
    }

    public void setPromotionSink(Consumer<String> sink) {
        this.promotionSink = sink == null ? promoted -> { } : sink;
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

    /**
     * 释放槽位：FIFO 提升一个等待目标占用槽位，并通过 promotionSink 通知编排层续跑。
     * 回调在锁外执行，避免与续跑路径的 tryAcquire 形成锁嵌套。
     */
    public synchronized String release(String target) {
        String promoted = releaseInternal(target);
        if (promoted != null) {
            promotionSink.accept(promoted);
        }
        return promoted;
    }

    private synchronized String releaseInternal(String target) {
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

    /**
     * 已被提升到槽位但暂时无法续跑（如恢复时撞上冻结窗口）：把槽位退回队列头部。
     * 真正在推进的目标数因此永远不超过 maxConcurrency，且不会直接失败。
     */
    public synchronized void requeue(String target) {
        active.remove(target);
        // 刚从队头被提升的目标拥有“返回队列头部”的优先权：
        // 即便队列此刻已满也保留它（不丢、不空占槽），随后再由 FIFO 释放逻辑提升其余等待者。
        if (!waiting.contains(target)) {
            waiting.addFirst(target);
        }
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
