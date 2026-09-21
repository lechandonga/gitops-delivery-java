package com.github.lechandonga.gitopsdeliveryjava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地声明式期望状态清单。
 */
public class Manifest {

    public static final String SUPPORTED_API_VERSION = "gitops.local/v1";
    public static final String SUPPORTED_KIND = "DeliveryManifest";

    private String apiVersion;
    private String kind;
    private Map<String, DesiredTarget> targets = new LinkedHashMap<>();

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Map<String, DesiredTarget> getTargets() { return targets; }
    public void setTargets(Map<String, DesiredTarget> targets) { this.targets = targets; }
}
