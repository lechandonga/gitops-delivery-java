package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.engine.TimeSource;
import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.FreezeWindow;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 变更冻结窗口判定（纯只读，不改变任何状态）。
 * <p>
 * 边界语义对两种窗口一致：【含起始时刻，不含结束时刻】{@code [start, end)}。
 * 冻结只防“变更”：常规发布推进被拦截，紧急回滚/取消/恢复不经过本判定。
 * 窗口时间一律使用系统默认时区解释。
 */
@Component
public class FreezeGate {

    private final TimeSource timeSource;

    public FreezeGate(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    /** 返回命中的冻结窗口；未命中返回 empty。 */
    public Optional<FreezeWindow> activeWindow(DesiredTarget desired) {
        List<FreezeWindow> windows = desired.getFreezeWindows();
        if (windows == null || windows.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timeSource.currentTimeMillis()), timeSource.zone());
        for (FreezeWindow window : windows) {
            if (contains(window, now)) {
                return Optional.of(window);
            }
        }
        return Optional.empty();
    }

    public boolean isFrozen(DesiredTarget desired) {
        return activeWindow(desired).isPresent();
    }

    /** [start, end) 边界：start 时刻已冻结；end 时刻已解冻。 */
    static boolean contains(FreezeWindow window, LocalDateTime now) {
        return switch (window.getType() == null ? "" : window.getType()) {
            case FreezeWindow.TYPE_DAILY -> containsDaily(window, now.toLocalDate(), now.toLocalTime());
            case FreezeWindow.TYPE_INTERVAL -> {
                LocalDateTime start = parseDateTime(window.getStart());
                LocalDateTime end = parseDateTime(window.getEnd());
                yield !now.isBefore(start) && now.isBefore(end);
            }
            default -> false;
        };
    }

    private static boolean containsDaily(FreezeWindow w, LocalDate today, LocalTime now) {
        LocalTime start = parseHhMm(w.getStart());
        LocalTime end = parseHhMm(w.getEnd());
        if (start.equals(end)) {
            return true; // 00:00-00:00（含任意起止相等）表示全天冻结
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // 跨午夜：[start,24:00) ∪ [00:00,end)
        return !now.isBefore(start) || now.isBefore(end);
    }

    public static LocalTime parseHhMm(String value) {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间必须为 HH:mm 格式: " + value);
        }
    }

    public static LocalDateTime parseDateTime(String value) {
        for (DateTimeFormatter f : new DateTimeFormatter[]{
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")}) {
            try {
                return LocalDateTime.parse(value, f);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        throw new IllegalArgumentException(
                "时间必须为 yyyy-MM-dd'T'HH:mm 或 yyyy-MM-dd'T'HH:mm:ss 格式: " + value);
    }

}
