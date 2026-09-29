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
| `service/FreezeGate` + `engine/TimeSource` | **变更冻结窗口**判定（daily/interval，边界 `[start,end)`，时钟可替换） |
| `engine/VersionHistory` | 每目标**历史稳定版本目录**，可从旧事件/旧快照重建 |
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
      "freezeWindows": [
        { "type": "daily", "start": "22:00", "end": "06:00" },
        { "type": "interval", "start": "2026-10-01T09:00", "end": "2026-10-01T12:00" }
      }
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
* **变更冻结窗口 `freezeWindows` 缺失** → 默认空列表（旧清单不冻结）；字段存在时每个窗口
  必须合法，否则在应用清单阶段以 `FREEZE_WINDOW_INVALID` 明确拒绝，**不静默忽略、不降级**：
  `type` 仅支持 `daily`（按天重复）/ `interval`（指定区间）；`start`/`end` 必填；
  `daily` 时间为 `HH:mm`，`interval` 为 `yyyy-MM-dd'T'HH:mm[:ss]`（系统本地时区）；
  `interval` 的 end 必须晚于 start。
* 历史事件/快照回放使用宽容 mapper（缺字段取默认、忽略未知字段）。

### 冻结窗口时间边界（确定且唯一）

* 两种窗口统一采用 **【含起始时刻，不含结束时刻】** `[start, end)`：
  `now == start` 已冻结；`now == end` 已解冻，无需任何额外操作即可推进。
* `daily` 支持跨午夜（如 `22:00-06:00` = `[22:00,24:00) ∪ [00:00,06:00)`）；
  起止相同（如 `00:00-00:00`）表示全天冻结。
* 判定时间来自可替换的 `TimeSource`（默认系统时钟、系统默认时区），本地可确定性测试。
* **冻结只防“变更”不防“恢复”**：冻结时段内常规发布推进（周期收敛/人工触发走同一 reconcile
  路径）一律以 `FREEZE_WINDOW_BLOCKED` 拒绝（与权限/审批/依赖/额度原因码可区分），
  且拒绝不改任何已发布版本状态、不建流程、不占租约/额度；但**紧急回滚（含指定历史版本回退）
  与取消照常可执行**。窗口一过，下一次触发（人工或周期）自然推进。

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
用于排队/前置等待。灰度中途人工暂停为 `PAUSED`（停在当前放量进度，不判成功/失败）。
**被中断的流程绝不判定为成功。**

### 3.3 灰度人工暂停 / 恢复（`PAUSED`）

* **暂停** `POST /api/delivery/targets/{name}/pause`：
  只对正在推进的流程生效，在最近的灰度检查点（步前/步后）落地 `PAUSED`；
  已放出的灰度量保留，未放出的量绝不因踩刹车多放一次。
  暂停落地后**立即释放目标租约与并发额度**——挂起目标不白占槽位，不会把额度耗光。
* **恢复** `POST .../resume`：从暂停的灰度步继续（`canaryStepIndex` 记录已完成步，
  已放过的百分比**不重放**，PROMOTE 只发生一次）；恢复时重新获约（新 fencing token，
  仍单调），deadline 从恢复点重新起算（暂停时长不消耗超时预算）。
* **额度满时排队**：恢复请求进入有界 FIFO 队列（`FLOW_RESUME_QUEUED`），槽位释放后
  再调 resume（或周期 tick）即获槽续跑；绝不直接失败，且任何时刻真正推进的目标数
  不超过 `delivery.max-concurrency`。
* **幂等与可区分拒绝**：重复暂停 → `FLOW_ALREADY_PAUSED`；对无活动流程暂停 →
  `PAUSE_NOT_RUNNING`；对无暂停流程恢复 → `RESUME_NOT_PAUSED`（两者明确区分）。
* **重启可识别**：`PAUSED` 状态、灰度进度（`canaryStepIndex`/百分比）持久化；
  重启恢复**不接管、不回滚、不判超时失败**，原样保留暂停态，resume 入口继续可用。

### 3.4 一次收敛的判定顺序（每个拒绝都有可区分原因码）

1. **漂移检测**：已收敛 → `ALREADY_CONVERGED`（无动作）。
2. **前置依赖**：任一依赖未收敛 → `PREREQUISITE_NOT_READY`；图有环 →
   `CIRCULAR_DEPENDENCY`（含环上节点，清单不会落库）。
3. **推进门禁**（只读）：角色不足 → `POLICY_UNAUTHORIZED`；缺审批 →
   `POLICY_APPROVAL_REQUIRED`。拒绝不改任何版本状态、不占资源。
4. **变更冻结窗口**（只读）：命中 → `FREEZE_WINDOW_BLOCKED`（不改版本状态、不占资源；
   紧急回滚/取消不受此限）。
5. **并发合并**：同目标已有活动流程 → `DUPLICATE_TRIGGER_COALESCED`；
   已暂停且登记恢复（含排队等额度）的流程由此续跑。
6. **额度**：有槽则执行；槽满入有界队列 → `QUOTA_QUEUED`；队列也满 →
   `QUOTA_REJECTED`（不会无界占用/增长）。
7. **租约**：获取目标租约与 fencing token；被活跃持有者占用 →
   `LEASE_HELD_BY_OTHER`（明确拒绝，不死锁）。
8. **执行阶段**：`BUILD → DEPLOY_CANARY → PROMOTE_STABLE`，每步写 WAL 事件；
   灰度按 `steps` 逐步放量，暂停在检查点落地，恢复从 `canaryStepIndex` 续跑。
9. 灰度健康率 `< minHealthyRatio` → `HEALTH_CHECK_FAILED` → 自动回滚。
10. 成功 → `SUCCEEDED`，并把该版本登记进目标的历史稳定版本目录。

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
* **手动回滚（上一个稳定版本）**：
  `POST /api/delivery/targets/{name}/rollback` → `ROLLBACK_DONE`。
* **回退到指定历史版本**：
  `POST /api/delivery/targets/{name}/rollback/{version}` → `ROLLBACK_HISTORY_DONE`。
  * 指定版本**必须曾在该目标上稳定发布过**（以历史稳定版本目录为准）；从未发布过、
    或不是本目标发过的版本 → `TARGET_VERSION_UNKNOWN` 明确拒绝，与
    “没有可回滚版本”的 `NO_STABLE_VERSION` 区分；拒绝不改任何版本状态。
  * 回退本身是一次**可追溯的版本变更**：事件记录 `inputVersion`（回退前）→
    `targetVersion`（回退目标）与 `actor`（触发者），事后可查“谁在何时从哪版退到哪版”。
  * 目标存在进行中流程（含暂停中）→ `ROLLBACK_TARGET_BUSY` 确定拒绝，
    绝不与发布流程两边同时推进；请先取消或等待其结束。
  * 回退后清空灰度残留、相位 `ROLLED_BACK`；漂移收敛语义不变——若清单期望版本更高，
    下一轮收敛照常重新推到期望版本。回退动作在冻结窗口内仍可执行（恢复优先）。
* **历史版本重建**：成功发布时写入本地 `history/<target>.json`；首次启动（或历史文件
  缺失）时自动从旧事件 WAL 与旧快照全量重建。旧事件/旧快照采用宽容 mapper
  （缺字段取默认、忽略未知字段），老数据读入不报错、历史不丢。

---

## 7. 重启恢复

启动时（或显式调用 `RecoveryService.recover()`）：

1. 以持久化 token 高水位 + 磁盘流程最大 token 校正租约序列；
2. 对每个未终结流程**接管过期租约**（新 token，事件 `LEASE_TAKEN_OVER`）；
3. 已完成阶段记录在 `completedStages`，恢复后**不重复推进**；
4. 灰度/构建等半发布相位回滚到上一个稳定版本（`ROLLBACK_DONE`），不留残留；
5. **人工暂停（`PAUSED`）的流程原样保留**：不接管租约、不回滚灰度进度、不判超时失败，
   重启后仍是暂停态，`canaryStepIndex`/灰度百分比保留，resume 入口继续可用；
6. 超过 deadline 的标记 `TIMED_OUT`；其余中断流程标记失败
   （`RECOVERED_AFTER_RESTART` / `INTERRUPTED_NOT_SUCCESS`），交下一轮收敛重新驱动；
7. 事件 WAL 只追加，关键事件不丢失；状态快照原子写。

---

## 8. 审计事件（每次状态迁移记录输入与判定依据）

事件顺序写入 `events.log`（单行 JSON），字段包含：
`seq`（全局递增，重启不回退）、`timestamp`、`flowId`、`target`、`actor`、
`fromState`、`toState`、`stage`、`reason`（可区分原因码）、`detail`、
`inputVersion`、`targetVersion`（版本变更后的/回退目标版本）、`fencingToken`。
拒绝、回滚、接管都有独立原因码，可直接回答“为什么是这个结论”。

回退到历史版本时同一事件同时带 `inputVersion`（回退前）与 `targetVersion`（回退目标），
配合 `actor` 可完整回答“谁、在何时、把哪个目标从哪个版本退到了哪个版本”。

---

## 9. HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/delivery/manifests` | 应用期望状态清单（严格兼容校验） |
| `POST /api/delivery/targets/{name}/reconcile?approved=` | 触发单目标收敛（头 `X-Actor`、`X-Actor-Roles`） |
| `POST /api/delivery/reconcile-all?approved=` | 按依赖顺序收敛全部 |
| `POST /api/delivery/targets/{name}/rollback` | 手动回滚到上一个稳定版本 |
| `POST /api/delivery/targets/{name}/rollback/{version}` | 回退到指定的历史稳定版本 |
| `POST /api/delivery/targets/{name}/pause` | 暂停灰度中的流程（踩刹车，释放额度） |
| `POST /api/delivery/targets/{name}/resume` | 恢复暂停中的流程（从暂停的灰度步续跑，满额则排队） |
| `POST /api/delivery/targets/{name}/cancel` | 取消活动流程 |
| `GET  /api/delivery/targets/{name}/events` | 查询该目标审计事件 |
| `GET  /api/delivery/state` | 查看清单、实际状态、全部流程与每目标历史稳定版本 |

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
mvn test -Dtest=FreezeWindowTest          # 冻结拦截常规推进 / 回滚取消可用 / 边界 / 非法清单
mvn test -Dtest=PauseResumeTest           # 灰度暂停续跑不重放 / 释放额度 / 满额排队 / 重启保留
mvn test -Dtest=RollbackHistoryTest       # 指定历史版本回退成功/拒绝 / 老事件快照重建历史

# 启动服务（可用 curl 走一遍 API）
mvn spring-boot:run
```

冻结窗口 curl 示例（边界 [start,end)，冻结期内 rollback 仍可用）：

```bash
curl -s -XPOST localhost:8080/api/delivery/manifests -H 'Content-Type: application/json' -d '{
  "apiVersion":"gitops.local/v1","kind":"DeliveryManifest",
  "targets":{"pay":{"desiredVersion":"v3",
    "freezeWindows":[{"type":"daily","start":"00:00","end":"23:59"}]}}}'
curl -s -XPOST localhost:8080/api/delivery/targets/pay/reconcile   # 409 FREEZE_WINDOW_BLOCKED
curl -s -XPOST localhost:8080/api/delivery/targets/pay/rollback/v1 # 冻结期内回退照常执行
```

测试覆盖（共 36 个用例、14 个测试类）：并发触发、灰度回滚、越权推进、循环依赖、额度上限、
租约接管 fencing、有界重试、超时/取消区分、清单兼容、重启恢复，以及本轮新增的
**冻结窗口（拦截/边界/非法清单/旧清单默认）、灰度暂停恢复（不重放/释放额度/满额排队/
重启保留/失败动作真重放）、指定历史版本回退（成功/版本未知/目标忙/老数据重建）**，
且每个结论都断言了**可区分的原因码**。
