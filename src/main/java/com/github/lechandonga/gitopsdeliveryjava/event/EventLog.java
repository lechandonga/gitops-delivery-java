package com.github.lechandonga.gitopsdeliveryjava.event;

import java.util.List;
import java.util.Map;

/** 追加式事件日志：append 落盘，readAll 用于重启重放。 */
public interface EventLog {
    DeliveryEvent append(EventType type, String target, String version,
                         String trigger, String reasonCode, Map<String, String> detail);

    List<DeliveryEvent> readAll();

    long lastSeq();
}
