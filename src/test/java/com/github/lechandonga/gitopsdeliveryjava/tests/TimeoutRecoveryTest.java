package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.event.DeliveryEvent;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.infra.DeployResult;
import com.github.lechandonga.gitopsdeliveryjava.infra.HealthStatus;
import com.github.lechandonga.gitopsdeliveryjava.infra.InMemoryLocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutRecoveryTest {

    /** 部署阻塞超过阶段超时预算的平台：用于验证超时被区分，且不被判成功。 */
    static final class SlowPlatform extends InMemoryLocalPlatform {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile long sleepMillis;

        @Override
        public DeployResult deploy(String target, String version, int weightPercent,
                                   int fenceToken) {
            entered.countDown();
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new DeployResult(false, null, "interrupted (timeout)");
            }
            return super.deploy(target, version, weightPercent, fenceToken);
        }
    }

    @Test
    void deployTimeoutIsDistinguishedAndNotSuccess(@TempDir Path dir) throws Exception {
        SlowPlatform platform = new SlowPlatform();
        platform.sleepMillis = 1_500;
        // 租约 TTL 设大，避免超时期间被接管干扰本断言
        DeliveryHarness h = new DeliveryHarness(dir, platform, 60_000, 1, 4, 2_000);
        String json = """
                { "name": "app", "stages": [
                    { "id": "s1", "env": "e1", "desiredVersion": "v2",
                      "executionTimeoutMillis": 200, "maxAttempts": 1 }
                ]}
                """;
        h.engine.applyManifest(h.manifestStore.parse(json), "api", false);
        var r = h.engine.converge("api");
        assertFalse(r.converged());
        StageStatus st = h.states.stage("s1").status;
        assertEquals(StageStatus.TIMED_OUT, st, "expected TIMED_OUT got " + st);
        long timeouts = h.events.readAll().stream()
                .filter(e -> e.type() == EventType.STAGE_TIMED_OUT
                        && ReasonCode.EXEC_TIMEOUT.equals(e.reasonCode())).count();
        long success = h.events.readAll().stream()
                .filter(e -> e.type() == EventType.STAGE_SUCCEEDED).count();
        assertEquals(1, timeouts);
        assertEquals(0, success, "timed-out flow must not be judged success");
    }

    @Test
    void restartReplaysEventsAndDoesNotReAdvanceCompletedStages(@TempDir Path dir) {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h1 = new DeliveryHarness(dir, platform, 10_000, 2, 16, 2_000);
        String json = """
                { "name": "app", "stages": [
                    { "id": "dev", "env": "dev", "desiredVersion": "v2" },
                    { "id": "prod", "env": "prod", "desiredVersion": "v2",
                      "dependsOn": ["dev"] }
                ]}
                """;
        h1.engine.applyManifest(h1.manifestStore.parse(json), "api", false);
        assertTrue(h1.engine.converge("api").converged());
        long eventsBefore = h1.events.lastSeq();
        int devTraceBefore = platform.trace("dev").size();
        int prodTraceBefore = platform.trace("prod").size();

        // 模拟进程异常退出后重启：全部存储从文件重建（物理平台仍在运行）
        DeliveryHarness h2 = h1.restart();
        List<String> takenOver = h2.recovery.recoverOnStartup();
        // 无过期租约：正常完成后租约已释放
        assertTrue(takenOver.isEmpty());

        // 状态从事件重放恢复：两阶段仍为 SUCCEEDED
        assertEquals(StageStatus.SUCCEEDED, h2.states.stage("dev").status);
        assertEquals(StageStatus.SUCCEEDED, h2.states.stage("prod").status);
        assertEquals("v2", h2.states.stage("prod").appliedVersion);

        // 重启后再收敛：不得重复推进已完成阶段，物理部署次数不变
        var r = h2.engine.converge("recovery");
        assertTrue(r.converged());
        assertEquals(devTraceBefore, platform.trace("dev").size(),
                "no re-deploy after restart: " + platform.trace("dev"));
        assertEquals(prodTraceBefore, platform.trace("prod").size());
        // 仅增加幂等跳过事件
        assertEquals(eventsBefore + 2, h2.events.lastSeq());

        // 回归：事件重放后 env 等期望态元数据不丢失，环境投影可正常计算
        assertEquals("dev", h2.states.stage("dev").env);
        assertEquals("prod", h2.states.stage("prod").env);
        assertEquals("v2", h2.states.env("prod").currentVersion);
    }

    @Test
    void expiredLeaseAfterCrashIsTakenOverSafely(@TempDir Path dir) throws Exception {
        InMemoryLocalPlatform platform = new InMemoryLocalPlatform();
        DeliveryHarness h1 = new DeliveryHarness(dir, platform, 80, 2, 16, 2_000);
        String json = """
                { "name": "app", "stages": [
                    { "id": "s1", "env": "e1", "desiredVersion": "v1" }
                ]}
                """;
        // 手动获取一个租约后不释放（模拟崩溃），等待过期
        h1.leases.acquire("s1", "crashed-owner");
        Thread.sleep(120);

        DeliveryHarness h2 = h1.restart();
        List<String> takenOver = h2.recovery.recoverOnStartup();
        assertTrue(takenOver.contains("s1"));
        DeliveryEvent recoveryEvent = h2.events.readAll().stream()
                .filter(e -> e.type() == EventType.RECOVERY
                        && ReasonCode.LEASE_TAKEOVER.equals(e.reasonCode()))
                .findFirst().orElseThrow();
        assertEquals("s1", recoveryEvent.target());

        // 接管后仍可正常收敛发布
        h2.engine.applyManifest(h2.manifestStore.parse(json), "recovery", false);
        var r = h2.engine.converge("recovery");
        assertTrue(r.converged(), r.eventReasons()::toString);
        assertEquals("v1", platform.currentVersion("s1"));
    }
}
