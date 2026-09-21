package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.model.DeliveryManifest;

import java.util.Map;
import java.util.Set;

/**
 * 推进门禁：纯判定、零副作用，拒绝不改变任何已发布版本状态。
 * 规则：
 * - trigger 不在已知入口集合 -> GATE_UNAUTHORIZED_TRIGGER；
 * - 受保护环境(requireApproval) 未审批 -> GATE_PROTECTED_NO_APPROVAL；
 * - 非受保护环境不要求审批；非法入参 -> GATE_MANIFEST_INVALID。
 */
public class GateKeeper {

    /** 受支持的触发入口（其余入口视为未授权，而非静默接受）。 */
    public static final Set<String> KNOWN_TRIGGERS = Set.of(
            "api", "scheduler", "event", "recovery", "rollback", "test");

    public GateKeeper() {
    }

    public void check(DeliveryManifest manifest, String env, String trigger, boolean approved) {
        if (manifest == null) {
            throw new DeliveryRejectedException(ReasonCode.GATE_MANIFEST_INVALID,
                    "no manifest applied");
        }
        if (trigger == null || !KNOWN_TRIGGERS.contains(trigger)) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNAUTHORIZED_TRIGGER,
                    "trigger not authorized: " + trigger);
        }
        if (manifest.requireApproval() && !approved) {
            throw new DeliveryRejectedException(ReasonCode.GATE_PROTECTED_NO_APPROVAL,
                    "protected env '" + env + "' requires approval before promotion");
        }
    }

    /** 回滚入口同样校验授权身份，但不需要发布审批（回滚是安全动作）。 */
    public void checkRollback(String trigger) {
        if (trigger == null || !KNOWN_TRIGGERS.contains(trigger)) {
            throw new DeliveryRejectedException(ReasonCode.GATE_UNAUTHORIZED_TRIGGER,
                    "rollback trigger not authorized: " + trigger);
        }
    }

    /** 供事件 detail 使用：返回决策依据快照。 */
    public Map<String, String> decisionContext(DeliveryManifest m, boolean approved) {
        return Map.of(
                "requireApproval", String.valueOf(m.requireApproval()),
                "approved", String.valueOf(approved),
                "protectedEnv", String.valueOf(m.protectedEnv()));
    }
}
