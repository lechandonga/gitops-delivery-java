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
    private final com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory versionHistory;

    public DeliveryController(DeliveryService deliveryService, ManifestSerde serde, StateStore store,
                              com.github.lechandonga.gitopsdeliveryjava.engine.VersionHistory versionHistory) {
        this.deliveryService = deliveryService;
        this.serde = serde;
        this.store = store;
        this.versionHistory = versionHistory;
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

    /** 回退到指定的历史稳定版本（该版本必须曾在本目标稳定发布过）。 */
    @PostMapping("/targets/{name}/rollback/{version}")
    public ReconcileOutcome rollbackTo(@PathVariable("name") String name,
                                       @PathVariable("version") String version,
                                       @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.rollbackTo(name, version, actor);
    }

    /** 暂停灰度中的流程（踩刹车）。 */
    @PostMapping("/targets/{name}/pause")
    public ReconcileOutcome pause(@PathVariable("name") String name,
                                  @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.pause(name, actor);
    }

    /** 恢复暂停中的流程（从暂停的灰度步继续，已放量步不重放）。 */
    @PostMapping("/targets/{name}/resume")
    public ReconcileOutcome resume(@PathVariable("name") String name,
                                   @RequestHeader(value = "X-Actor", defaultValue = "anonymous") String actor) {
        return deliveryService.resume(name, actor);
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
        java.util.Map<String, Object> history = new java.util.LinkedHashMap<>();
        store.allActual().forEach(a -> history.put(a.getName(),
                versionHistory.history(a.getName()).stream()
                        .map(com.github.lechandonga.gitopsdeliveryjava.model.VersionHistoryEntry::version)
                        .toList()));
        view.put("versionHistory", history);
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
