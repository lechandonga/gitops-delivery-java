# 本地持续交付编排与稳态收敛（gitops-delivery-java）

用**本地清单**描述期望状态（Desired State），用**本地模拟的执行/部署平台 + 事件日志**描述实际状态
（Actual State），由统一的收敛引擎驱动二者一致。发布、灰度、回滚在
`api / scheduler / event / recovery / rollback` 等触发入口下走**同一条编排路径**，
因此结论一致、可解释、可重放、可恢复。

- 期望状态：`DeliveryManifest`（本地 JSON 清单，严格校验、未知字段拒绝）
- 实际状态：`StateRepository`（由追加式事件日志 `events.jsonl` 重放得到）+ `InMemoryLocalPlatform`（模拟部署/健康的物理承载）
- 编排内核：`ReconciliationEngine`（DAG 编排 + 漂移检测 + 门禁 + 额度 + 执行）

---

## 1. 状态流转规则

### 1.1 阶段状态机（StageStatus）

```
                ┌──────────────────────────────────────────────┐
                │                                              │
   PENDING ──► RUNNING ──► CANARY_RUNNING ──► SUCCEEDED        │
                │   │          │                                │
                │   │          └─健康不达标─► ROLLED_BACK       │
                │   ├─部署失败(有界重试)─► RUNNING ... ─► FAILED │
                │   ├─超时──────────────► TIMED_OUT             │
                │   └─取消──────────────► CANCELLED             │
                │                                              │
                └─ 失败/回滚后相对期望仍漂移，下次收敛继续推进 ──┘
```

- **PENDING**：清单新增阶段的初始状态（缺失字段按稳定默认语义补齐）。
- **RUNNING**：已获得目标租约并开始某次尝试（事件 `STAGE_STARTED` 记录 attempt）。
- **CANARY_RUNNING**：灰度每放一档权重记录一次 `STAGE_CANARY_STEP`（含 weight）。
- **SUCCEEDED**：版本 100% 放量且健康达标；此时该版本成为“上一个稳定版本”。
- **ROLLED_BACK**：灰度健康不达标或重试耗尽时，自动回滚到**上一个稳定版本**，物理上以 100% 切回，不留半发布状态。
  - 回滚后相对“期望版本”仍然是**漂移**，下一轮收敛会再次尝试推进（不会被当成已收敛而静默）。
- **FAILED / TIMED_OUT / CANCELLED**：三类终态原因严格区分，**中断/超时的流程绝不判定为成功**。

### 1.2 收敛循环（所有触发入口共用）

`converge(trigger)` 对清单按 DAG 拓扑序逐阶段判定：

1. **幂等判定**：阶段已 `SUCCEEDED` 且承载版本 == 期望版本 → 记录 `CONVERGE_SKIPPED_IDEMPOTENT`，不做任何部署动作。重复收敛无副作用。
2. **漂移检测**：物理实际版本 ≠ 期望版本，或阶段非 SUCCEEDED → 标记 drift。
3. **门禁判定**：入口授权 + 受保护环境审批（见 §2）。
4. **前置依赖**：前置必须 `SUCCEEDED` 且版本匹配；否则以 `DEP_PREREQUISITE_NOT_DONE` 拒绝（不静默跳过、不死锁）。
5. **额度判定**：获取并发槽位（有界排队，超时/队列满 → `CAP_QUEUE_FULL`），失败不残留占用。
6. **执行**：`StageExecutor` 内部再做**目标租约互斥 + 幂等 + 灰度/健康/回滚 + 有界重试**，最终释放槽位与租约。

> 环依赖在**提交清单时**即被 `DEP_CYCLE` 拒绝（异常消息带环路径），清单不会落盘，已发布版本不受影响。

### 1.3 幂等与并发安全

- 同一目标由 **Lease（TTL）+ fencing token** 互斥：同一时刻只有一个 owner 可执行。
- 多个收敛循环/重复触发并发时，未抢到租约的一方得到 `LEASE_CONTENDED`，**同一阶段不会被执行两次**。
- 租约过期后允许他人**安全接管**：接管使全局 fence token +1，旧 owner 的 token 立即失效；
  所有物理部署都携带 token，过期 token 被平台拒绝（防双写、防状态跳变）。
- 续租按周期进行；`release` 校验 owner+token，旧 owner 无法抹掉新持有者的租约。

### 1.4 灰度与自动回滚

- 灰度按 `weights`（严格递增、最大 100）逐档放量，每档后按 `healthThreshold` 做健康判定。
- 任一档不达标 → 记录 `ROLLBACK_STARTED`，以 100% 回滚到 `lastStableVersion`，健康确认后记录 `ROLLBACK_SUCCEEDED`。
- 若历史上从无稳定版本，则无法回滚，以 `ROLLBACK_NO_STABLE_VERSION` 明确失败（绝不静默留在半发布权重）。
- 版本变更全程有事件（started / canary_step / succeeded / rollback_started / rollback_succeeded），**可追溯**。

### 1.5 失败、超时、取消

| 情形 | 终态 | 原因码 | 是否重试 |
|---|---|---|---|
| 部署/健康失败 | FAILED | `EXEC_FAILED`（或 `CANARY_UNHEALTHY`） | 有界，最多 `maxAttempts` 次 |
| 超过 `executionTimeoutMillis` | TIMED_OUT | `EXEC_TIMEOUT` | 不重试（时间预算已耗尽） |
| 收敛/灰度窗口被取消 | CANCELLED | `EXEC_CANCELLED` | 不重试 |
| 重试次数耗尽 | FAILED | `RETRIES_EXHAUSTED` | ——（有稳定版则先回滚） |

每次尝试都有独立 `STAGE_STARTED(attempt=n)`，重试**有界且幂等**；失败后槽位/租约一定释放，不残留占用。

### 1.6 重启恢复

- 事件日志（JSONL）逐条追加并刷盘；`leases.json` 每次变更落盘。
- 启动时 `RecoveryService.recoverOnStartup()`：
  1. 重放全部事件重建状态投影（已完成阶段保持 SUCCEEDED/ROLLED_BACK，**不重复推进**）；
  2. 重新绑定清单期望态；
  3. 崩溃残留且**已过期**的租约由 recovery owner 接管，记录 `RECOVERY`（原因 `LEASE_TAKEN_OVER_AFTER_EXPIRY`），随后释放；未过期租约保持尊重。
- 启动后可自动再收敛一次（`delivery.auto-reconcile-on-startup`）。

---

## 2. 门禁与额度策略

### 2.1 推进门禁（GateKeeper，纯判定、零副作用）

- **入口授权**：仅接受 `api / scheduler / event / recovery / rollback / test`；未知入口 →
  `GATE_UNAUTHORIZED_TRIGGER`（请求级 422，根本不进入逐阶段处理）。
- **受保护环境**：清单 `requireApproval=true` 时，必须先 `POST /api/approve?env=...`，
  否则逐阶段拒绝 `GATE_PROTECTED_NO_APPROVAL`。
- **拒绝不改版本**：任何门禁拒绝都只写 `GATE_REJECTED` 事件，不触发部署，已发布版本状态不变。

### 2.2 兼容/选项门禁（提交清单时）

- 仅支持 `schemaVersion = delivery.gitops/v1`；其它版本 → `GATE_UNSUPPORTED_OPTION`。
- JSON 中**任何未知字段** → `GATE_UNSUPPORTED_OPTION`（明确拒绝，绝不静默忽略）。
- 非法灰度权重/阈值、非法 maxAttempts/timeout → `GATE_UNSUPPORTED_OPTION` 或 `GATE_MANIFEST_INVALID`。

缺失字段的**确定且稳定**默认语义：

| 字段 | 缺省值 |
|---|---|
| `schemaVersion` | `delivery.gitops/v1` |
| `protectedEnv` / `requireApproval` | `false` |
| `stage.dependsOn` | `[]` |
| `stage.canary` | 无灰度（直接 100%） |
| `stage.executionTimeoutMillis` | `30000` |
| `stage.maxAttempts` | `3` |
| `canary.stepWaitMillis` | `1000` |
| `canary.healthThreshold` | `0.95` |

### 2.3 并发与排队额度（CapacityController）

- `delivery.max-concurrency` 个**公平**执行槽位（FIFO）。
- 等待者数量受 `delivery.max-queue` 约束：入队前检查，超出立即 `CAP_QUEUE_FULL`，**队列不会无限增长、线程不会无限等待**。
- 等待超过 `delivery.capacity-wait-millis` 同样视为获取失败并回退排队计数；成功执行后槽位必释放。

### 2.4 原因码速查（拒绝/回滚/接管可区分）

| 原因码 | 含义 |
|---|---|
| `DEP_CYCLE` | 阶段依赖存在环（消息含环路径） |
| `DEP_PREREQUISITE_NOT_DONE` | 前置阶段未成功或版本不匹配 |
| `GATE_PROTECTED_NO_APPROVAL` | 受保护环境未审批 |
| `GATE_UNAUTHORIZED_TRIGGER` | 未知/未授权触发入口 |
| `GATE_UNSUPPORTED_OPTION` | 未知字段 / 不支持的 schema 或选项 |
| `GATE_MANIFEST_INVALID` | 清单结构非法（缺字段、重复 id、依赖不存在） |
| `CAP_QUEUE_FULL` | 并发/排队额度耗尽 |
| `LEASE_CONTENDED` / `LEASE_STALE_TOKEN` | 租约被持有 / token 已失效 |
| `LEASE_TAKEN_OVER_AFTER_EXPIRY` | 过期租约被安全接管 |
| `IDEMPOTENT_ALREADY_DONE` | 已收敛，幂等跳过 |
| `CANARY_UNHEALTHY` / `ROLLBACK_NO_STABLE_VERSION` | 灰度不健康 / 无稳定版可回 |
| `EXEC_FAILED` / `EXEC_TIMEOUT` / `EXEC_CANCELLED` | 失败 / 超时 / 取消 |
| `RETRIES_EXHAUSTED` | 有界重试耗尽 |

---

## 3. HTTP 接口（本地触发入口）

| 方法与路径 | 说明 |
|---|---|
| `POST /api/manifest?trigger=&approved=` | 提交期望清单（原始 JSON，严格校验），随后立即收敛一次 |
| `POST /api/converge?trigger=` | 显式触发收敛（可传 scheduler/event/recovery 等入口） |
| `POST /api/approve?env=&trigger=` | 授予受保护环境推进审批 |
| `POST /api/stages/{id}/cancel?trigger=` | 取消阶段 |
| `GET /api/state` | 阶段/环境实际状态、租约与额度观测 |
| `GET /api/events` | 全量事件流（每次迁移的输入与判定依据） |

拒绝返回 HTTP `422`，体为 `{ "reasonCode": "...", "message": "..." }`，与系统错误 `500` 区分。

---

## 4. 本地验证方法

### 4.1 构建与测试

```bash
mvn clean test
```

覆盖场景（共 30 个用例，全部位于 `src/test/.../tests`）：

- `DependencyGraphTest`：拓扑序、环检测（可区分原因 + 环路径）、未知依赖、前置缺失。
- `ManifestCompatibilityTest`：缺失字段默认值、未知字段拒绝、不支持 schema、非法/合法灰度。
- `LeaseStoreTest`：互斥、过期接管使旧 token 失效、续租身份校验。
- `ReconcileBasicTest`：按序发布、**重复收敛幂等无副作用**、越权入口不改版本。
- `GateDependencyFailureTest`：**受保护环境未审批拒绝/审批后通过**、前置未完成级联拒绝、**有界重试且不判成功**。
- `CanaryRollbackTest`：**灰度不健康自动回滚到上一稳定版本、无半发布残留、版本可追溯**；健康灰度全量发布。
- `ConcurrencyCapacityCancelTest`：**8 线程并发收敛每阶段仅执行一次**、额度溢出有界拒绝且槽位释放、**取消与失败/超时区分**。
- `TimeoutRecoveryTest`：**超时区分且不判成功**、**重启重放恢复且不重复推进**、**崩溃过期租约安全接管**。
- `DeliveryApiTest`：端到端验证未知字段 422、受保护门禁拒绝→审批→成功、越权入口 422。

### 4.2 手动 curl 演示

```bash
mvn spring-boot:run

# 提交普通清单（提交即收敛）
curl -s -X POST localhost:8080/api/manifest \
  -H 'Content-Type: application/json' --data @examples/manifest.json

# 受保护环境：先被拒绝（GATE_PROTECTED_NO_APPROVAL）
curl -s -X POST localhost:8080/api/manifest \
  -H 'Content-Type: application/json' --data @examples/manifest-protected.json
# 审批后再收敛
curl -s -X POST 'localhost:8080/api/approve?env=prod'
curl -s -X POST localhost:8080/api/converge

# 观测实际状态与全部判定事件
curl -s localhost:8080/api/state | jq .
curl -s localhost:8080/api/events | jq .
```

### 4.3 落地产物（delivery.data-dir，默认 `./target/delivery-data`）

- `events.jsonl`：追加式事件日志（重启恢复的唯一事实来源）。
- `leases.json`：租约与全局 fencing token。
- `manifest.json`：最近一次期望清单。

删除该目录相当于全新环境；保留它再重启即可验证事件重放与租约接管。

---

## 5. 主要代码地图

| 包 | 职责 |
|---|---|
| `model` | 期望清单 `DeliveryManifest/ManifestStage/CanarySpec` 与实际状态 `StageState/EnvironmentState` |
| `event` | 不可变 `DeliveryEvent`、`EventType`、事件日志接口 |
| `store` | `FileEventLog`（JSONL）、`StateRepository`（事件投影）、`LeaseStore`（租约+fencing）、`ManifestStore`（严格解析/默认值） |
| `infra` | `LocalPlatform` / `InMemoryLocalPlatform`：模拟部署、健康曲线、故障/取消注入、token 防双写 |
| `engine` | `ReconciliationEngine`（收敛主编排）、`DependencyGraph`（DAG/环）、`GateKeeper`（门禁）、`CapacityController`（额度）、`StageExecutor`（执行/灰度/回滚/重试/超时/取消）、`RecoveryService`（重启恢复）、`ReasonCode` |
| `api` / `config` | REST 入口、全局错误映射、Spring 装配与启动恢复 Runner |
