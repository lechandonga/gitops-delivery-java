package com.github.lechandonga.gitopsdeliveryjava.lease;

import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 每目标租约 + 单调递增 fencing token。
 * <p>
 * 规则：
 * <ul>
 *   <li>同一时刻一个目标最多被一个持有者占用（其余得到 {@code LEASE_HELD_BY_OTHER}）；</li>
 *   <li>每次成功获取/接管都返回严格更大的 fencing token，配合 StateStore 防双写；</li>
 *   <li>租约过期后，任何执行者都可安全接管，旧持有者后续写入会被 fencing 拒绝；</li>
 *   <li>重复获取（同持有者）返回原 token，保证重复触发幂等、同阶段不执行多次。</li>
 * </ul>
 */
@Component
public class LeaseManager {

    private record Lease(String holderId, long fencingToken, long acquiredAt, long expiresAt) {
        boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final AtomicLong tokenSequence;

    public LeaseManager() {
        this(0L);
    }

    /** 启动恢复时用持久化的 token 高水位初始化，保证跨重启 token 严格单调。 */
    public LeaseManager(long persistedHighWaterMark) {
        this.tokenSequence = new AtomicLong(Math.max(0L, persistedHighWaterMark));
    }

    /** 每次签发更大 token 时回调（由状态库持久化高水位）。 */
    private volatile LongConsumer highWaterMarkSink = t -> { };

    public void setHighWaterMarkSink(LongConsumer sink) {
        this.highWaterMarkSink = sink == null ? t -> { } : sink;
    }

    /** 启动时以持久化高水位校正序列（取较大者，绝不回退）。 */
    public void seedHighWaterMark(long highWaterMark) {
        tokenSequence.updateAndGet(current -> Math.max(current, highWaterMark));
    }

    /**
     * 获取目标租约。
     *
     * @return 当前持有者可使用的 fencing token（&gt;0）
     * @throws DeliveryRejectedException LEASE_HELD_BY_OTHER 被其他活跃持有者占用
     */
    public long acquire(String target, String holderId, long ttlMillis) {
        long now = System.currentTimeMillis();
        // compute 保证“检查-接管”原子，杜绝两个执行者同时拿到租约
        Lease result = leases.compute(target, (key, existing) -> {
            if (existing == null || existing.expired(now)) {
                long token = nextToken(existing);
                return new Lease(holderId, token, now, now + ttlMillis);
            }
            if (existing.holderId.equals(holderId)) {
                // 幂等重入：延续有效期，token 不变
                return new Lease(holderId, existing.fencingToken, existing.acquiredAt, now + ttlMillis);
            }
            return existing;
        });
        if (!result.holderId.equals(holderId)) {
            throw new DeliveryRejectedException(ReasonCode.LEASE_HELD_BY_OTHER,
                    "target=" + target + " heldBy=" + result.holderId);
        }
        return result.fencingToken;
    }

    /** 尝试接管一个已过期租约；若仍有效则抛出 LEASE_HELD_BY_OTHER。 */
    public long takeOverExpired(String target, String newHolderId, long ttlMillis) {
        long now = System.currentTimeMillis();
        Lease[] taken = new Lease[1];
        leases.compute(target, (key, existing) -> {
            if (existing != null && !existing.expired(now)) {
                taken[0] = existing;
                return existing;
            }
            long token = nextToken(existing);
            Lease next = new Lease(newHolderId, token, now, now + ttlMillis);
            taken[0] = next;
            return next;
        });
        if (!taken[0].holderId.equals(newHolderId)) {
            throw new DeliveryRejectedException(ReasonCode.LEASE_HELD_BY_OTHER,
                    "target=" + target + " heldBy=" + taken[0].holderId);
        }
        return taken[0].fencingToken;
    }

    public boolean renew(String target, String holderId, long fencingToken, long ttlMillis) {
        long now = System.currentTimeMillis();
        boolean[] ok = {false};
        leases.computeIfPresent(target, (key, existing) -> {
            if (!existing.holderId.equals(holderId) || existing.fencingToken != fencingToken) {
                // token 不匹配说明持有者已被接管：拒绝续租
                return existing;
            }
            if (existing.expired(now)) {
                return existing;
            }
            ok[0] = true;
            return new Lease(holderId, fencingToken, existing.acquiredAt, now + ttlMillis);
        });
        return ok[0];
    }

    public void release(String target, String holderId, long fencingToken) {
        leases.computeIfPresent(target, (key, existing) -> {
            if (existing.holderId.equals(holderId) && existing.fencingToken == fencingToken) {
                return null;
            }
            return existing;
        });
    }

    public boolean isHolder(String target, String holderId, long fencingToken) {
        Lease l = leases.get(target);
        if (l == null || l.expired(System.currentTimeMillis())) {
            return false;
        }
        return l.holderId.equals(holderId) && l.fencingToken == fencingToken;
    }

    /**
     * 返回目标当前活跃租约的 fencing token；不存在或已过期返回 null。
     * 供状态库做 fencing 校验，防止被接管的旧持有者双写。
     */
    public Long currentToken(String target) {
        Lease l = leases.get(target);
        if (l == null || l.expired(System.currentTimeMillis())) {
            return null;
        }
        return l.fencingToken();
    }

    /** 仅供恢复/测试：强制失效某目标租约（模拟崩溃后 TTL 到期）。 */
    public void forceExpire(String target) {
        leases.computeIfPresent(target, (k, v) ->
                new Lease(v.holderId(), v.fencingToken(), v.acquiredAt(), 0L));
    }

    private long nextToken(Lease previous) {
        // 单调递增：即便本 JVM 内重置，也保证比上一个 token 大
        long candidate = tokenSequence.incrementAndGet();
        if (previous != null && candidate <= previous.fencingToken()) {
            candidate = previous.fencingToken() + 1;
            tokenSequence.set(candidate);
        }
        highWaterMarkSink.accept(candidate);
        return candidate;
    }
}
