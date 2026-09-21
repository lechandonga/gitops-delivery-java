package com.github.lechandonga.gitopsdeliveryjava.engine;

import com.github.lechandonga.gitopsdeliveryjava.model.ActualTargetState;
import com.github.lechandonga.gitopsdeliveryjava.model.DesiredTarget;
import com.github.lechandonga.gitopsdeliveryjava.model.TargetPhase;
import org.springframework.stereotype.Component;

/**
 * 期望状态 vs 本地实际状态的漂移检测。
 * <p>
 * 已收敛的唯一定义：实际相位为 STABLE，且稳定版本等于期望版本。
 * 因此：
 * <ul>
 *   <li>CANARY / BUILT / FAILED / ROLLED_BACK（版本不同）一律视为漂移，驱动继续收敛；</li>
 *   <li>重复收敛在已一致时不产生任何动作（幂等的无副作用由上层据此保证）。</li>
 * </ul>
 */
@Component
public class DriftDetector {

    public boolean isConverged(DesiredTarget desired, ActualTargetState actual) {
        if (actual == null || desired == null) {
            return false;
        }
        return actual.getPhase() == TargetPhase.STABLE
                && desired.getDesiredVersion() != null
                && desired.getDesiredVersion().equals(actual.getStableVersion());
    }

    /** 是否存在半发布残留（灰度中/构建后未推广/失败态），用于恢复与回滚清理判定。 */
    public boolean isHalfReleased(ActualTargetState actual) {
        if (actual == null) {
            return false;
        }
        TargetPhase p = actual.getPhase();
        return p == TargetPhase.CANARY || p == TargetPhase.BUILT || p == TargetPhase.FAILED;
    }
}
