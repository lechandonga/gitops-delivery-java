package com.github.lechandonga.gitopsdeliveryjava.model;

/**
 * 所有拒绝、回滚、接管等结论的可区分原因码。
 * 每个结论都必须携带一个明确的原因码，禁止静默忽略。
 */
public enum ReasonCode {
    NONE("正常/无拒绝"),
    ALREADY_CONVERGED("期望状态与实际状态一致，无需动作"),
    DRIFT_DETECTED("检测到期望状态与实际状态漂移"),
    CIRCULAR_DEPENDENCY("目标依赖图存在循环依赖"),
    PREREQUISITE_NOT_READY("前置依赖目标尚未收敛到稳定版本"),
    HEALTH_CHECK_FAILED("灰度健康判定未达标"),
    ROLLBACK_DONE("已回滚到上一个稳定版本"),
    ROLLBACK_FAILED("回滚本身失败"),
    NO_STABLE_VERSION("不存在可回滚的上一个稳定版本"),
    LEASE_HELD_BY_OTHER("目标租约被其他执行者持有"),
    LEASE_EXPIRED("本地租约已过期，必须停止写入"),
    LEASE_TAKEN_OVER("过期租约已被新执行者安全接管"),
    FENCING_TOKEN_STALE("写入被拒绝：fencing token 已过期"),
    DUPLICATE_TRIGGER_COALESCED("重复触发已合并到进行中的同一流程"),
    POLICY_ENV_PROTECTED("目标环境受保护，禁止未授权推进"),
    POLICY_UNAUTHORIZED("调用方角色未通过门禁授权"),
    POLICY_APPROVAL_REQUIRED("受保护环境缺少发布审批"),
    FREEZE_WINDOW_BLOCKED("目标处于变更冻结窗口，常规推进被禁止（紧急回滚/取消不受限）"),
    QUOTA_QUEUED("并发额度已满，操作已按策略排队"),
    QUOTA_REJECTED("并发额度与队列均已满，操作被拒绝"),
    FLOW_PAUSED("灰度流程已人工暂停，停在当前放量步骤，未判成功也未判失败"),
    FLOW_RESUMED("灰度流程已恢复，从暂停所在步骤继续推进"),
    PAUSE_NOT_RUNNING("暂停被拒绝：目标当前没有进行中的流程"),
    RESUME_NOT_PAUSED("恢复被拒绝：目标当前没有处于暂停状态的流程"),
    ACTIVE_FLOW_CONFLICT("目标存在进行中的流程，不能执行指定版本回退，请先取消或等待其结束"),
    TARGET_ROLLBACK_DONE("已回退到指定的历史稳定版本"),
    VERSION_NOT_PUBLISHED("指定版本从未在该目标上发布过"),
    VERSION_NEVER_STABLE("指定版本在该目标上从未稳定发布过，不能作为回退目标"),
    STAGE_FAILED("阶段执行失败"),
    STAGE_TIMEOUT("阶段执行超时"),
    FLOW_CANCELLED("流程被显式取消"),
    RETRIES_EXHAUSTED("有界重试次数耗尽"),
    MANIFEST_INVALID("清单语法或语义非法"),
    UNKNOWN_FIELD_REJECTED("清单含未知字段，已拒绝而非忽略"),
    UNSUPPORTED_OPTION("清单使用了不支持的选项"),
    API_VERSION_UNSUPPORTED("不支持的清单 apiVersion"),
    RECOVERED_AFTER_RESTART("进程重启后恢复未完成流程"),
    INTERRUPTED_NOT_SUCCESS("被中断的流程不得判定为成功");

    private final String defaultMessage;

    ReasonCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
