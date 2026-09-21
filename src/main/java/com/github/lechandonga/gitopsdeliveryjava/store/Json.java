package com.github.lechandonga.gitopsdeliveryjava.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** 共享 JSON 编解码器：未知字段一律拒绝（兼容语义显式化）。 */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    private Json() {
    }
}
