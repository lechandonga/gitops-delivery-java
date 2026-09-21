package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发安全：同一目标被多个收敛循环同时触发时，
 * 只有一个真正建立流程执行，其余得到可区分的“已合并/租约占用”结论；
 * BUILD 阶段在模拟基础设施层只执行一次。
 */
class ConcurrencyTest {

    @Test
    void concurrentTriggersExecuteStageOnce(@TempDir Path dir) throws Exception {
        DeliverySystemHarness h = new DeliverySystemHarness(dir);
        h.service.applyManifest(TestSupport.single("svc-x", "v1"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<ReconcileOutcome> outcomes = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ReconcileOutcome o = h.service.reconcileTarget(
                            "svc-x", "alice", Set.of("dev"), false, "concurrent");
                    synchronized (outcomes) {
                        outcomes.add(o);
                    }
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发触发不应抛出异常: " + errors);

        long succeeded = outcomes.stream().filter(o -> o.getFlowStatus() == FlowStatus.SUCCEEDED).count();
        long merged = outcomes.stream()
                .filter(o -> o.getReason() == ReasonCode.DUPLICATE_TRIGGER_COALESCED
                        || o.getReason() == ReasonCode.LEASE_HELD_BY_OTHER).count();
        assertEquals(1, succeeded, "恰好一个触发真正完成发布");
        assertEquals(threads - 1, merged, "其余触发必须被合并或以租约原因拒绝");

        assertEquals(1, h.infra.executionCount("svc-x", "BUILD"), "BUILD 阶段只能执行一次");
        assertEquals(1, h.infra.executionCount("svc-x", "PROMOTE"), "PROMOTE 阶段只能执行一次");
    }
}
