package com.github.lechandonga.gitopsdeliveryjava.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标级租约存储（本地文件 + 内存）。
 * 语义：
 * - 同一目标同一时刻只有一个 owner；acquire 成功返回单调递增 fenceToken；
 * - owner 持有期间他人 acquire 被拒，除非租约过期 -> 安全接管（token +1，旧 token 立刻失效）；
 * - renew/release 校验 owner 与 fenceToken，杜绝过期持有者的双写与状态跳变；
 * - 每次变更落盘，重启后按 expiresAt 判定，未过期租约继续受尊重。
 */
public class LeaseStore {

    private final Path file;
    private final long ttlMillis;
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private int globalFence;

    public LeaseStore(Path dataDir, long ttlMillis) {
        try {
            Files.createDirectories(dataDir);
            this.file = dataDir.resolve("leases.json");
            this.ttlMillis = ttlMillis;
            load();
        } catch (IOException e) {
            throw new UncheckedIOException("init lease store failed", e);
        }
    }

    /** 尝试获取；成功返回租约，冲突返回 null。过期租约允许接管。 */
    public synchronized Lease acquire(String target, String owner) {
        Lease existing = leases.get(target);
        long now = System.currentTimeMillis();
        if (existing != null && existing.expiresAt > now
                && !existing.owner.equals(owner)) {
            return null; // 仍被他人有效持有
        }
        boolean takeover = existing != null && !existing.owner.equals(owner);
        Lease lease = new Lease();
        lease.target = target;
        lease.owner = owner;
        lease.acquiredAt = now;
        lease.expiresAt = now + ttlMillis;
        globalFence += 1;
        lease.fenceToken = globalFence;
        leases.put(target, lease);
        persist();
        return lease;
    }

    /** 接管过期租约；若租约仍有效或归属不符返回 null。 */
    public synchronized Lease takeover(String target, String newOwner) {
        Lease existing = leases.get(target);
        long now = System.currentTimeMillis();
        if (existing == null || existing.expiresAt > now) {
            return null;
        }
        Lease lease = new Lease();
        lease.target = target;
        lease.owner = newOwner;
        lease.acquiredAt = now;
        lease.expiresAt = now + ttlMillis;
        globalFence += 1;
        lease.fenceToken = globalFence;
        leases.put(target, lease);
        persist();
        return lease;
    }

    public synchronized boolean renew(String target, String owner, int fenceToken) {
        Lease lease = leases.get(target);
        if (lease == null || !lease.owner.equals(owner)
                || lease.fenceToken != fenceToken) {
            return false;
        }
        lease.expiresAt = System.currentTimeMillis() + ttlMillis;
        persist();
        return true;
    }

    /** 仅持有者（owner+token 匹配）可释放，防止接管者之外的误释放。 */
    public synchronized void release(String target, String owner, int fenceToken) {
        Lease lease = leases.get(target);
        if (lease != null && lease.owner.equals(owner)
                && lease.fenceToken == fenceToken) {
            leases.remove(target);
            persist();
        }
    }

    /** 执行动作前校验：持有者、token、未过期三者缺一不可。 */
    public synchronized boolean isValid(String target, String owner, int fenceToken) {
        Lease lease = leases.get(target);
        return lease != null
                && lease.owner.equals(owner)
                && lease.fenceToken == fenceToken
                && lease.expiresAt > System.currentTimeMillis();
    }

    public synchronized Map<String, Lease> active() {
        long now = System.currentTimeMillis();
        Map<String, Lease> result = new LinkedHashMap<>();
        leases.forEach((k, v) -> {
            if (v.expiresAt > now) {
                result.put(k, v);
            }
        });
        return result;
    }

    /** 启动恢复：返回已过期但未释放的租约目标（崩溃残留，等待接管）。 */
    public synchronized Map<String, Lease> expiredOnStartup() {
        long now = System.currentTimeMillis();
        Map<String, Lease> result = new LinkedHashMap<>();
        leases.forEach((k, v) -> {
            if (v.expiresAt <= now) {
                result.put(k, v);
            }
        });
        return result;
    }

    private void load() {
        try {
            if (Files.notExists(file)) {
                return;
            }
            Snapshot snapshot = Json.MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    Snapshot.class);
            globalFence = snapshot.globalFence;
            if (snapshot.leases != null) {
                snapshot.leases.forEach(leases::put);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("load leases failed", e);
        }
    }

    private void persist() {
        try {
            Snapshot snapshot = new Snapshot();
            snapshot.globalFence = globalFence;
            snapshot.leases = new LinkedHashMap<>(leases);
            Files.writeString(file, Json.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(snapshot), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("persist leases failed", e);
        }
    }

    public static class Snapshot {
        public int globalFence;
        public Map<String, Lease> leases;
    }
}
