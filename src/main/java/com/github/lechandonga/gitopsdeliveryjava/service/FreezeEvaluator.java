package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.FreezeWindow;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 变更冻结窗口判定（只读，不改任何状态）。
 * <p>
 * 窗口左闭右开 {@code [start, end)}：t == start 为冻结，t == end 已恢复；
 * {@code start > end} 表示跨午夜窗口。
 * 仅阻断“变更”：紧急回滚（自动/手动/指定历史版本）与取消的调用方不得经过本判定。
 */
@Component
public class FreezeEvaluator {

    static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final TimeSource timeSource;

    public FreezeEvaluator(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public boolean isFrozen(DesiredTarget desired) {
        return isFrozen(desired, timeSource.now());
    }

    public boolean isFrozen(DesiredTarget desired, LocalTime now) {
        FreezeWindow w = desired == null ? null : desired.getFreezeWindow();
        if (w == null) {
            return false; // 旧清单/未声明窗口：默认不冻结
        }
        LocalTime start = LocalTime.parse(w.getStart(), HHMM);
        LocalTime end = LocalTime.parse(w.getEnd(), HHMM);
        // start < end：同日窗口 [start, end)；start > end：跨午夜 [start,24:00)∪[00:00,end)
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }
}
