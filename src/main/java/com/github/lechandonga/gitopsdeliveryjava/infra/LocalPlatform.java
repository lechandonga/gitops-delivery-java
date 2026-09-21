package com.github.lechandonga.gitopsdeliveryjava.infra;

/**
 * 本地模拟的执行与部署环境：真实状态保存在内存/本地文件中，
 * 支持注入故障与健康曲线，供灰度与回滚判定使用。
 */
public interface LocalPlatform {
    /** 按权重部署/放量；fenceToken 必须与当前有效租约匹配，否则拒绝双写。 */
    DeployResult deploy(String target, String version, int weightPercent, int fenceToken);

    /** 健康探测。 */
    HealthStatus health(String target);

    /** 查询某目标当前承载的版本（实际状态）。 */
    String currentVersion(String target);

    /** 测试支撑：强制下一次部署失败。 */
    void failNextDeploy(String target);

    /** 测试支撑：设置健康返回曲线（按调用次数）。 */
    void setHealthCurve(String target, java.util.List<Double> successRateCurve);

    /** 测试支撑：按版本设置健康曲线（仅该承载版本生效，便于隔离回滚判定）。 */
    void setHealthCurveForVersion(String target, String version,
                                  java.util.List<Double> successRateCurve);

    /** 取消标记：目标正在执行时被取消则中断。 */
    void cancel(String target);

    boolean isCancelled(String target);
}
