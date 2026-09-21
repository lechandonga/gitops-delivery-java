package com.github.lechandonga.gitopsdeliveryjava.api;

import com.github.lechandonga.gitopsdeliveryjava.engine.ConvergenceReport;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReconciliationEngine;
import com.github.lechandonga.gitopsdeliveryjava.event.DeliveryEvent;
import com.github.lechandonga.gitopsdeliveryjava.event.EventLog;
import com.github.lechandonga.gitopsdeliveryjava.infra.LocalPlatform;
import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;
import com.github.lechandonga.gitopsdeliveryjava.model.EnvironmentState;
import com.github.lechandonga.gitopsdeliveryjava.model.StageState;
import com.github.lechandonga.gitopsdeliveryjava.store.CapacityView;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import com.github.lechandonga.gitopsdeliveryjava.store.ManifestStore;
import com.github.lechandonga.gitopsdeliveryjava.store.StateRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 本地交付触发入口：所有入口最终都走 ReconciliationEngine，结论一致。 */
@RestController
public class DeliveryController {

    private final ReconciliationEngine engine;
    private final ManifestStore manifestStore;
    private final StateRepository states;
    private final EventLog events;
    private final LeaseStore leases;
    private final LocalPlatform platform;
    private final CapacityView capacity;

    public DeliveryController(ReconciliationEngine engine, ManifestStore manifestStore,
                              StateRepository states, EventLog events, LeaseStore leases,
                              LocalPlatform platform, CapacityView capacity) {
        this.engine = engine;
        this.manifestStore = manifestStore;
        this.states = states;
        this.events = events;
        this.leases = leases;
        this.platform = platform;
        this.capacity = capacity;
    }

    /** 提交期望清单（原始 JSON，严格拒绝未知字段/不支持选项）。 */
    @PostMapping("/api/manifest")
    public ApiDtos.ConvergeResponse applyManifest(@RequestBody String rawJson,
                                                  @RequestParam(defaultValue = "api") String trigger,
                                                  @RequestParam(defaultValue = "false") boolean approved) {
        DeliveryManifest manifest = manifestStore.parse(rawJson);
        engine.applyManifest(manifest, trigger, approved);
        // 应用清单后立即收敛一次，让任意入口的“提交即得到结论”
        return toResponse(engine.converge(trigger));
    }

    /** 显式触发收敛（可模拟 scheduler/event 等入口，门禁会校验 trigger）。 */
    @PostMapping("/api/converge")
    public ApiDtos.ConvergeResponse converge(@RequestParam(defaultValue = "api") String trigger) {
        return toResponse(engine.converge(trigger));
    }

    /** 授予受保护环境推进审批。 */
    @PostMapping("/api/approve")
    public ApiDtos.RejectedResponse approve(@RequestParam String env,
                                            @RequestParam(defaultValue = "api") String trigger) {
        engine.grantApproval(env, trigger);
        return new ApiDtos.RejectedResponse("APPROVED", "approval granted for env " + env);
    }

    /** 取消阶段执行。 */
    @PostMapping("/api/stages/{stageId}/cancel")
    public ApiDtos.RejectedResponse cancel(@PathVariable String stageId,
                                           @RequestParam(defaultValue = "api") String trigger) {
        engine.cancel(stageId, trigger);
        return new ApiDtos.RejectedResponse("CANCELLED", "cancel requested for " + stageId);
    }

    @GetMapping("/api/state")
    public ApiDtos.StateView state() {
        List<ApiDtos.StageView> stageViews = new ArrayList<>();
        Set<String> envNames = new LinkedHashSet<>();
        for (StageState s : states.stages().values()) {
            envNames.add(s.env);
            stageViews.add(new ApiDtos.StageView(s.stageId, s.env, s.desiredVersion,
                    s.status.name(), s.appliedVersion, s.lastStableVersion, s.attempt,
                    s.canaryWeight, s.leaseOwner, s.leaseExpiresAt, s.reasonCode, s.message));
        }
        List<ApiDtos.EnvView> envViews = new ArrayList<>();
        for (String env : envNames) {
            EnvironmentState e = states.env(env);
            envViews.add(new ApiDtos.EnvView(e.env, e.protectedEnv, e.approved,
                    e.currentVersion, e.lastStableVersion));
        }
        return new ApiDtos.StateView(stageViews, envViews, leases.active().size(),
                capacity.activeCount(), capacity.queueLength());
    }

    /** 查询事件流（每次迁移的输入与判定依据）。 */
    @GetMapping("/api/events")
    public List<DeliveryEvent> events() {
        return events.readAll();
    }

    private ApiDtos.ConvergeResponse toResponse(ConvergenceReport r) {
        return new ApiDtos.ConvergeResponse(r.converged(), r.driftDetected(),
                r.outcomes(), r.eventReasons());
    }
}
