package com.github.lechandonga.gitopsdeliveryjava.web;

import com.github.lechandonga.gitopsdeliveryjava.compat.ManifestSerde;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReconcileOutcome;
import com.github.lechandonga.gitopsdeliveryjava.service.DeliveryService;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 触发入口。与周期收敛、手动调用共享 DeliveryService 的同一条收敛路径。
 * 角色通过 X-Actor-Roles 头（逗号分隔）传入；审批通过 approved=true 显式表达。
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final ManifestSerde serde;
    private final StateStore store;

    public DeliveryController(DeliveryService deliveryService, ManifestSerde serde, StateStore store) {
        this.deliveryService = deliveryService;
        this.serde = serde;
        this.store = store;
    }

    /** 应用期望状态清单（严格兼容校验，未知字段拒绝）。 */
    @PostMapping("/manifests")
    public Map<String, Object> applyManifest(@RequestBody String json) {
        Manifest manifest = serde.parse(json);
        deliveryService.applyManifest(manifest);
        return Map.of("applied", true, "targets", manifest.getTargets().size());
    }

    /** 触发单个目标收敛。 */
    @PostMapping("/targets/{name}/reconcile")
    public ReconcileOutcome reconcile(
            @PathVariable("name") String name,
            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor,
            @RequestHeader(value = "X-Actor-Roles", defaultValue = "") String rolesHeader,
            @RequestParam(value = "approved", defaultValue = "false") boolean approved) {
        Set<String> roles = parseRoles(rolesHeader);
        return deliveryService.reconcileTarget(name, actor, roles, approved, "http-api");
    }

    /** 按依赖顺序收敛全部目标。 */
    @PostMapping("/reconcile-all")
    public ReconcileOutcome reconcileAll(
            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor,
            @RequestHeader(value = "X-Actor-Roles", defaultValue = "") String rolesHeader,
            @RequestParam(value = "approved", defaultValue = "false") boolean approved) {
        return deliveryService.reconcileAll(actor, parseRoles(rolesHeader), approved);
    }

    @PostMapping("/targets/{name}/rollback")
    public ReconcileOutcome rollback(@PathVariable("name") String name,
                                     @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.rollback(name, actor);
    }

    /** 回退到指定的历史稳定版本（冻结窗口内也允许；版本必须曾在该目标稳定发布）。 */
    @PostMapping("/targets/{name}/rollback-to")
    public ReconcileOutcome rollbackToVersion(
            @PathVariable("name") String name,
            @RequestParam("version") String version,
            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.rollbackToVersion(name, version, actor);
    }

    /** 灰度人工暂停：停在当前放量步骤，立即释放并发槽。 */
    @PostMapping("/targets/{name}/pause")
    public ReconcileOutcome pause(@PathVariable("name") String name,
                                  @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.pause(name, actor);
    }

    /** 灰度恢复：从暂停步骤续跑；额度满时排队，不重放已放的量。 */
    @PostMapping("/targets/{name}/resume")
    public ReconcileOutcome resume(
            @PathVariable("name") String name,
            @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor,
            @RequestHeader(value = "X-Actor-Roles", defaultValue = "") String rolesHeader,
            @RequestParam(value = "approved", defaultValue = "false") boolean approved) {
        return deliveryService.resume(name, actor, parseRoles(rolesHeader), approved);
    }

    @PostMapping("/targets/{name}/cancel")
    public ReconcileOutcome cancel(@PathVariable("name") String name,
                                   @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.cancel(name, actor);
    }

    @GetMapping("/targets/{name}/events")
    public List<EventRecord> events(@PathVariable("name") String name) {
        return store.events(name);
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> view = new LinkedHashMap<>();
        store.loadManifest().ifPresent(m -> view.put("manifest", m));
        view.put("actual", store.allActual());
        view.put("flows", store.allFlows());
        return view;
    }

    private Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(header.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
