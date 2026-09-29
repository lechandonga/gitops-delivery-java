package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 变更冻结窗口声明。冻结只拦截“变更”（常规发布推进），
 * 紧急回滚与取消在冻结时段内仍可执行。
 * <p>
 * 两种窗口（按 {@link #type} 区分），边界统一为【含起始时刻、不含结束时刻】{@code [start, end)}：
 * <ul>
 *   <li>{@code daily}：按天重复，{@code start}/{@code end} 为 {@code HH:mm}（24 小时制本地时区）。
 *       支持跨午夜（如 22:00-06:00），含义为 22:00 &lt;= now &lt; 24:00 或 00:00 &lt;= now &lt; 06:00。
 *       起止相同（如 00:00-00:00）表示全天冻结。</li>
 *   <li>{@code interval}：指定区间，{@code start}/{@code end} 为 {@code yyyy-MM-dd'T'HH:mm[:ss]}
 *       本地时区绝对时间，end 必须晚于 start。</li>
 * </ul>
 */
public class FreezeWindow {

    public static final String TYPE_DAILY = "daily";
    public static final String TYPE_INTERVAL = "interval";

    private String type = TYPE_DAILY;
    private String start;
    private String end;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }
    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }
}
