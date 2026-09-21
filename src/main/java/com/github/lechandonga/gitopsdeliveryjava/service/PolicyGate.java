package com.github.lechandonga.gitopsdeliveryjava.service;

import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.ProtectionPolicy;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 受保护环境推进门禁。检查是纯只读判定，不产生任何版本状态变更。
 * <p>
 * 拒绝原因必须可区分：
 * <ol>
 *   <li>{@code POLICY_ENV_PROTECTED}：目标环境受保护（最外层语义）；</li>
 *   <li>{@code POLICY_UNAUTHORIZED}：调用方角色不满足 requiredRoles；</li>
 *   <li>{@code POLICY_APPROVAL_REQUIRED}：缺少已登记的发布审批。</li>
 * </ol>
 * 非受保护环境一律放行（NONE）。
 */
@Service
public class PolicyGate {

    public ReasonCode check(DesiredTarget desired, String actor, Set<String> actorRoles, boolean approved) {
        ProtectionPolicy p = desired.getProtection();
        if (p == null || !p.isProtectedEnv()) {
            return ReasonCode.NONE;
        }
        Set<String> roles = actorRoles == null ? Set.of() : actorRoles;
        if (p.getRequiredRoles() != null && !p.getRequiredRoles().isEmpty()
                && p.getRequiredRoles().stream().noneMatch(roles::contains)) {
            return ReasonCode.POLICY_UNAUTHORIZED;
        }
        if (p.isRequireApproval() && !approved) {
            return ReasonCode.POLICY_APPROVAL_REQUIRED;
        }
        if (p.isRequireApproval() && actor != null && p.getApprovedBy() != null
                && !p.getApprovedBy().isEmpty() && !p.getApprovedBy().contains(actor)) {
            // 配置了明确审批人名单时，当前 actor 必须在名单内（名单仅作授权补充证据）
            return ReasonCode.POLICY_APPROVAL_REQUIRED;
        }
        return ReasonCode.NONE;
    }
}
