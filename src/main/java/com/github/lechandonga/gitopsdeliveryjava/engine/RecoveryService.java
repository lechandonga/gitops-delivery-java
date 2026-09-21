package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.event.DeliveryEvent;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;
import com.github.lechandonga.gitopsdeliveryjava.store.Lease;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import com.github.lechandonga.gitopsdeliveryjava.store.ManifestStore;
import com.github.lechandonga.gitopsdeliveryjava.store.StateRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 重启恢复：
 * 1) 重放全部事件重建实际状态投影（已完成阶段保持 SUCCEEDED/ROLLED_BACK，不重复执行）；
 * 2) 重新绑定清单期望态结构（缺失阶段补 PENDING）；
 * 3) 崩溃时残留且已过期的租约由恢复 owner 安全接管并记 RECOVERY 事件，
 *    未过期租约保持尊重，不产生双写。
 */
public class RecoveryService {

    public static final String RECOVERY_OWNER = "recovery-on-startup";

    private final StateRepository states;
    private final LeaseStore leases;
    private final com.github.lechandonga.gitopsdeliveryjava.event.EventLog events;
    private final ManifestStore manifestStore;

    public RecoveryService(StateRepository states, LeaseStore leases,
                           com.github.lechandonga.gitopsdeliveryjava.event.EventLog events,
                           ManifestStore manifestStore) {
        this.states = states;
        this.leases = leases;
        this.events = events;
        this.manifestStore = manifestStore;
    }

    /** 启动时调用；返回接管（恢复）的目标列表。 */
    public synchronized List<String> recoverOnStartup() {
        List<DeliveryEvent> all = events.readAll();
        states.replay(all);

        var manifest = manifestStore.load();
        if (manifest != null) {
            states.bindManifest(manifest);
        }

        List<String> takenOver = new ArrayList<>();
        Map<String, Lease> expired = leases.expiredOnStartup();
        for (String target : expired.keySet()) {
            Lease lease = leases.takeover(target, RECOVERY_OWNER);
            if (lease != null) {
                events.append(EventType.RECOVERY, target, null, "recovery",
                        ReasonCode.LEASE_TAKEOVER, Map.of(
                                "owner", RECOVERY_OWNER,
                                "fenceToken", String.valueOf(lease.fenceToken),
                                "message", "expired lease taken over after restart"));
                // 恢复阶段仅负责接管与记账：立即释放，避免阻塞后续正常收敛；
                // 残留的 RUNNING 阶段由后续收敛（已完成则幂等跳过）安全推进
                leases.release(target, RECOVERY_OWNER, lease.fenceToken);
                takenOver.add(target);
            }
        }
        return List.copyOf(takenOver);
    }
}
