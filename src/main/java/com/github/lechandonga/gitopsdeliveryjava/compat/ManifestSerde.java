package com.github.lechandonga.gitopsdeliveryjava.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.gitopsdeliveryjava.exception.DeliveryRejectedException;
import com.github.lechandonga.gitopsdeliveryjava.model.CanarySpec;
import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.FreezeWindow;
import com.github.lechandonga.gitopsdeliveryjava.model.Manifest;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import org.springframework.stereotype.Component;

/**
 * 历史清单/旧版本事件兼容层。
 * <p>
 * 兼容性契约（默认语义确定且稳定）：
 * <ul>
 *   <li>新增字段缺失：采用模型中的稳定默认值（如 canary.enabled=false、environment=default、
 *       maxAttempts=3、灰度健康阈值 0.8），旧清单可直接被新版本读取；</li>
 *   <li>未知/拼写错误字段：FAIL_ON_UNKNOWN_PROPERTIES → UNKNOWN_FIELD_REJECTED 明确拒绝，
 *       绝不静默忽略；</li>
 *   <li>不支持的 apiVersion/kind、不支持的灰度步长等：明确拒绝；</li>
 *   <li>事件读取（旧版本可能缺新字段）使用宽容 mapper，保证历史可回放。</li>
 * </ul>
 */
@Component
public class ManifestSerde {

    private final ObjectMapper strictMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ObjectMapper lenientMapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public Manifest parse(String json) {
        Manifest manifest;
        try {
            manifest = strictMapper.readValue(json, Manifest.class);
        } catch (JsonMappingException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Unrecognized field") || msg.contains("未知字段")) {
                throw new DeliveryRejectedException(ReasonCode.UNKNOWN_FIELD_REJECTED, msg);
            }
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID, msg);
        } catch (JsonProcessingException e) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "清单 JSON 无法解析: " + e.getMessage());
        }
        validate(manifest);
        return manifest;
    }

    public String toJson(Manifest manifest) {
        try {
            return strictMapper.writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID, e.getMessage());
        }
    }

    /** 宽容读取：用于旧版本事件/快照回放，未知字段忽略，缺失字段取默认。 */
    public <T> T readLenient(String json, Class<T> type) {
        try {
            return lenientMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID, e.getMessage());
        }
    }

    private void validate(Manifest manifest) {
        if (manifest.getApiVersion() == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID, "缺少 apiVersion");
        }
        if (!Manifest.SUPPORTED_API_VERSION.equals(manifest.getApiVersion())) {
            throw new DeliveryRejectedException(ReasonCode.API_VERSION_UNSUPPORTED,
                    "不支持的 apiVersion=" + manifest.getApiVersion()
                            + "，仅支持 " + Manifest.SUPPORTED_API_VERSION);
        }
        if (manifest.getKind() == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID, "缺少 kind");
        }
        if (!Manifest.SUPPORTED_KIND.equals(manifest.getKind())) {
            throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                    "不支持的 kind=" + manifest.getKind() + "，仅支持 " + Manifest.SUPPORTED_KIND);
        }
        if (manifest.getTargets().isEmpty()) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID, "清单不包含任何目标");
        }
        manifest.getTargets().forEach((name, t) -> validateTarget(name, t));
    }

    private void validateTarget(String name, DesiredTarget t) {
        if (t.getName() == null) {
            t.setName(name);
        } else if (!t.getName().equals(name)) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "目标键名与 name 字段不一致: " + name + " vs " + t.getName());
        }
        if (t.getDesiredVersion() == null || t.getDesiredVersion().isBlank()) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "target=" + name + " 缺少 desiredVersion");
        }
        if (t.getTimeoutSeconds() <= 0) {
            throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                    "target=" + name + " timeoutSeconds 必须为正数");
        }
        if (t.getMaxAttempts() <= 0) {
            throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                    "target=" + name + " maxAttempts 必须为正数（重试有界）");
        }
        CanarySpec c = t.getCanary();
        if (c != null && c.isEnabled()) {
            if (c.getSteps() == null || c.getSteps().isEmpty()) {
                throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                        "target=" + name + " 灰度 steps 不能为空");
            }
            int prev = 0;
            for (Integer step : c.getSteps()) {
                if (step == null || step <= prev || step > 100) {
                    throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                            "target=" + name + " 灰度步长必须严格递增且在 (0,100]: " + c.getSteps());
                }
                prev = step;
            }
            if (c.getMinHealthyRatio() <= 0 || c.getMinHealthyRatio() > 1) {
                throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                        "target=" + name + " minHealthyRatio 必须在 (0,1]");
            }
        }
        validateFreezeWindow(name, t.getFreezeWindow());
    }

    private void validateFreezeWindow(String name, FreezeWindow w) {
        if (w == null) {
            return; // 旧清单/未声明：默认不冻结
        }
        if (w.getStart() == null || w.getEnd() == null) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "target=" + name + " freezeWindow 必须同时声明 start 与 end（HH:mm）");
        }
        java.time.LocalTime start;
        java.time.LocalTime end;
        try {
            start = java.time.LocalTime.parse(w.getStart(),
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "target=" + name + " freezeWindow.start 不是合法 HH:mm: " + w.getStart());
        }
        try {
            end = java.time.LocalTime.parse(w.getEnd(),
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new DeliveryRejectedException(ReasonCode.MANIFEST_INVALID,
                    "target=" + name + " freezeWindow.end 不是合法 HH:mm: " + w.getEnd());
        }
        if (start.equals(end)) {
            // start==end 语义歧义（全天？空集？），明确拒绝，禁止静默降级
            throw new DeliveryRejectedException(ReasonCode.UNSUPPORTED_OPTION,
                    "target=" + name + " freezeWindow start 与 end 不得相同: "
                            + w.getStart() + "-" + w.getEnd());
        }
    }
}
