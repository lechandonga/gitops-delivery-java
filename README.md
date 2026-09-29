# 本地持续交付编排与稳态收敛（GitOps Delivery, Java/Spring Boot）

用**本地清单**描述期望状态、用**本地模拟的执行/部署基础设施**描述实际状态，使
发布、灰度放量、回滚在不同触发入口（HTTP API / 周期收敛 / 手动调用 / 重启恢复）下
得到**一致且可解释**的结论。

所有数据落在本地目录（默认 `target/delivery-data/`），不依赖任何外部集群。

---

## 1. 模块总览

| 组件 | 职责 |
| --- | --- |
| `compat/ManifestSerde` | 清单解析与**兼容契约**：缺失字段稳定默认；未知字段/不支持选项明确拒绝 |
| `engine/DependencyResolver` | 依赖拓扑排序；**循环依赖**报出环上节点；前置未完成不跳过 |
| `engine/DriftDetector` | **漂移检测**：实际 `STABLE` 且版本等于期望版本才算收敛 |
| `engine/QuotaManager` | **有界**并发槽 + **有界** FIFO 队列，超额排队或拒绝 |
| `lease/LeaseManager` | 每目标**租约 + 单调 fencing token**，过期可安全接管、防双写 |
| `service/SimulatedInfrastructure` | 本地模拟构建/灰度/健康判定/推广/回滚，按 `flowId` 幂等去重 |
| `engine/StageExecutor` | 单阶段执行：**有界重试**、超时区分、灰度逐步健康判定 |
| `service/PolicyGate` | 受保护环境**推进门禁**（角色 + 审批，只读不改状态） |
| `service/FreezeEvaluator` / `TimeSource` | **变更冻结窗口**按天判定（左闭右开、可跨午夜，时钟可测） |
| `service/PauseControl` | 灰度**人工暂停**的进程内协作点（放量边界握手） |
| `engine/VersionHistory` | 从事件 WAL/快照**重建版本历史**，判定版本是否发布过/稳定过 |
| `service/DeliveryService` | 编排核心，所有触发入口收敛到同一条 `reconcile` 路径 |
| `store/StateStore` | 本地持久化：清单/实际状态/流程快照 + 只追加事件 WAL，fencing 校验 |
| `service/RecoveryService` | **重启恢复**：接管过期租约、清理半发布、不重复已完成阶段 |
| `service/ConvergenceScheduler` | 周期性声明式收敛循环（默认关闭） |
| `web/DeliveryController` | HTTP 触发入口 |

---

## 2. 期望状态清单（声明式）

```json
{
  "apiVersion": "gitops.local/v1",
  "kind": "DeliveryManifest",
  "targets": {
    "pay": {
      "desiredVersion": "v2",
      "environment": "prod",
      "dependsOn": ["pay-db"],
      "timeoutSeconds": 60,
      "maxAttempts": 3,
      "canary": {
        "enabled": true,
        "steps": [10, 50, 100],
        "minHealthyRatio": 0.8,
        "maxRolloutSeconds": 60
      },
      "protection": {
        "protectedEnv": true,
        "requiredRoles": ["release-manager"],
        "requireApproval": true,
        "approvedBy": []
      },
      "freezeWindow": { "start": "22:00", "end": "06:00" }
    },
    "pay-db": { "desiredVersion": "v5" }
  }
}
```

### 兼容契约（默认语义确定且稳定）

* **新增字段缺失** → 使用稳定默认值：
  `environment=default`、`canary.enabled=false`（不灰度，直接稳定发布）、
  `maxAttempts=3`、`timeoutSeconds=60`、灰度 `steps=[10,50,100]`、
  `minHealthyRatio=0.8`。旧清单可被新版本直接读取。
* **未知/拼写错误字段** → `UNKNOWN_FIELD_REJECTED` 明确拒绝，**绝不静默忽略**。
* **不支持的 `apiVersion`/`kind`** → `API_VERSION_UNSUPPORTED` / `UNSUPPORTED_OPTION`。
* 灰度步长必须严格递增且落在 `(0,100]`，否则 `UNSUPPORTED_OPTION`。
* `freezeWindow`（按天重复的变更冻结窗口）：`start`/`end` 为 24 小时制 `HH:mm`（本地时区，
  精确到分钟）。格式无法解析、只写一端 → `MANIFEST_INVALID`；`start == end`（语义歧义）
  → `UNSUPPORTED_OPTION`，均在**应用清单时**明确拒绝，不静默忽略、不悄悄降级。
  旧清单不带该字段 → 默认该目标**永不冻结**。
* 历史事件/快照回放使用宽容 mapper（缺字段取默认、忽略未知字段）。

---

## 3. 状态流转规则

### 3.1 目标实际相位（`TargetPhase`）

```
ABSENT ──BUILD 成功──▶ BUILT ──部署灰度──▶ CANARY(percent) ──健康达标/100%──▶ STABLE
                                  │
                                  └─健康不达标/失败/超时──▶ 自动回滚 ──▶ ROLLED_BACK(=上一个稳定版本)
```

* **收敛的唯一定义**：相位 `STABLE` 且 `stableVersion == desiredVersion`。
  灰度中/构建后/失败/回滚态一律视为**漂移**，会被下一轮收敛继续驱动。
* **重复收敛无副作用**：已收敛时直接返回 `ALREADY_CONVERGED`，不建流程、不占租约、
  不执行任何阶段。
* **回滚后无半发布残留**：`canaryVersion` 清空、`canaryPercent=0`，
  稳定指针回到上一个稳定版本；无上一个稳定版本则 `NO_STABLE_VERSION`（标记 FAILED）。
* 版本链可追溯：`stableVersion` / `previousStableVersion` 与事件 WAL 记录每次变更。

### 3.2 流程状态（`FlowStatus`）

`PENDING → RUNNING → SUCCEEDED`
失败分支严格区分：`FAILED`（阶段失败/重试耗尽）、`TIMED_OUT`（超过 deadline）、
`CANCELLED`（显式取消）、`ROLLED_BACK`（健康不达标等触发自动回滚）、
`REJECTED`（门禁/依赖/额度/租约等前置拒绝）。另有 `WAITING_QUOTA`、`WAITING_DEPS`
用于排队/前置等待，`PAUSED` 表示灰度人工暂停（非终结）。**被中断的流程绝不判定为成功。**

### 3.2.1 变更冻结窗口（`freezeWindow`，按天重复）

* **时间语义（左闭右开 `[start, end)`）**：以运行环境本地时区判定，精确到分钟。
  落在 `start` 这一分钟即视为冻结，到 `end` 这一分钟**立即自动恢复**，无需任何额外操作。
* **跨午夜**：`start > end`（如 `22:00`-`06:00`）表示
  `[start, 24:00) ∪ [00:00, end)`；`start == end` 非法，清单应用即拒绝。
* **冻结只防“变更”，不防“恢复”**：冻结时段内，所有常规推进——人工 `reconcile`、
  周期收敛、暂停后的 `resume`——一律得到独立原因码 `FREEZE_WINDOW_BLOCKED`，
  拒绝**不改任何已发布版本状态、不占资源**；而紧急回滚（`rollback`、`rollback-to`）
  与 `cancel` **照常执行**。
* 判定点位于漂移检测、依赖检查、门禁之后；对**已经在跑**的流程不产生影响
  （它是在非冻结时段获准开始的，且冻结不会回滚已放量进度）。

### 3.2.2 灰度人工暂停与恢复（`PAUSED`）

```
RUNNING ──pause 请求，在下一个放量边界停住──▶ PAUSED ──resume──▶ RUNNING（从暂停步骤续跑）
                  │
                  └─暂停/恢复在冻结窗口内：恢复被 FREEZE_WINDOW_BLOCKED 挡下，暂停状态不变
```

* 暂停是**协作式**的：执行器只在**放量边界**（灰度步骤之间、推广之前）停住，
  因此暂停必然停在一个已持久化的确定放量百分比上；既不继续放量，也不判成功/失败。
* 进度记录为流程快照里的 `canaryStepIndex`（已成功放量到的步骤下标，从 0 开始）。
  恢复只执行其后的步骤：**已放的量不重放，版本不会多发一次**；`DEPLOY_CANARY`
  只有在全部放量步骤走完后才算完成，暂停在中途不会让恢复跳过剩余放量。
  失败或没做完的步骤下标不推进，重试/恢复必须真实重做。
* **额度语义**：暂停一生效立即释放并发槽，挂起目标不会长期白占额度；暂停状态
  与进度持久化，**进程重启后仍是 `PAUSED`**（不接管、不回滚），恢复入口照常可用。
* 恢复时额度满 → `QUOTA_QUEUED` 按有界 FIFO **排队**（不直接失败/拒绝）；槽位释放时
  自动提升等待者并就地续跑；若轮到额度时撞上冻结窗口，保持 `PAUSED` 并重新入队。
  无论怎样暂停/恢复，同时真正在推进的目标数从不超过 `delivery.max-concurrency`。
* **幂等与可区分拒绝**：对已 `PAUSED` 流程重复暂停 → 幂等 `FLOW_PAUSED`（无副作用）；
  无活动流程暂停 → `PAUSE_NOT_RUNNING`；对非暂停流程恢复 → `RESUME_NOT_PAUSED`，
  两者明确区分。排队中的暂停流程被再次触发等价于恢复意图。

### 3.2.3 回退到指定历史版本

* `rollback-to?version=<v>` 把目标直接退回到**曾经在该目标稳定发布过**的任意历史版本
  （不必是“上一个稳定版本”）。它本身是一次可追溯的版本变更：独立流程快照
  （`kind=ROLLBACK`、`rollbackToVersion`、`actor`）+ 事件 `TARGET_ROLLBACK_DONE`
  携带 `fromVersion → toVersion`。
* 三类拒绝严格区分：没有可回滚版本仍是 `NO_STABLE_VERSION`；指定版本从未发布过 →
  `VERSION_NOT_PUBLISHED`；发布过但从未稳定（例如只走到灰度就失败）→
  `VERSION_NEVER_STABLE`。
* 目标存在进行中流程（含暂停）→ `ACTIVE_FLOW_CONFLICT`：不允许回退与推进同时发生；
  请先 `cancel` 或等其结束。回退不清空半发布残留之外的任何东西：完成后
  `canaryVersion=null`、`canaryPercent=0`。
* 回退**不受冻结窗口限制**（恢复语义）。回退后若清单期望版本更高，下一轮收敛
  按原漂移语义重新推进。
* 版本历史由 `VersionHistory` 从**事件 WAL + 实际状态快照**重建：老事件缺少
  `fromVersion/toVersion` 等新字段时宽容读取、不报错、不丢历史。

### 3.3 一次收敛的判定顺序（每个拒绝都有可区分原因码）

1. **漂移检测**：已收敛 → `ALREADY_CONVERGED`（无动作）。
2. **前置依赖**：任一依赖未收敛 → `PREREQUISITE_NOT_READY`；图有环 →
   `CIRCULAR_DEPENDENCY`（含环上节点，清单不会落库）。
3. **推进门禁**（只读）：角色不足 → `POLICY_UNAUTHORIZED`；缺审批 →
   `POLICY_APPROVAL_REQUIRED`。拒绝不改任何版本状态、不占资源。
4. **变更冻结窗口**（只读）：窗口内常规推进 → `FREEZE_WINDOW_BLOCKED`
   （紧急回滚/取消不走此判定）。
5. **并发合并**：同目标已有活动流程 → `DUPLICATE_TRIGGER_COALESCED`；
   活动流程为 `PAUSED` 且未显式恢复 → `FLOW_PAUSED`。
6. **额度**：有槽则执行；槽满入有界队列 → `QUOTA_QUEUED`；队列也满 →
   `QUOTA_REJECTED`（不会无界占用/增长）。
7. **租约**：获取目标租约与 fencing token；被活跃持有者占用 →
   `LEASE_HELD_BY_OTHER`（明确拒绝，不死锁）。
8. **执行阶段**：`BUILD → DEPLOY_CANARY → PROMOTE_STABLE`，每步写 WAL 事件；
   放量边界收到暂停请求 → `PAUSED`（释放槽位，进度持久化）。
9. 灰度健康率 `< minHealthyRatio` → `HEALTH_CHECK_FAILED` → 自动回滚。
10. 成功 → `SUCCEEDED`。

---

## 4. 幂等、并发安全与租约接管

* **阶段幂等**：模拟基础设施以 `(target, flowId, stage)` 去重，同一流程的同一阶段
  真正只执行一次；有界重试、重复触发、接管都不会重复执行。
* **同目标并发**：多个收敛循环同时触发同一目标时，只有一个建立流程执行，
  其余得到 `DUPLICATE_TRIGGER_COALESCED` / `LEASE_HELD_BY_OTHER`，结论一致。
* **租约 + fencing token**：
  * 每个目标同一时刻只有一个活跃持有者；
  * 每次获取/接管签发**严格更大**的 token，token 高水位持久化
    （`fencing-hwm.txt`），**跨重启单调不回退**；
  * 租约 TTL 过期后可被任意执行者**安全接管**（`LEASE_TAKEN_OVER`）；
  * 被接管的旧持有者再写入会被 `FENCING_TOKEN_STALE` 拒绝，杜绝双写与状态跳变。

---

## 5. 门禁与额度策略

### 门禁（`delivery` 清单内 `protection`）

* 仅 `protectedEnv=true` 时生效；非受保护环境一律放行。
* `requiredRoles`：调用方角色（HTTP 头 `X-Actor-Roles`，逗号分隔）必须命中其一，
  否则 `POLICY_UNAUTHORIZED`。
* `requireApproval=true`：必须显式 `approved=true`（或 actor 在 `approvedBy` 名单），
  否则 `POLICY_APPROVAL_REQUIRED`。
* 周期调度器以 `scheduler/system` 身份运行，只推进非受保护目标；
  受保护目标必须走带角色与审批的显式入口。

### 额度（`application.properties`）

| 配置 | 默认 | 含义 |
| --- | --- | --- |
| `delivery.max-concurrency` | 2 | 同时推进的目标数上限 |
| `delivery.max-queue` | 10 | 等待队列长度上限（有界） |
| `delivery.lease-ttl-ms` | 10000 | 目标租约 TTL |
| `delivery.scheduler-enabled` | false | 是否开启周期收敛 |
| `delivery.reconcile-interval-ms` | 5000 | 周期收敛间隔 |
| `delivery.data-dir` | target/delivery-data | 本地状态目录 |
| `delivery.executor-id` | executor-local | 执行者标识前缀 |

槽满 → 排队（`QUOTA_QUEUED`）；槽与队列都满 → 拒绝（`QUOTA_REJECTED`）。
槽位释放时按 FIFO 提升一个等待目标。

暂停的目标立即释放槽位（只保留 `PAUSED` 快照，不占租约），不会出现一个挂起目标
长期耗光额度的情况；恢复时若没有空槽则进入同一有界队列，轮到时自动续跑，
因此任意时刻真正在推进的目标数都不超过 `delivery.max-concurrency`。

---

## 6. 失败 / 超时 / 取消 / 回滚

* **失败**：阶段异常按 `maxAttempts` **有界重试**（重试幂等）；耗尽 →
  `RETRIES_EXHAUSTED` 并自动回滚，不残留占用。
* **超时**：每次尝试与灰度步前检查流程 deadline；超时 → `STAGE_TIMEOUT`，
  与失败、取消严格区分。
* **取消**：`POST /api/delivery/targets/{name}/cancel`，仅对活动流程生效，
  释放租约与额度，状态 `CANCELLED`。
* **自动回滚**：灰度健康不达标 / 阶段失败 / 超时 → 回到上一个稳定版本，
  清空灰度，原因码保留（`HEALTH_CHECK_FAILED` / `STAGE_FAILED` / `STAGE_TIMEOUT`）。
* **手动回滚**：`POST /api/delivery/targets/{name}/rollback` → `ROLLBACK_DONE`。
* **指定历史版本回退**：`POST /api/delivery/targets/{name}/rollback-to?version=<v>`
  → `TARGET_ROLLBACK_DONE`；版本从未发布 → `VERSION_NOT_PUBLISHED`，
  从未稳定 → `VERSION_NEVER_STABLE`，有进行中流程 → `ACTIVE_FLOW_CONFLICT`。
  与手动回滚一样，冻结窗口内照常允许。
* **人工暂停/恢复**：`POST .../pause`、`POST .../resume`（后者接受
  `X-Actor-Roles` 与 `approved`）。暂停立即释放并发槽；恢复从 `canaryStepIndex`
  续跑，额度满时排队、冻结窗口内被挡（`FREEZE_WINDOW_BLOCKED`）。

---

## 7. 重启恢复

启动时（或显式调用 `RecoveryService.recover()`）：

1. 以持久化 token 高水位 + 磁盘流程最大 token 校正租约序列；
2. 对每个未终结流程**接管过期租约**（新 token，事件 `LEASE_TAKEN_OVER`）；
3. 已完成阶段记录在 `completedStages`，恢复后**不重复推进**；
4. 灰度/构建等半发布相位回滚到上一个稳定版本（`ROLLBACK_DONE`），不留残留；
5. 超过 deadline 的标记 `TIMED_OUT`；其余中断流程标记失败
   （`RECOVERED_AFTER_RESTART` / `INTERRUPTED_NOT_SUCCESS`），交下一轮收敛重新驱动；
6. **人工暂停（`PAUSED`）不按中断处理**：重启后保持暂停状态与灰度进度，
   不接管租约、不回滚、不释放“正在推进”的语义（它本就不持槽），显式 `resume` 才续跑；
7. 事件 WAL 只追加，关键事件不丢失；状态快照原子写。

---

## 8. 审计事件（每次状态迁移记录输入与判定依据）

事件顺序写入 `events.log`（单行 JSON），字段包含：
`seq`（全局递增，重启不回退）、`timestamp`、`flowId`、`target`、`actor`、
`fromState`、`toState`、`stage`、`reason`（可区分原因码）、`detail`、
`inputVersion`、`fromVersion`、`toVersion`（版本变更的来源/目标版本，旧事件缺省宽容读取）、
`fencingToken`。
拒绝、回滚、接管都有独立原因码，可直接回答“为什么是这个结论”。

---

## 9. HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/delivery/manifests` | 应用期望状态清单（严格兼容校验） |
| `POST /api/delivery/targets/{name}/reconcile?approved=` | 触发单目标收敛（头 `X-Actor`、`X-Actor-Roles`） |
| `POST /api/delivery/reconcile-all?approved=` | 按依赖顺序收敛全部 |
| `POST /api/delivery/targets/{name}/rollback` | 手动回滚到上一个稳定版本 |
| `POST /api/delivery/targets/{name}/rollback-to?version=` | 回退到指定历史稳定版本（冻结窗口内也允许） |
| `POST /api/delivery/targets/{name}/pause` | 灰度人工暂停（停在放量边界，释放并发槽） |
| `POST /api/delivery/targets/{name}/resume?approved=` | 灰度恢复（头 `X-Actor`、`X-Actor-Roles`；额度满排队） |
| `POST /api/delivery/targets/{name}/cancel` | 取消活动流程 |
| `GET  /api/delivery/targets/{name}/events` | 查询该目标审计事件 |
| `GET  /api/delivery/state` | 查看清单、实际状态与全部流程 |

业务拒绝返回 `409`，响应体形如 `{"reason":"POLICY_UNAUTHORIZED","message":"..."}`。

---

## 10. 本地验证方法

```bash
# 编译 + 全量单元/场景测试（无需外部依赖）
mvn test

# 只跑某一类场景
mvn test -Dtest=CanaryRollbackTest        # 灰度健康不达标 -> 自动回滚
mvn test -Dtest=ConcurrencyTest           # 并发触发：阶段只执行一次
mvn test -Dtest=LeaseTakeoverTest         # 租约过期接管 + fencing 防双写
mvn test -Dtest=PolicyGateTest            # 越权/缺审批推进被可区分拒绝
mvn test -Dtest=DependencyAndQuotaTest    # 循环依赖/前置未完成/额度排队与拒绝
mvn test -Dtest=RestartRecoveryTest       # 重启恢复：接管 + 清理半发布 + 不重复
mvn test -Dtest=CompatibilityTest         # 旧清单默认值 / 未知字段拒绝
mvn test -Dtest=FailureTimeoutCancelTest  # 失败重试 / 超时 / 取消区分
mvn test -Dtest=FreezeWindowTest          # 冻结窗口：推进被挡/回滚放行/边界/非法清单
mvn test -Dtest=PauseResumeTest           # 暂停释放额度/恢复不重放/排队/重启/失败重做
mvn test -Dtest=RollbackHistoryTest       # 指定历史版本回退成功与三类拒绝/旧数据重建

# 启动服务（可用 curl 走一遍 API）
mvn spring-boot:run
```

测试覆盖（共 41 个用例、14 个测试类）：并发触发、灰度回滚、越权推进、循环依赖、额度上限、
租约接管 fencing、有界重试、超时/取消区分、清单兼容、重启恢复，以及本轮新增的
**变更冻结窗口**（窗口内常规推进被挡而紧急回滚照常、左闭右开边界、非法窗口拒绝）、
**灰度暂停/恢复**（暂停释放并发槽、恢复接着走不重放不多发、额度满排队、重启后仍暂停、
失败步骤不被当作完成而真实重做）、**指定历史版本回退**（成功可追溯、从未发布/从未稳定/
进行中冲突三类可区分拒绝、旧事件与旧快照兼容重建），且每个结论都断言了**可区分的原因码**。
