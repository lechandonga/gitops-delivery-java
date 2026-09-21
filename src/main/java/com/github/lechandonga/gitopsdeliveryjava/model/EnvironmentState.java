package com.github.lechandonga.gitopsdeliveryjava.model;

/** 环境级别的实际状态投影。 */
public class EnvironmentState {
    public String env;
    public boolean protectedEnv;
    public String currentVersion;
    public String lastStableVersion;
    public boolean approved;
    public long updatedAt;

    public EnvironmentState() {
    }
}
