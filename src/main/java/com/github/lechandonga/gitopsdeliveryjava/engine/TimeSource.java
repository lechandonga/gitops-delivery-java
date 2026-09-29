package com.github.lechandonga.gitopsdeliveryjava.engine;

import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 统一时钟：冻结窗口按此时钟判定。生产实现读取系统时间与系统默认时区；
 * 测试可替换为固定时钟，以确定性地验证窗口边界语义。
 */
@Component
public class TimeSource {

    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public ZoneId zone() {
        return ZoneId.systemDefault();
    }
}
