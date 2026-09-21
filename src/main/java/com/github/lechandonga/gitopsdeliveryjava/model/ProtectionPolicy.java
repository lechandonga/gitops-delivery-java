package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 受保护环境推进门禁策略。
 */
public class ProtectionPolicy {

    private boolean protectedEnv = false;
    private Set<String> requiredRoles = new LinkedHashSet<>();
    private boolean requireApproval = false;
    private Set<String> approvedBy = new LinkedHashSet<>();

    public boolean isProtectedEnv() { return protectedEnv; }
    public void setProtectedEnv(boolean protectedEnv) { this.protectedEnv = protectedEnv; }
    public Set<String> getRequiredRoles() { return requiredRoles; }
    public void setRequiredRoles(Set<String> requiredRoles) { this.requiredRoles = requiredRoles; }
    public boolean isRequireApproval() { return requireApproval; }
    public void setRequireApproval(boolean requireApproval) { this.requireApproval = requireApproval; }
    public Set<String> getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Set<String> approvedBy) { this.approvedBy = approvedBy; }
}
