package com.github.lechandonga.gitopsdeliveryjava.store;

import com.github.lechandonga.gitopsdeliveryjava.event.DeliveryEvent;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;
import com.github.lechandonga.gitopsdeliveryjava.model.EnvironmentState;
import com.github.lechandonga.gitopsdeliveryjava.model.StageState;
import com.github.lechandonga.gitopsdeliveryjava.model.StageStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 阶段/环境实际状态投影：完全由事件流重放构建。
 * 这是“实际状态”的权威来源（重启后重建，不丢失、不重复推进）。
 */
public class StateRepository {

    private final Map<String, StageState> stages = new LinkedHashMap<>();
    private final java.util.Set<String> approvedEnvs = new java.util.LinkedHashSet<>();
    private DeliveryManifest manifest;

    public StateRepository(java.nio.file.Path dataDir) {
        // dataDir 预留给快照；当前以事件流为唯一真相来源
    }

    /** 应用清单时同步期望态结构（新增阶段进入 PENDING，幂等）。 */
    public synchronized void bindManifest(DeliveryManifest manifest) {
        this.manifest = manifest;
        manifest.stages().forEach(ms -> stages.computeIfAbsent(ms.id(), id -> {
            StageState s = new StageState();
            s.stageId = id;
            s.env = ms.env();
            s.desiredVersion = ms.desiredVersion();
            s.status = StageStatus.PENDING;
            return s;
        }));
        // 事件重放可能早于/晚于清单绑定：对已存在投影补齐 env 等期望态元数据
        manifest.stages().forEach(ms -> {
            StageState s = stages.get(ms.id());
            if (s != null) {
                if (s.env == null) {
                    s.env = ms.env();
                }
                if (s.desiredVersion == null) {
                    s.desiredVersion = ms.desiredVersion();
                }
            }
        });
    }

    public synchronized DeliveryManifest manifest() {
        return manifest;
    }

    /** 从事件流重放构建投影；幂等，可在恢复时安全重放。 */
    public synchronized void replay(List<DeliveryEvent> events) {
        stages.clear();
        approvedEnvs.clear();
        for (DeliveryEvent e : events) {
            apply(e);
        }
    }

    /** 应用单个事件（运行期 append 后同步投影）。 */
    public synchronized void apply(DeliveryEvent e) {
        switch (e.type()) {
            case MANIFEST_APPLIED -> { /* 期望结构由 bindManifest 处理 */ }
            case STAGE_STARTED -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.RUNNING;
                s.desiredVersion = e.version();
                s.attempt = parseInt(e.detail().get("attempt"), s.attempt + 1);
                s.canaryWeight = 0;
                touch(s, e);
            }
            case STAGE_CANARY_STEP -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.CANARY_RUNNING;
                s.canaryWeight = parseInt(e.detail().get("weight"), s.canaryWeight);
                touch(s, e);
            }
            case STAGE_SUCCEEDED -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.SUCCEEDED;
                s.canaryWeight = 100;
                if (e.version() != null) {
                    s.appliedVersion = e.version();
                    if (!"true".equals(e.detail().get("rollbackTarget"))) {
                        s.lastStableVersion = e.version();
                    }
                }
                s.leaseOwner = null;
                s.leaseExpiresAt = 0L;
                touch(s, e);
            }
            case STAGE_FAILED -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.FAILED;
                touch(s, e);
            }
            case STAGE_TIMED_OUT -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.TIMED_OUT;
                touch(s, e);
            }
            case STAGE_CANCELLED -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.CANCELLED;
                s.leaseOwner = null;
                s.leaseExpiresAt = 0L;
                touch(s, e);
            }
            case ROLLBACK_STARTED -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.RUNNING;
                touch(s, e);
            }
            case ROLLBACK_SUCCEEDED -> {
                StageState s = stageOrNew(e.target(), e.version());
                s.status = StageStatus.ROLLED_BACK;
                s.appliedVersion = e.version();
                s.canaryWeight = 100;
                s.leaseOwner = null;
                s.leaseExpiresAt = 0L;
                touch(s, e);
            }
            case LEASE_TAKEN -> {
                StageState s = stageOrNew(e.target(), null);
                s.leaseOwner = e.detail().get("owner");
                s.leaseExpiresAt = parseLong(e.detail().get("expiresAt"), 0L);
            }
            case LEASE_TAKEOVER -> {
                StageState s = stageOrNew(e.target(), null);
                s.leaseOwner = e.detail().get("owner");
                s.leaseExpiresAt = parseLong(e.detail().get("expiresAt"), 0L);
            }
            case GATE_REJECTED, QUEUE_REJECTED, CONVERGE_SKIPPED_IDEMPOTENT,
                 APPROVAL_GRANTED, RECOVERY -> {
                // 不改变版本/阶段推进状态；审批在环境投影记录
                if (e.type() == EventType.APPROVAL_GRANTED) {
                    approvedEnvs.add(e.target());
                }
            }
        }
    }

    public synchronized StageState stage(String stageId) {
        return stages.get(stageId);
    }

    public synchronized Map<String, StageState> stages() {
        return new LinkedHashMap<>(stages);
    }

    public synchronized List<StageState> stagesOfEnv(String env) {
        List<StageState> list = new ArrayList<>();
        for (StageState s : stages.values()) {
            if (env.equals(s.env)) {
                list.add(s);
            }
        }
        return list;
    }

    /**
     * 环境实际版本：取该环境已成功阶段中最大更新时间对应的 appliedVersion；
     * 无已发布版本时为 null（拒绝不得改变已发布版本，由事件流天然保证）。
     */
    public synchronized EnvironmentState env(String env) {
        EnvironmentState es = envOrNew(env);
        Optional<StageState> latest = stages.values().stream()
                .filter(s -> env.equals(s.env))
                .filter(s -> s.appliedVersion != null)
                .max(Comparator.comparingLong(s -> s.updatedAt));
        latest.ifPresent(s -> es.currentVersion = s.appliedVersion);
        Optional<StageState> stable = stages.values().stream()
                .filter(s -> env.equals(s.env) && s.lastStableVersion != null)
                .max(Comparator.comparingLong(s -> s.updatedAt));
        stable.ifPresent(s -> es.lastStableVersion = s.lastStableVersion);
        if (manifest != null) {
            es.protectedEnv = manifest.protectedEnv();
        }
        es.approved = approvedEnvs.contains(env);
        return es;
    }

    public synchronized boolean envApproved(String env) {
        return approvedEnvs.contains(env);
    }

    private EnvironmentState envOrNew(String env) {
        EnvironmentState es = new EnvironmentState();
        es.env = env;
        return es;
    }

    private StageState stageOrNew(String stageId, String version) {
        StageState s = stages.get(stageId);
        if (s == null) {
            s = new StageState();
            s.stageId = stageId;
            s.desiredVersion = version;
            stages.put(stageId, s);
        }
        return s;
    }

    private void touch(StageState s, DeliveryEvent e) {
        s.updatedAt = e.timestamp();
        s.reasonCode = e.reasonCode();
        if (e.detail() != null && e.detail().get("message") != null) {
            s.message = e.detail().get("message");
        }
    }

    private static int parseInt(String v, int dflt) {
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }

    private static long parseLong(String v, long dflt) {
        if (v == null) {
            return dflt;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }

    public void flush() {
        // 事件流即真相，无需额外快照
    }
}
