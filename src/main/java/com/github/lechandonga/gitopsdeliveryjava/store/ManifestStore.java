package com.github.lechandonga.gitopsdeliveryjava.store;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.github.lechandonga.gitopsdeliveryjava.engine.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.engine.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.CanarySpec;
import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ManifestStage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地期望状态清单读写。
 * 兼容规则（确定且稳定）：
 * - schemaVersion 缺省 = {@value #DEFAULT_SCHEMA_VERSION}；
 * - protectedEnv / requireApproval 缺省 = false；
 * - stage.dependsOn 缺省 = 空；canary 缺省 = 无灰度；
 * - executionTimeoutMillis 缺省 = {@value #DEFAULT_TIMEOUT_MS}；maxAttempts 缺省 = 3；
 * - 任何未知字段、不支持的 schemaVersion / 灰度选项 -> 明确拒绝（不静默忽略）。
 */
public class ManifestStore {

    public static final String DEFAULT_SCHEMA_VERSION = "delivery.gitops/v1";
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final Path file;

    public ManifestStore(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            this.file = dataDir.resolve("manifest.json");
        } catch (IOException e) {
            throw new UncheckedIOException("init manifest store failed", e);
        }
    }

    public synchronized void save(DeliveryManifest manifest) {
        validate(manifest);
        try {
            Files.writeString(file, Json.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("save manifest failed", e);
        }
    }

    public synchronized DeliveryManifest load() {
        if (Files.notExists(file)) {
            return null;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            return parse(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("load manifest failed", e);
        }
    }

    /** 从 JSON 文本解析并补齐默认值；严格拒绝未知字段与不支持选项。 */
    public DeliveryManifest parse(String raw) {
        ManifestDto dto;
        try {
            dto = Json.MAPPER.readValue(raw, ManifestDto.class);
        } catch (UnrecognizedPropertyException e) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                    "unsupported/unknown manifest field: " + e.getPropertyName());
        } catch (IOException e) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "manifest parse failed: " + e.getMessage());
        }
        return toManifest(dto);
    }

    private DeliveryManifest toManifest(ManifestDto dto) {
        if (dto == null || dto.name == null || dto.name.isBlank()) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "manifest.name is required");
        }
        String schema = dto.schemaVersion == null || dto.schemaVersion.isBlank()
                ? DEFAULT_SCHEMA_VERSION : dto.schemaVersion;
        if (!DEFAULT_SCHEMA_VERSION.equals(schema)) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                    "unsupported schemaVersion: " + schema);
        }
        List<ManifestStage> stages = new ArrayList<>();
        if (dto.stages != null) {
            for (StageDto s : dto.stages) {
                stages.add(toStage(s));
            }
        }
        if (stages.isEmpty()) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "manifest.stages must contain at least one stage");
        }
        return new DeliveryManifest(schema, dto.name,
                Boolean.TRUE.equals(dto.protectedEnv),
                Boolean.TRUE.equals(dto.requireApproval),
                List.copyOf(stages));
    }

    private ManifestStage toStage(StageDto s) {
        if (s == null || s.id == null || s.id.isBlank()) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "stage.id is required");
        }
        if (s.env == null || s.env.isBlank()) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "stage.env is required: " + s.id);
        }
        if (s.desiredVersion == null || s.desiredVersion.isBlank()) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "stage.desiredVersion is required: " + s.id);
        }
        List<String> deps = s.dependsOn == null ? List.of() : List.copyOf(s.dependsOn);
        CanarySpec canary = toCanary(s.canary, s.id);
        long timeout = s.executionTimeoutMillis == null
                ? DEFAULT_TIMEOUT_MS : s.executionTimeoutMillis;
        int attempts = s.maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : s.maxAttempts;
        if (timeout <= 0) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                    "executionTimeoutMillis must be positive: " + s.id);
        }
        if (attempts < 1 || attempts > 10) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                    "maxAttempts must be within 1..10: " + s.id);
        }
        return new ManifestStage(s.id, s.env, s.desiredVersion, deps, canary, timeout, attempts);
    }

    private CanarySpec toCanary(CanaryDto c, String stageId) {
        if (c == null) {
            return null;
        }
        if (c.weights == null || c.weights.isEmpty()) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "canary.weights is required: " + stageId);
        }
        int prev = 0;
        for (Integer w : c.weights) {
            if (w == null || w <= prev || w > 100) {
                throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                        "canary.weights must strictly increase within (0,100]: " + stageId);
            }
            prev = w;
        }
        long wait = c.stepWaitMillis == null ? 1_000L : c.stepWaitMillis;
        double threshold = c.healthThreshold == null ? 0.95 : c.healthThreshold;
        if (wait < 0 || threshold <= 0 || threshold > 1.0) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                    "invalid canary timing/threshold: " + stageId);
        }
        return new CanarySpec(List.copyOf(c.weights), wait, threshold);
    }

    private void validate(DeliveryManifest manifest) {
        if (manifest == null) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "manifest is null");
        }
        if (!DEFAULT_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNSUPPORTED_OPTION,
                    "unsupported schemaVersion: " + manifest.schemaVersion());
        }
    }

    /** 原始映射 DTO：未知字段由 FAIL_ON_UNKNOWN_PROPERTIES 拒绝。 */
    public static class ManifestDto {
        @JsonProperty("schemaVersion") public String schemaVersion;
        @JsonProperty("name") public String name;
        @JsonProperty("protectedEnv") public Boolean protectedEnv;
        @JsonProperty("requireApproval") public Boolean requireApproval;
        @JsonProperty("stages") public List<StageDto> stages;
    }

    public static class StageDto {
        @JsonProperty("id") public String id;
        @JsonProperty("env") public String env;
        @JsonProperty("desiredVersion") public String desiredVersion;
        @JsonProperty("dependsOn") public List<String> dependsOn;
        @JsonProperty("canary") public CanaryDto canary;
        @JsonProperty("executionTimeoutMillis") public Long executionTimeoutMillis;
        @JsonProperty("maxAttempts") public Integer maxAttempts;
    }

    public static class CanaryDto {
        @JsonProperty("weights") public List<Integer> weights;
        @JsonProperty("stepWaitMillis") public Long stepWaitMillis;
        @JsonProperty("healthThreshold") public Double healthThreshold;
    }
}
