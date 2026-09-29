package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 按天重复的变更冻结窗口（本地判定）。
 * <p>
 * 时间语义（以运行环境本地时区为准）：
 * <ul>
 *   <li>{@code start}/{@code end} 为 24 小时制 {@code HH:mm}，只精确到分钟；</li>
 *   <li>窗口为<strong>左闭右开</strong> {@code [start, end)}：落在 start 这一分钟
 *       （含整点时刻）即视为冻结，到 end 这一分钟立即恢复，无需任何额外操作；</li>
 *   <li>{@code start &lt; end}（如 {@code 22:00}-{@code 06:00}）视为<strong>跨午夜</strong>
 *       窗口：当天 start 到当天 24:00，以及 00:00 到次日 end 均冻结；</li>
 *   <li>{@code start == end} 语义有歧义，按非法窗口在清单应用时拒绝。</li>
 * </ul>
 * 冻结只阻止“变更”（常规发布推进与暂停后恢复）；紧急回滚（含指定历史版本回退）
 * 与取消不受冻结限制。
 */
public class FreezeWindow {

    private String start;
    private String end;

    public FreezeWindow() {
    }

    public FreezeWindow(String start, String end) {
        this.start = start;
        this.end = end;
    }

    public String getStart() { return start; }
    public void setStart(String start) { this.start = start; }
    public String getEnd() { return end; }
    public void setEnd(String end) { this.end = end; }
}
