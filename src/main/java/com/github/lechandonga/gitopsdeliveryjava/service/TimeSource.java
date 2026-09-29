package com.github.lechandonga.gitopsdeliveryjava.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;

/**
 * 冻结窗口判定用时钟。生产使用系统默认时区；测试可替换以便确定性地覆盖窗口边界。
 */
@Component
public class TimeSource {

    private volatile Clock clock = Clock.systemDefaultZone();

    public LocalTime now() {
        return LocalTime.now(clock);
    }

    /** 仅供测试：把时钟固定到指定时间点（HH:mm:ss，本地时区）。 */
    public void pin(LocalTime fixed) {
        this.clock = Clock.fixed(
                java.time.LocalDate.now(Clock.systemDefaultZone()).atTime(fixed)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant(),
                java.time.ZoneId.systemDefault());
    }

    /** 仅供测试：恢复为实时时钟。 */
    public void reset() {
        this.clock = Clock.systemDefaultZone();
    }
}
