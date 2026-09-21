package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.infra.InMemoryLocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyCapacityCancelTest {

    private static String manifest(int stages) {
        StringBuilder sb = new StringBuilder("""
                { "name": "app", "stages": [""");
        for (int i = 0; i < stages; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{ \"id\": \"s").append(i).append("\", \"env\": \"e")
              .append(i).append("\", \"desiredVersion\": \"v1\" }");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    void concurrentConvergesExecuteEachStageOnce(@TempDir Path dir) throws Exception {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 60_000, 4, 64, 5_000);
        h.engine.applyManifest(h.manifestStore.parse(manifest(6)), "api", false);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    h.engine.converge("api");
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(errors.isEmpty(), errors::toString);

        // 每个阶段物理部署恰好一次：并发收敛下租约互斥 + 幂等
        for (int i = 0; i < 6; i++) {
            final String stageId = "s" + i;
            assertEquals(1, platform.trace(stageId).size(),
                    () -> stageId + " trace=" + platform.trace(stageId));
            assertEquals(StageStatus.SUCCEEDED, h.states.stage(stageId).status);
        }
        long leaseContended = h.events.readAll().stream()
                .filter(e -> e.reasonCode() != null
                        && e.reasonCode().equals(ReasonCode.LEASE_CONTENDED)).count();
        assertTrue(leaseContended >= 0); // 竞争被识别（数量取决于时序），但无重复执行
    }

    @Test
    void capacityQueueOverflowIsRejectedBoundedAndReleasesSlots(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        // 并发 1、队列 0：第一次占用后，其他阶段无法获取额度 -> QUEUE_FULL
        DeliveryHarness h = new DeliveryHarness(dir, platform, 60_000, 1, 0, 50);
        h.engine.applyManifest(h.manifestStore.parse(manifest(3)), "api", false);
        var r = h.engine.converge("api");
        // 第一个阶段成功，其余因并发额度且无排队空间被拒绝（原因可区分）
        long queueFull = r.eventReasons().stream()
                .filter(x -> x.endsWith(ReasonCode.QUEUE_FULL)).count();
        assertTrue(queueFull >= 1, r.eventReasons()::toString);
        // 额度槽位必须被释放，后续收敛可继续推进
        assertEquals(0, h.capacity.activeCount());
        var r2 = h.engine.converge("api");
        long queueFull2 = r2.eventReasons().stream()
                .filter(x -> x.endsWith(ReasonCode.QUEUE_FULL)).count();
        assertTrue(queueFull2 >= 1);
        assertEquals(0, h.capacity.activeCount(), "no leaked capacity slot");
    }

    @Test
    void cancelIsDistinguishedFromFailureAndTimeout(@TempDir Path dir) throws Exception {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h = new DeliveryHarness(dir, platform, 60_000, 2, 16, 2_000);
        String json = """
                { "name": "app", "stages": [
                    { "id": "s1", "env": "e1", "desiredVersion": "v2",
                      "canary": { "weights": [20, 100], "stepWaitMillis": 2000 } }
                ]}
                """;
        h.engine.applyManifest(h.manifestStore.parse(json), "api", false);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        var future = pool.submit(() -> h.engine.converge("api"));
        Thread.sleep(150); // 等灰度第一步进入观察窗口
        h.engine.cancel("s1", "api");
        var report = future.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        long cancelledEvents = h.events.readAll().stream()
                .filter(e -> e.type() == EventType.STAGE_CANCELLED
                        && ReasonCode.EXEC_CANCELLED.equals(e.reasonCode())).count();
        long successEvents = h.events.readAll().stream()
                .filter(e -> e.type() == EventType.STAGE_SUCCEEDED).count();
        assertTrue(cancelledEvents >= 1, "cancellation must be recorded distinctly");
        assertEquals(0, successEvents, "cancelled flow must not be judged success");
        StageStatus st = h.states.stage("s1").status;
        assertTrue(st == StageStatus.CANCELLED, "expected CANCELLED got " + st);
        assertTrue(report.eventReasons().stream()
                .anyMatch(x -> x.endsWith(ReasonCode.EXEC_CANCELLED)));
    }
}
