package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 清单中单个交付目标的期望状态。
 */
public class DesiredTarget {

    private String name;
    private String desiredVersion;
    private String environment = "default";
    private List<String> dependsOn = new ArrayList<>();
    private CanarySpec canary = new CanarySpec();
    private ProtectionPolicy protection = new ProtectionPolicy();
    private int timeoutSeconds = 60;
    private int maxAttempts = 3;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDesiredVersion() { return desiredVersion; }
    public void setDesiredVersion(String desiredVersion) { this.desiredVersion = desiredVersion; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
    public CanarySpec getCanary() { return canary; }
    public void setCanary(CanarySpec canary) { this.canary = canary; }
    public ProtectionPolicy getProtection() { return protection; }
    public void setProtection(ProtectionPolicy protection) { this.protection = protection; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
