package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.Stage;
import com.github.lechandonga.gitopsdeliveryjava.model.VersionHistoryEntry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每目标“曾经稳定发布过的版本”历史。
 * <p>
 * 数据来源（保证老数据可读、历史不丢）：
 * <ol>
 *   <li>首次启动且无 history 目录时，从旧事件 WAL + 旧流程快照 + 旧实际状态快照
 *       全量重建（宽容读取：缺字段取默认、未知字段忽略）；</li>
 *   <li>运行期每次 PROMOTE 成功登记；回退到历史版本不产生“新发布版本”，
 *       但回退事件本身仍在 WAL 中可追溯。</li>
 * </ol>
 */
@Component
public class VersionHistory {

    private static final String UNKNOWN_ACTOR = "unknown";

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeliveryProperties props;
    private final com.github.lechandonga.gitopsdeliveryjava.store.StateStore store;
    private Path historyDir;
    private final Map<String, List<VersionHistoryEntry>> cache = new ConcurrentHashMap<>();

    public VersionHistory(DeliveryProperties props,
                          @org.springframework.context.annotation.Lazy
                          com.github.lechandonga.gitopsdeliveryjava.store.StateStore store) {
        this.props = props;
        this.store = store;
    }

    @PostConstruct
    public synchronized void init() {
        this.historyDir = Path.of(props.getDataDir()).resolve("history");
        try {
            Files.createDirectories(historyDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 从旧事件与旧快照重建历史。幂等：已有历史文件的目标不会被覆盖丢失。
     * 必须在 StateStore.init 之后调用（RecoveryService/DeliveryService 启动期均可）。
     */
    public synchronized void rebuildFromLegacyData() {
        Map<String, LinkedHashMap<String, VersionHistoryEntry>> merged = new LinkedHashMap<>();
        // 1) 已持久化的历史（若有）先载入
        for (ActualTargetState actual : store.allActual()) {
            loadPersisted(actual.getName()).ifPresent(entries ->
                    mergeInto(merged, actual.getName(), entries));
        }
        // 2) 旧事件 WAL：PROMOTE 阶段成功（toState=STABLE 且有目标版本）或成功发布事件
        for (EventRecord event : store.events(null)) {
            String target = event.getTarget();
            String version = stableVersionOf(event);
            if (target == null || version == null) {
                continue;
            }
            mergeInto(merged, target, List.of(new VersionHistoryEntry(
                    version, event.getTimestamp(),
                    event.getActor() == null ? UNKNOWN_ACTOR : event.getActor(),
                    event.getFlowId())));
        }
        // 3) 旧快照兜底：当前稳定版本与 previousStableVersion
        for (ActualTargetState actual : store.allActual()) {
            long at = Math.max(actual.getUpdatedAt(), 1L);
            if (actual.getStableVersion() != null) {
                mergeInto(merged, actual.getName(), List.of(
                        new VersionHistoryEntry(actual.getStableVersion(), at, UNKNOWN_ACTOR, null)));
            }
            if (actual.getPreviousStableVersion() != null) {
                mergeInto(merged, actual.getName(), List.of(
                        new VersionHistoryEntry(actual.getPreviousStableVersion(),
                                Math.max(at - 1, 1L), UNKNOWN_ACTOR, null)));
            }
        }
        merged.forEach((target, byVersion) -> {
            List<VersionHistoryEntry> entries = new ArrayList<>(byVersion.values());
            entries.sort(java.util.Comparator.comparingLong(VersionHistoryEntry::firstStableAt));
            persist(target, entries);
        });
    }

    private String stableVersionOf(EventRecord event) {
        if (event.getStage() == Stage.PROMOTE_STABLE
                && "STABLE".equals(event.getToState())
                && event.getInputVersion() != null) {
            return event.getInputVersion();
        }
        // 旧格式事件可能没有 stage：成功发布记录以 targetVersion / inputVersion 表示
        if (event.getTargetVersion() != null && "STABLE".equals(event.getToState())) {
            return event.getTargetVersion();
        }
        return null;
    }

    private void mergeInto(Map<String, LinkedHashMap<String, VersionHistoryEntry>> merged,
                           String target, List<VersionHistoryEntry> entries) {
        LinkedHashMap<String, VersionHistoryEntry> byVersion =
                merged.computeIfAbsent(target, k -> new LinkedHashMap<>());
        for (VersionHistoryEntry entry : entries) {
            byVersion.merge(entry.version(), entry,
                    (a, b) -> a.firstStableAt() <= b.firstStableAt() ? a : b);
        }
    }

    /** 登记一次稳定发布。重复登记（同版本）保留最早记录，保证幂等。 */
    public synchronized void recordStable(String target, String version, String actor,
                                          String flowId, long at) {
        List<VersionHistoryEntry> entries = new ArrayList<>(loadPersisted(target).orElse(List.of()));
        boolean exists = entries.stream().anyMatch(e -> e.version().equals(version));
        if (!exists) {
            entries.add(new VersionHistoryEntry(version, at, actor, flowId));
            entries.sort(java.util.Comparator.comparingLong(VersionHistoryEntry::firstStableAt));
            persist(target, entries);
        }
        cache.put(target, entries);
    }

    /** 版本是否曾经在该目标上稳定发布过。 */
    public boolean wasStable(String target, String version) {
        if (version == null) {
            return false;
        }
        return history(target).stream().anyMatch(e -> e.version().equals(version));
    }

    public synchronized List<VersionHistoryEntry> history(String target) {
        return loadPersisted(target).orElseGet(() -> {
            // 无历史文件：可能是更老的数据，尝试从事件/快照即时重建
            rebuildFromLegacyData();
            return loadPersisted(target).orElse(List.of());
        });
    }

    /** 仅供恢复/测试：基于流程快照补充登记（旧数据里 PROMOTE 完成但事件缺失的极端情况）。 */
    public synchronized void recordFromFlowIfStable(FlowRecord flow, ActualTargetState actual) {
        if (actual.getStableVersion() != null) {
            recordStable(flow.getTarget(), actual.getStableVersion(),
                    flow.getActor() == null ? UNKNOWN_ACTOR : flow.getActor(),
                    flow.getFlowId(), Math.max(flow.getCreatedAt(), 1L));
        }
    }

    private Optional<List<VersionHistoryEntry>> loadPersisted(String target) {
        List<VersionHistoryEntry> cached = cache.get(target);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = historyDir.resolve(target + ".json");
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        try {
            List<VersionHistoryEntry> entries = mapper.readValue(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<VersionHistoryEntry>>() {
                    });
            cache.put(target, entries);
            return Optional.of(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void persist(String target, List<VersionHistoryEntry> entries) {
        Path file = historyDir.resolve(target + ".json");
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cache.put(target, entries);
    }
}
