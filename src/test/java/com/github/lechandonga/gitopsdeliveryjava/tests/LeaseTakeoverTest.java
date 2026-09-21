package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约：未过期不可抢占（可区分拒绝）；过期后可安全接管且 token 单调增大；
 * 旧持有者用旧 token 写入被 fencing 拒绝，杜绝双写/状态跳变。
 */
class LeaseTakeoverTest {

    @Test
    void expiredLeaseCanBeTakenOverAndOldTokenRejected(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);

        long tokenA = h.leases.acquire("db", "holder-A", h.props.getLeaseTtlMillis());
        // 未过期：B 抢占被明确拒绝，且不死锁
        DeliveryRejectedException ex = assertThrows(DeliveryRejectedException.class,
                () -> h.leases.takeOverExpired("db", "holder-B", h.props.getLeaseTtlMillis()));
        assertEquals(ReasonCode.LEASE_HELD_BY_OTHER, ex.getReasonCode());

        // 过期：B 接管成功，新 token 严格更大
        h.leases.forceExpire("db");
        long tokenB = h.leases.takeOverExpired("db", "holder-B", h.props.getLeaseTtlMillis());
        assertTrue(tokenB > tokenA, "接管后 fencing token 必须增大");

        // 旧持有者用旧 token 写状态必须被拒绝
        ActualTargetState state = new ActualTargetState();
        state.setName("db");
        state.setStableVersion("rogue-write");
        DeliveryRejectedException fence = assertThrows(DeliveryRejectedException.class,
                () -> h.store.saveActual(state, tokenA));
        assertEquals(ReasonCode.FENCING_TOKEN_STALE, fence.getReasonCode());

        // 新持有者写入成功
        state.setStableVersion("legit-write");
        h.store.saveActual(state, tokenB);
        assertEquals("legit-write", h.store.getOrCreateActual("db").getStableVersion());
    }

    @Test
    void reacquireSameHolderIsIdempotent(@TempDir Path dir) {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        long t1 = h.leases.acquire("cache", "same", h.props.getLeaseTtlMillis());
        long t2 = h.leases.acquire("cache", "same", h.props.getLeaseTtlMillis());
        assertEquals(t1, t2, "同一持有者重复获取必须返回同一 token（幂等）");
    }
}
