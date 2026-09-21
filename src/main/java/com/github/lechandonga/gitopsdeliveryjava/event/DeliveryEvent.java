package com.github.lechandonga.gitopsdeliveryjava.event;

import java.util.Map;

/**
 * 不可变领域事件：每次状态迁移记录输入与判定依据。
 * {@code detail} 保存可解释的判定上下文（原因码、触发入口、版本等）。
 */
public record DeliveryEvent(
        long seq,
        long timestamp,
        EventType type,
        String target,
        String version,
        String trigger,
        String reasonCode,
        Map<String, String> detail) {
}
