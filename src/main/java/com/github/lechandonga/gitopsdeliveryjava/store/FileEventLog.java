package com.github.lechandonga.gitopsdeliveryjava.store;

import com.github.lechandonga.gitopsdeliveryjava.event.DeliveryEvent;
import com.github.lechandonga.gitopsdeliveryjava.event.EventLog;
import com.github.lechandonga.gitopsdeliveryjava.event.EventType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于本地 JSONL 文件的追加式事件日志。
 * append 每条事件刷盘；重启后 readAll 重放，不丢失关键事件。
 */
public class FileEventLog implements EventLog {

    private final Path file;
    private long lastSeq;

    public FileEventLog(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            this.file = dataDir.resolve("events.jsonl");
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
            this.lastSeq = scanLastSeq();
        } catch (IOException e) {
            throw new UncheckedIOException("init event log failed", e);
        }
    }

    @Override
    public synchronized DeliveryEvent append(EventType type, String target, String version,
                                              String trigger, String reasonCode,
                                              Map<String, String> detail) {
        long seq = lastSeq + 1;
        DeliveryEvent event = new DeliveryEvent(seq, System.currentTimeMillis(), type, target,
                version, trigger, reasonCode,
                detail == null ? Map.of() : new LinkedHashMap<>(detail));
        try {
            String line = Json.MAPPER.writeValueAsString(event);
            // 单行原子追加 + flush，保证崩溃时事件完整或缺失，不会出现半行
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            lastSeq = seq;
        } catch (IOException e) {
            throw new UncheckedIOException("append event failed", e);
        }
        return event;
    }

    @Override
    public synchronized List<DeliveryEvent> readAll() {
        List<DeliveryEvent> result = new ArrayList<>();
        long seq = 0L;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                DeliveryEvent event = Json.MAPPER.readValue(line, DeliveryEvent.class);
                result.add(event);
                seq = event.seq();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("read event log failed", e);
        }
        lastSeq = seq;
        return result;
    }

    @Override
    public synchronized long lastSeq() {
        return lastSeq;
    }

    private long scanLastSeq() {
        long seq = 0L;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    seq = Json.MAPPER.readValue(line, DeliveryEvent.class).seq();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("scan event log failed", e);
        }
        return seq;
    }
}
