package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.EventRecord;
import com.github.lechandonga.gitopsdeliveryjava.model.ReasonCode;
import com.github.lechandonga.gitopsdeliveryjava.model.Stage;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import com.github.lechandonga.gitopsdeliveryjava.store.StateStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标版本历史重建器（只读）。
 * <p>
 * 数据来源全部是<strong>持久化的旧数据</strong>：事件 WAL（权威，按 seq 顺序）
 * 加当前实际状态快照（兜底）。不引入额外的新存储格式，因此：
 * <ul>
 *   <li>老数据读进来不报错：旧事件缺 {@code fromVersion/toVersion} 等新字段时，
       仍可从事件语义（PROMOTE_STABLE 成功、回滚类事件）还原版本；</li>
 *   <li>版本历史不丢失：只要事件在，历史就在。</li>
 * </ul>
 * “曾经稳定发布过”：PROMOTE_STABLE 成功落地的版本，或回滚类事件所切到的稳定版本。
 */
@Component
public class VersionHistory {

    private final StateStore store;

    public VersionHistory(StateStore store) {
        this.store = store;
    }

    /** 该目标曾经稳定发布过的版本，按首次成为稳定版本的先后顺序、去重。 */
    public List<String> stableVersions(String target) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (EventRecord e : store.events(target)) {
            String promoted = promotedStableVersion(e);
            if (promoted != null) {
                ordered.add(promoted);
            }
        }
        // 快照兜底：仅当前 STABLE 才算“正在稳定”；
        // previousStableVersion 在自动回滚路径可能记录着“失败的灰度版本”，不能当作稳定历史。
        ActualTargetState actual = store.findActual(target).orElse(null);
        if (actual != null && actual.getPhase() == TargetPhase.STABLE
                && actual.getStableVersion() != null) {
            ordered.add(actual.getStableVersion());
        }
        return new ArrayList<>(ordered);
    }

    /** 该目标发布过的全部版本（含仅走到灰度、未稳定的版本），用于区分两类拒绝。 */
    public Set<String> publishedVersions(String target) {
        LinkedHashSet<String> versions = new LinkedHashSet<>(stableVersions(target));
        for (EventRecord e : store.events(target)) {
            if (e.getInputVersion() != null && !e.getInputVersion().isBlank()) {
                versions.add(e.getInputVersion());
            }
        }
        ActualTargetState actual = store.findActual(target).orElse(null);
        if (actual != null && actual.getCanaryVersion() != null) {
            versions.add(actual.getCanaryVersion());
        }
        return versions;
    }

    private String promotedStableVersion(EventRecord e) {
        // 回滚类事件只有“完成回退”的两类会切到一个历史稳定版本；
        // 健康失败/阶段失败等原因事件的 inputVersion 是“失败的灰度版本”，绝不能算稳定。
        boolean rollbackCompleted = e.getReason() == ReasonCode.ROLLBACK_DONE
                || e.getReason() == ReasonCode.TARGET_ROLLBACK_DONE;
        // 1) 新格式事件：直接读 toVersion
        if (rollbackCompleted && e.getToVersion() != null) {
            return e.getToVersion();
        }
        if (e.getStage() == Stage.PROMOTE_STABLE && e.getReason() == ReasonCode.NONE
                && e.getInputVersion() != null) {
            return e.getInputVersion();
        }
        // 2) 旧格式回滚事件：从 detail 中无法可靠解析时退化为快照/inputVersion 兜底。
        //    旧链路中回滚事件的 toState=ROLLED_BACK，其稳定版本由快照 previous/stable 覆盖。
        if (rollbackCompleted
                && TargetPhase.ROLLED_BACK.name().equals(e.getToState())
                && e.getInputVersion() != null
                && (e.getFromVersion() != null || e.getToVersion() != null)) {
            return e.getToVersion();
        }
        return null;
    }
}
