package com.github.lechandonga.gitopsdeliveryjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.lechandonga.gitopsdeliveryjava.config.DeliveryProperties;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.FlowStatus;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 本地持久化状态库。
 * <ul>
 *   <li>manifest.json：期望状态快照；</li>
 *   <li>actual/&lt;target&gt;.json：本地模拟实际状态快照；</li>
 *   <li>flows/&lt;flowId&gt;.json：流程快照（重启恢复依据）；</li>
 *   <li>events.log：只追加审计 WAL，每行一个 JSON 事件，重启后不丢失。</li>
 * </ul>
 * 所有可能被多个执行者竞争的写入都必须携带 fencing token；
 * token 过期（低于该目标已见过的最大 token）的写入被明确拒绝，杜绝双写与状态跳变。
 */
@Component
public class StateStore {

    private final ObjectMapper mapper = new ObjectMapper()
            // 快照与事件按“宽容读取、严格写入”演进：旧数据带未知字段时忽略，绝不读挂
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);
    /** 事件 WAL 专用 mapper：每个事件必须序列化为单行（日志按行回放）。 */
    private final ObjectMapper eventMapper = new ObjectMapper()
            // 宽容回放：旧版本事件带未知字段时忽略，保证历史可重建、读取不报错
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final DeliveryProperties props;
    private final com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager leaseManager;

    private Path dataDir;
    private Path actualDir;
    private Path flowsDir;
    private Path eventsFile;
    private final AtomicLong eventSeq = new AtomicLong(0);
    private final java.util.concurrent.ConcurrentHashMap<Path, Object> fileLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 事件 WAL 的专用锁：保证一次追加为完整一行，且读取不会观察到半行。 */
    private final Object eventLogLock = new Object();

    public StateStore(DeliveryProperties props,
                      @org.springframework.context.annotation.Lazy
                      com.github.lechandonga.gitopsdeliveryjava.lease.LeaseManager leaseManager) {
        this.props = props;
        this.leaseManager = leaseManager;
    }

    @PostConstruct
    public void init() {
        this.dataDir = Path.of(props.getDataDir());
        this.actualDir = dataDir.resolve("actual");
        this.flowsDir = dataDir.resolve("flows");
        this.eventsFile = dataDir.resolve("events.log");
        try {
            Files.createDirectories(actualDir);
            Files.createDirectories(flowsDir);
            if (Files.notExists(eventsFile)) {
                Files.createFile(eventsFile);
            }
            replayEventSeq();
            if (leaseManager != null) {
                // 用持久化高水位恢复租约 token 序列，并把后续签发回写磁盘
                long hwm = readFencingHighWaterMark();
                if (hwm > 0) {
                    leaseManager.seedHighWaterMark(hwm);
                }
                leaseManager.setHighWaterMarkSink(this::writeFencingHighWaterMark);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("初始化本地状态库失败: " + dataDir, e);
        }
    }

    private void replayEventSeq() {
        try (Stream<String> lines = Files.lines(eventsFile, StandardCharsets.UTF_8)) {
            lines.filter(s -> !s.isBlank())
                    .mapToLong(line -> {
                        try {
                            return eventMapper.readValue(line, EventRecord.class).getSeq();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).max().ifPresent(eventSeq::set);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void saveManifest(Manifest manifest) {
        writeJsonAtomic(dataDir.resolve("manifest.json"), manifest);
    }

    public synchronized Optional<Manifest> loadManifest() {
        Path p = dataDir.resolve("manifest.json");
        if (Files.notExists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(p.toFile(), Manifest.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized ActualTargetState getOrCreateActual(String target) {
        return findActual(target).orElseGet(() -> {
            ActualTargetState s = new ActualTargetState();
            s.setName(target);
            return s;
        });
    }

    /**
     * 保存实际状态。fencingToken &gt; 0 时进行 fencing 校验：
     * 状态中记录的 lastFencing 必须不大于本次 token，否则拒绝写入。
     */
    public synchronized void saveActual(ActualTargetState state, long fencingToken) {
        verifyFencing(state.getName(), fencingToken);
        state.setUpdatedAt(System.currentTimeMillis());
        writeJsonAtomic(actualDir.resolve(state.getName() + ".json"), state);
    }

    public synchronized Optional<ActualTargetState> findActual(String target) {
        Path p = actualDir.resolve(target + ".json");
        if (Files.notExists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(p.toFile(), ActualTargetState.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<ActualTargetState> allActual() {
        List<Path> paths = listJson(actualDir);
        return paths.stream().map(path -> readJson(path, ActualTargetState.class))
                .sorted(Comparator.comparing(ActualTargetState::getName))
                .toList();
    }

    public synchronized void saveFlow(FlowRecord flow) {
        flow.setUpdatedAt(System.currentTimeMillis());
        writeJsonAtomic(flowsDir.resolve(flow.getFlowId() + ".json"), flow);
    }

    public synchronized Optional<FlowRecord> findFlow(String flowId) {
        Path p = flowsDir.resolve(flowId + ".json");
        if (Files.notExists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(p.toFile(), FlowRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 查找目标当前未终结的流程（重启恢复 / 并发合并使用）。 */
    public synchronized Optional<FlowRecord> findActiveFlow(String target) {
        return listJson(flowsDir).stream().map(p -> readJson(p, FlowRecord.class))
                .filter(f -> target.equals(f.getTarget()) && !isTerminal(f.getStatus()))
                .max(Comparator.comparingLong(FlowRecord::getCreatedAt));
    }

    public synchronized List<FlowRecord> allFlows() {
        return listJson(flowsDir).stream().map(p -> readJson(p, FlowRecord.class))
                .sorted(Comparator.comparingLong(FlowRecord::getCreatedAt))
                .toList();
    }

    private List<Path> listJson(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.toString().endsWith(".json")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T readJson(Path path, Class<T> type) {
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean isTerminal(FlowStatus status) {
        return status == FlowStatus.SUCCEEDED || status == FlowStatus.FAILED
                || status == FlowStatus.ROLLED_BACK || status == FlowStatus.CANCELLED
                || status == FlowStatus.TIMED_OUT || status == FlowStatus.REJECTED;
    }

    /**
     * 追加审计事件（WAL，先于状态变更落盘语义由调用方保证）。
     * 返回全局递增序号；重启后从日志恢复序号，不回退、不重复。
     */
    public long appendEvent(EventRecord event, long fencingToken) {
        if (event.getTarget() != null) {
            verifyFencing(event.getTarget(), fencingToken);
        }
        String line;
        long seq;
        // 事件追加与读取互斥，避免观察到半行
        synchronized (eventLogLock) {
            seq = eventSeq.incrementAndGet();
            event.setSeq(seq);
            if (event.getTimestamp() == 0) {
                event.setTimestamp(System.currentTimeMillis());
            }
            event.setFencingToken(fencingToken);
            try {
                line = eventMapper.writeValueAsString(event);
                Files.writeString(eventsFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return seq;
    }

    public List<EventRecord> events(String target) {
        List<String> lines;
        synchronized (eventLogLock) {
            try {
                lines = Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return lines.stream().filter(s -> !s.isBlank()).map(lineStr -> {
            try {
                return eventMapper.readValue(lineStr, EventRecord.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).filter(e -> target == null || target.equals(e.getTarget()))
                .sorted(Comparator.comparingLong(EventRecord::getSeq))
                .toList();
    }

    /**
     * fencing 校验。
     * <ol>
     *   <li>token=0 代表系统内部写（清单/人工），跳过校验；</li>
     *   <li>若租约管理器存在该目标的活跃租约，写入 token 必须等于当前 token——
     *       已被接管的旧持有者用旧 token 写入在此被拒；</li>
     *   <li>再以持久化的“见过的最大 token”兜底（重启后租约表为空也能防回退）。</li>
     * </ol>
     */
    private final LinkedHashMap<String, Long> maxFencingByTarget = new LinkedHashMap<>();

    private void verifyFencing(String target, long fencingToken) {
        if (fencingToken <= 0) {
            return;
        }
        if (leaseManager != null) {
            Long activeToken = leaseManager.currentToken(target);
            if (activeToken != null && fencingToken != activeToken) {
                throw new DeliveryRejectedException(ReasonCode.FENCING_TOKEN_STALE,
                        "target=" + target + " token=" + fencingToken
                                + " 非当前租约 token=" + activeToken);
            }
        }
        long known = maxFencingByTarget.computeIfAbsent(target, this::readFencingMarker);
        if (fencingToken < known) {
            throw new DeliveryRejectedException(ReasonCode.FENCING_TOKEN_STALE,
                    "target=" + target + " token=" + fencingToken + " knownMax=" + known);
        }
        if (fencingToken > known) {
            maxFencingByTarget.put(target, fencingToken);
            writeFencingMarker(target, fencingToken);
        }
    }

    private Path fencingMarker(String target) {
        return dataDir.resolve("fencing-" + target + ".txt");
    }

    /** 全局 fencing token 高水位文件（跨重启单调的依据）。 */
    private Path fencingHighWaterMarkFile() {
        return dataDir.resolve("fencing-hwm.txt");
    }

    public long readFencingHighWaterMark() {
        try {
            Path p = fencingHighWaterMarkFile();
            if (Files.notExists(p)) {
                return 0L;
            }
            return Long.parseLong(Files.readString(p, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeFencingHighWaterMark(long token) {
        try {
            synchronized (eventLogLock) {
                long current = Files.notExists(fencingHighWaterMarkFile())
                        ? 0L
                        : Long.parseLong(Files.readString(fencingHighWaterMarkFile(),
                                StandardCharsets.UTF_8).trim());
                if (token > current) {
                    Files.writeString(fencingHighWaterMarkFile(), Long.toString(token),
                            StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long readFencingMarker(String target) {
        try {
            Path p = fencingMarker(target);
            if (Files.notExists(p)) {
                return 0L;
            }
            return Long.parseLong(Files.readString(p, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeFencingMarker(String target, long token) {
        try {
            Files.writeString(fencingMarker(target), Long.toString(token), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeJsonAtomic(Path target, Object value) {
        // 同一目标文件串行原子写，避免两个写入者共用临时文件名而互相截断
        Path lock = target.toAbsolutePath().normalize();
        synchronized (fileLocks.computeIfAbsent(lock, k -> new Object())) {
            try {
                Path tmp = target.resolveSibling(target.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
                mapper.writeValue(tmp.toFile(), value);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
