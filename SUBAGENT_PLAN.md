# RikkaHub 子代理（Subagent）方案

> 状态：设计已确认，待实现
> 日期：2026-10-31
> 目标：让角色扮演助手可以把与角色无关的代码工作派发给一个独立的代码专家子代理，子代理完成后把结果交回角色，由角色用自己的风格回复用户。

---

## 1. 背景与目标

### 1.1 使用场景

- 主助手是一个角色扮演角色（例如猫娘、助手人格），有自己的系统提示和说话风格；
- 遇到代码修改、调试、运行测试这类与角色扮演无关的工作时，主助手把任务派发给一个独立的「代码专家」子代理；
- 子代理在**干净上下文**中工作，不继承角色的系统提示和对话历史；
- 子代理完成后只回传最终报告，主助手再按自己的角色风格回复用户。

### 1.2 设计目标

1. 子代理与主对话上下文隔离，代码中间过程不污染角色对话；
2. 子代理可以配置独立的模型、系统提示、工具范围和权限；
3. 子代理默认只读，写文件和执行命令需要在配置中显式开启；
4. 子代理结果可持久化、可查看完整报告、取消后能看到已完成的部分；
5. 不引入「子代理助手」概念，子代理不出现在助手列表，也不能单独开聊天；
6. 不影响现有技能（Skill）系统和主 Agent 的工具审批逻辑。

### 1.3 非目标（首版不做）

- 子代理作为独立会话出现在会话列表；
- 子代理递归派发子代理（仅保留深度上限防护）；
- 并行舰队（fleet / parallel_tasks）与依赖图；
- 子代理之间共享写租约；
- 子代理续跑（continue_from）与 steering（task_id）；
- 跨进程/崩溃后的运行恢复（只做启动时 stale running 清理）。

---

## 2. 调研结论

### 2.1 已有 RikkaHub fork 实现

| Fork | 实现方式 | 主要问题 |
| --- | --- | --- |
| `ruitong719/Rikkahub-Next` | 子代理定义存 Settings；每个子代理一个工具；在内存里跑 `GenerationLoop`，不建子会话 | 用字符串匹配判断运行状态；子代理继承主对话历史；依赖其 `Tool.terminal` 扩展；没有证据校验和写租约 |
| `AAAelina/rikkahub-agent-ProMax` | 子代理 = 新建隐藏 conversation，走 `ChatService` 完整管线 | 依赖其 fork 新增的 `submitUserMessageTracked` / `CommandOutcome` / `HeadlessConversations` / `AgentRunRepository`，与当前 `ConversationSession` 单一权威模型冲突 |
| `wuyhong715/rikkahub-agent-pure` | 同 ProMax 路线，额外做工具面冻结、专家库、cron/Telegram | 体量最大，子代理只是其中一部分，直接摘取会带进大量无关依赖 |

三个 fork 可能互相参考，均未完整覆盖持久化、取消语义、完成协议、工具边界等组合，**不直接照抄**。

### 2.2 Reasonix Studio 参考

官方仓库：`esengine/DeepSeek-Reasonix`，`studio` 分支，子代理核心在 `internal/runtime/delegation/`。

可借鉴的契约：

- `task` 工具 + 可选 `profile` 参数，profile 名不出现在工具 schema 和系统提示中，保持 prompt cache 稳定；
- 子代理定义复用 Skill 文件（`runAs: subagent`），正文就是完整 system prompt；
- 工具白名单是「profile 上限 ∩ 调用参数」，调用只能收窄，不能扩大；
- `read-only: true` 强制只读工具集；
- `complete_subtask` 终局工具，要求结构化报告和证据；
- 结果返回有界预览 + `Subagent reference: sa_...`，`read_subagent_result` 按 UTF-8 字节分页读取；
- 进度事件（状态/推理/文本预览）不进入父上下文；
- 写路径租约（write claim），并行写路径重叠时 preflight 失败；
- `max_subagent_depth` 防递归；
- 子代理结果若提到「用户已批准/请确认」，附加 host-decision boundary 警告；
- transcript + meta 持久化，取消保存 partial，失败不提供可读结果；
- 模型/effort 优先级：持久化配置 > 调用参数 > frontmatter > 全局子代理默认 > 父模型；
- 会话级并发上限（默认 6）和并行写上限（默认 3），超限排队。

RikkaHub 不照搬其 Skill 文件存储方式（见 2.3），但沿用其执行语义和持久化模型。

### 2.3 为什么不复用 Skill 文件

已评估「子代理 = `runAs: subagent` 的 SKILL.md」方案，最终不采用：

- 技能列表会混入可执行专家，概念混杂；
- `Assistant.enabledSkills` 与子代理启用关系冲突，需要额外过滤；
- 现有 `SkillDetailPage` 是纯文本编辑，子代理字段多，手写 YAML 容易出错；
- `SkillValidator` 只校验 name/description，子代理的模型、工具、步数、超时等字段缺少校验；
- 删除/重命名/导入需要额外清理引用；
- **安全风险**：`/skills` 是模型可写区域，模型可以自己写一个可写子代理 profile，绕过只读和派发审批；
- 与上游技能系统合并时冲突面大。

因此子代理使用独立的 `Settings.subAgents` 配置，与 Skill 系统完全分开。

---

## 3. 已确认的设计决策

| 决策 | 选择 |
| --- | --- |
| 配置位置 | 独立 `Settings.subAgents`，设置 → 工具页新增「子代理管理」入口 |
| 绑定方式 | 定义全局共享；每个助手通过 `Assistant.enabledSubAgents` 决定能派发哪些子代理 |
| 工具名称 | `task` |
| 默认权限 | 只读；写文件和 shell 在子代理配置里显式开启 |
| 持久化 | 完整按 Reasonix 方案：transcript + meta + `sa_...` 引用 + 分页读取 + 取消 partial + 启动清理 |
| 子代理与助手关系 | 子代理不是 Assistant；通过 `Assistant.enabledSubAgents` 启用 |
| 与 Skill 系统关系 | 完全独立，不修改 `SkillManager` / `SkillMetadata` |

---

## 4. 概念模型

```
角色助手（Assistant，主 Agent）
  ├─ 系统提示 / 角色风格 / 对话历史
  └─ 启用的子代理（enabledSubAgents）
        └─ 代码专家（SubAgent，不是 Assistant）
              ├─ 独立系统提示
              ├─ 独立模型 / effort
              ├─ 工具白名单 / 只读策略
              └─ 独立上下文，只回传最终报告
```

- 主 Agent 通过 `task` 工具派发；
- 子代理运行在独立的 Agent 循环中；
- 子代理的中间推理、工具调用、工具输出**不进入**主对话上下文；
- 主 Agent 只收到最终报告（或 `sa_...` 引用 + 有界预览）；
- 最终回复由主 Agent 按角色风格生成。

---

## 5. 数据模型

### 5.1 SubAgent

新建 `app/src/main/java/me/rerere/rikkahub/data/model/SubAgent.kt`：

```kotlin
@Serializable
data class SubAgent(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    /** null = 继承全局子代理模型，再回落到父轮模型 */
    val modelId: Uuid? = null,
    /** 思考深度；null = 继承父轮 */
    val reasoningLevel: ReasoningLevel? = null,
    /** 默认只读；写文件和 shell 需要显式放开 */
    val readOnly: Boolean = true,
    /** 工具名白名单；空集 = 仅使用只读默认集合 */
    val allowedTools: Set<String> = emptySet(),
    /** 最大工具循环步数 */
    val maxSteps: Int = 12,
    /** 超时（秒） */
    val timeoutSeconds: Int = 120,
    /** 派发时是否需要用户审批 */
    val requiresApproval: Boolean = true,
)

enum class SubAgentToolPolicy {
    READ_ONLY,
    WORKSPACE,
    ALL,
}
```

首版可以只用 `readOnly + allowedTools`，`SubAgentToolPolicy` 作为后续扩展。

### 5.2 Settings

`Settings.kt` 新增：

```kotlin
val subAgents: List<SubAgent> = emptyList(),
val subAgentModelId: Uuid? = null,          // 可选：统一子代理模型
val subAgentMaxConcurrency: Int = 3,        // 每个会话的并发上限
```

`SettingsFields.kt` 新增：

```kotlin
json(SUB_AGENTS, Settings::subAgents) { copy(subAgents = it) },
SettingsField(SUBAGENT_MODEL, Settings::subAgentModelId, ...),
plain(SUBAGENT_MAX_CONCURRENCY, Settings::subAgentMaxConcurrency) { copy(subAgentMaxConcurrency = it) },
```

`SettingsNormalization.kt` 负责：

- 去重（按 id）；
- 名称去空白、限制长度；
- `maxSteps` 限制在 1..64；
- `timeoutSeconds` 限制在 10..1800；
- 清理无效模型引用（可选）；
- 不补内置子代理，避免用户无法删除。

### 5.3 Assistant

`Assistant.kt` 新增：

```kotlin
val enabledSubAgents: Set<Uuid> = emptySet(),
```

旧数据反序列化时默认为空，不需要数据库迁移。

### 5.4 运行记录

新建 `SubAgentRun`（内存 + 持久化 meta）：

```kotlin
data class SubAgentRun(
    val ref: String,                    // sa_<uuid>
    val parentConversationId: Uuid,
    val parentAssistantId: Uuid,
    val subAgentId: Uuid,
    val label: String,
    val task: String,
    val modelId: Uuid?,
    val status: SubAgentStatus,         // RUNNING/COMPLETED/CANCELLED/FAILED/INTERRUPTED
    val result: String? = null,
    val error: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val steps: Int = 0,
    val usage: Usage? = null,
)
```

---

## 6. 工具设计

### 6.1 派发工具 `task`

参数：

```json
{
  "agent": "Code Expert",
  "prompt": "修复 app 模块的构建错误，运行 ./gradlew assembleDebug 验证",
  "description": "修复构建错误",
  "tools": ["read_file", "grep", "write_file", "shell"],
  "model": "deepseek-coder",
  "max_steps": 12,
  "timeout_seconds": 120
}
```

必填：`prompt`。

规则：

- `agent` 只接受当前助手 `enabledSubAgents` 里的名字；省略时使用第一个启用的子代理；
- `tools` 只能收窄子代理配置的白名单，不能扩大；
- `model` 只能覆盖为已配置的模型；未知模型直接报错；
- `max_steps` / `timeout_seconds` 受子代理配置上限约束；
- `needsApproval = { subAgent.requiresApproval }`，默认 true；
- 工具描述里不枚举子代理名称，名称在运行时解析，保持 schema 稳定。

### 6.2 结果读取工具 `read_subagent_result`

```json
{
  "ref": "sa_<uuid>",
  "offset_bytes": 0,
  "limit_bytes": 12288
}
```

- 只读工具；
- 默认 12 KiB，最大 24 KiB；
- 按 UTF-8 字节分页；
- `COMPLETED` 可读完整结果；
- `CANCELLED` 可读 partial，并明确标注「这不是最终结果」；
- `RUNNING` / `FAILED` / `INTERRUPTED` 拒绝；
- 只允许读取当前会话谱系、同一工作区的结果。

### 6.3 可选工具（后续）

- `list_subagents`：列出当前会话的子代理运行记录；
- `cancel_subagent`：取消一个运行中的子代理；
- `fleet` / `parallel_tasks`：并行派发（需要写租约和依赖图）。

---

## 7. 执行流程

```
用户消息
  ↓
主 AgentLoop（角色助手）
  ↓ 模型调用 task(agent="Code Expert", prompt="...")
SubAgentRunner
  ├─ 解析 SubAgent 配置
  ├─ 解析模型：配置 > 调用参数 > 全局子代理模型 > 父轮模型
  ├─ 构造工具集：父轮工具 ∩ 子代理白名单 ∩ 调用参数
  │    ├─ 只读模式：剔除 write_file / edit_file / shell
  │    ├─ 剔除 task / read_subagent_result / ask_user / 设备 UI 工具
  │    └─ needsApproval = { false }
  ├─ 创建 SubAgentRun + 持久化 meta（RUNNING）
  ├─ 构造子请求：独立 system prompt + task 文本
  ├─ runAgent(AgentRequest, AgentHooks)
  │    └─ 子 AgentLoop（多步工具循环）
  ├─ 收集最终报告
  ├─ 保存 transcript + meta（COMPLETED / CANCELLED / FAILED）
  └─ 返回有界预览 + sa_... 引用
  ↓
主 AgentLoop 收到结果
  ↓
主模型按角色风格生成最终回复
```

### 7.1 上下文规则

- 子代理默认只看 `prompt`，不看主对话历史；
- 可选 `include_recent_turns`（后续）把最近 N 轮作为纯文本背景附加，不作为真实消息重放；
- 子代理不应用主助手的 regex、OCR、通知类 transformer；
- 可以应用占位符替换（时间、模型名等），但必须显式列出。

### 7.1.1 工具注入条件

- `task` 工具只在**当前助手至少启用了一个子代理**时注入；
- 工具描述不枚举子代理名称，名称在运行时从 `enabledSubAgents` 解析，保持 schema 稳定；
- 子代理定义是全局共享的，但一个助手是否能派发、能派发哪些，完全由 `Assistant.enabledSubAgents` 决定；
- 没有启用任何子代理的助手，模型看不到 `task` 工具。

### 7.2 审批规则

- `task` 工具本身按 `requiresApproval` 决定是否审批；
- 子代理内部工具 `needsApproval = false`，因为子循环没有审批 UI；
- 子代理报告如果提到「用户已批准 / 请确认 / 请选择」，在结果后附 host-decision boundary 警告，防止主 Agent 把子代理的话当成真实用户决策。

### 7.3 递归与深度

- 子代理工具集中永远不包含 `task`、`read_subagent_result`；
- 运行上下文记录 `subagentDepth`；
- 默认 `maxSubagentDepth = 1`（首版不嵌套），后续可配置到 2；
- 超深直接返回错误 JSON。

### 7.4 超时、取消、并发

- 超时：`withTimeoutOrNull(timeoutSeconds)`；
- 取消：子代理在主工具调用内同步运行，父协程取消会自然传播；
- 并发：`SubAgentRegistry` 按会话限制 `subAgentMaxConcurrency`（默认 3）；
- 超过并发上限时排队等待，而不是直接失败；
- 停止主生成时，级联取消该会话所有运行中的子代理。

---

## 8. 持久化设计（Reasonix 方案）

### 8.1 目录结构

```
filesDir/subagents/
  sa_<uuid>.json          # transcript：List<UIMessage> 的序列化
  sa_<uuid>.meta.json     # 状态、父会话、工作区、模型、effort、工具范围、时间
```

### 8.2 meta 字段

```json
{
  "ref": "sa_...",
  "parentConversationId": "...",
  "parentAssistantId": "...",
  "subAgentId": "...",
  "workspaceId": "...",
  "modelId": "...",
  "reasoningLevel": "high",
  "allowedTools": ["read_file", "grep"],
  "status": "completed",
  "terminalReason": null,
  "startedAt": "...",
  "updatedAt": "...",
  "steps": 7,
  "usage": { "inputTokens": 0, "outputTokens": 0 }
}
```

### 8.3 生命周期

1. 创建 `SubAgentRun`，写 `RUNNING` meta；
2. 运行期间持续写 transcript；
3. 完成：保存 transcript + `COMPLETED` meta；
4. 取消：保存 transcript + `CANCELLED` meta；
5. 失败：保存 transcript + `FAILED` meta，但不提供可读结果；
6. 启动清理：父会话不在活跃列表的 `RUNNING` 标为 `INTERRUPTED`；
7. 删除会话：删除该父会话的所有子代理产物。

### 8.4 结果读取

- `read_subagent_result` 按 UTF-8 字节分页；
- 读取前校验 ref 属于当前会话谱系和同一工作区；
- 每个 ref 使用进程内锁，避免读取与终态保存竞争；
- 大结果不一次性进入父上下文。

### 8.5 进度

- 子代理状态、推理预览、文本预览通过 `AgentEvent` 发给 UI；
- 不写入 transcript 的进度事件，不进入父上下文；
- 重启后以 transcript 为准。

---

## 9. UI 设计

### 9.0 定义与启用的分工

子代理有两层，界面上必须分开：

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 定义（全局） | 设置 → 工具 → 子代理管理 | 新建/编辑/删除子代理：名称、提示词、模型、权限、工具、步数、超时 |
| 启用（按助手） | 助手详情 → 子代理 | 选择这个助手可以派发哪些子代理 |

- 定义是全局共享的：同一个「Code Expert」可以被多个角色助手复用，不用重复配置；
- 启用是助手级的：角色 A 可以启用 Code Expert，角色 B 可以不启用；
- 设置 → 工具页是全局工具配置区，只负责「定义」，不负责「某个助手是否能用」；
- 不要把子代理塞进现有 `localTools` 的 4 个工具开关，也不要和「工具审批」的 4 个开关混排。

### 9.1 设置 → 工具 → 子代理管理

在 `SettingToolsPage` 现有的 `CardGroup` 列表里新增一项：

```
[工作区]
  工作区
  工作区提示词

[工具开关]
  读取文件 / 写入文件 / 编辑文件 / 执行命令

[命令超时管理]

[工具审批]

[子代理]
  子代理管理   >  已配置 2 个
```

点「子代理管理」进入 `SettingSubAgentsPage`：

```
[子代理]

Code Expert
只读 · 2 个工具 · deepseek-coder

Researcher
只读 · 3 个工具 · 继承父模型

[+ 新建子代理]
```

编辑页字段：

- 名称
- 描述
- 系统提示词
- 模型（可继承）
- 思考深度（可继承）
- 只读开关
- 允许的工具（多选）
- 最大步数
- 超时
- 派发时是否需要审批

> 这里只管理定义。某个助手是否能用某个子代理，不在这里设置。

### 9.2 助手编辑页

新增「子代理」入口：

```
子代理    >  已启用 1 个
技能      >  已启用 5 个
```

点进去勾选当前助手可派发的子代理。

- 列表来源是 `Settings.subAgents` 的全部定义；
- 勾选结果写入 `Assistant.enabledSubAgents`；
- 没有勾选任何子代理时，该助手的模型看不到 `task` 工具；
- 删除一个子代理定义时，要同步清理所有助手里的引用。

### 9.3 聊天页

`task` 工具卡片：

```
┌──────────────────────────────────┐
│ 🛠 Code Expert          运行中 18s │
│ 修复 app 模块的构建错误            │
│ ▸ 最近活动：读取 build.gradle.kts  │
└──────────────────────────────────┘
```

完成后：

```
┌──────────────────────────────────┐
│ ✅ Code Expert          完成 42s  │
│ 已修复 3 处编译错误，构建通过       │
│ [查看完整报告]                    │
└──────────────────────────────────┘
```

- 卡片显示子代理名称、状态、耗时、最近活动；
- 展开显示有界预览；
- 「查看完整报告」调用 `read_subagent_result`；
- 子代理的完整推理和工具输出不进入主对话。

### 9.4 统计

- 新增 `UsageSource.SUBAGENT`；
- 统计页可以按来源区分主对话和子代理的用量。

---

## 10. 文件级改动清单

### 新增

| 文件 | 内容 |
| --- | --- |
| `data/model/SubAgent.kt` | `SubAgent`、`SubAgentStatus`、`SubAgentRun`、校验 |
| `data/ai/subagent/SubAgentRunner.kt` | 执行器：解析配置、构造请求、`runAgent`、结果提取 |
| `data/ai/subagent/SubAgentRegistry.kt` | 内存运行记录、并发计数、取消 |
| `data/ai/subagent/SubAgentStore.kt` | transcript + meta 持久化、引用、分页读取、清理 |
| `data/ai/subagent/SubAgentPrompts.kt` | 系统提示、完成协议、结果编码 |
| `data/ai/subagent/SubAgentToolSurface.kt` | 工具交集、只读、递归剔除、host-decision boundary |
| `data/ai/tools/SubAgentTools.kt` | `task`、`read_subagent_result` 工具定义 |
| `ui/pages/setting/SettingSubAgentsPage.kt` | 子代理列表与编辑 |
| `ui/pages/assistant/detail/AssistantSubAgentsPage.kt` | 助手启用列表 |

### 修改

| 文件 | 改动 |
| --- | --- |
| `data/datastore/Settings.kt` | 新增 `subAgents`、`subAgentModelId`、`subAgentMaxConcurrency` |
| `data/datastore/SettingsFields.kt` | 新字段的存取 |
| `data/datastore/SettingsNormalization.kt` | 去重、范围校验 |
| `data/model/Assistant.kt` | 新增 `enabledSubAgents` |
| `data/ai/ChatRequestFactory.kt` | 新增子代理 `ChatTurnContext` / `createAgent` 重载 |
| `data/ai/tools/ChatToolFactory.kt` | 注入 `task` / `read_subagent_result` |
| `data/ai/UsageRecorder.kt` | 新增 `UsageSource.SUBAGENT` |
| `ai/src/main/java/me/rerere/ai/core/Tool.kt` | 可选增加 `terminal`（首版可不用） |
| `ai/src/main/java/me/rerere/ai/agent/AgentLoop.kt` | 可选支持 terminal 工具 |
| `service/ChatService.kt` | 停止生成时级联取消子代理 |
| `di/AppModule.kt` | 注册 Runner / Registry / Store |
| `RouteActivity.kt` | 新增 `Screen.SettingSubAgents`、`Screen.AssistantSubAgents` |
| `ui/pages/setting/SettingToolsPage.kt` | 新增「子代理」CardGroup，入口进入 `SettingSubAgentsPage` |
| `ui/pages/assistant/detail/*` | 子代理入口 |
| `ui/components/message/ChatMessageTools.kt` | 子代理卡片 |
| `app/src/main/res/values*/strings.xml` | 新增文案 |

---

## 11. 分阶段实施

### P1：核心执行（只读）

- `SubAgent` 模型 + Settings 字段 + Assistant 启用；
- `SubAgentRunner`：解析配置、工具交集、只读、超时、步数；
- `task` 工具注册到 `ChatToolFactory`；
- 结果直接作为工具输出，超长截断；
- 递归防护、错误 JSON；
- 基础单元测试。

验收：角色助手可以派发一个只读代码专家，专家返回摘要，角色用自己的风格回复。

### P2：持久化与完整结果

- `SubAgentStore`：transcript + meta + `sa_...` 引用；
- `read_subagent_result` 分页读取；
- 取消保存 partial；
- 启动清理 stale running；
- 删除会话清理；
- 用量统计。

验收：长报告可分页读取；取消后能查看已完成的过程；重启后记录仍在（running 标为 interrupted）。

### P3：配置与 UI

- `SettingSubAgentsPage` CRUD；
- 助手编辑页启用列表；
- 聊天卡片：状态、耗时、最近活动、查看完整报告；
- 内置示例（Code Expert / Researcher / Reviewer）。

验收：用户可以新建代码专家，启用后在聊天中派发；卡片正确显示状态和报告。

### P4：写权限与并发（已完成/明确不做）

- 写文件 / shell 的显式开启；
- 会话级并发队列；
- 并行派发（`fleet`，含 `depends_on`）；
- 写路径声明与重叠检查：不做，只在 `fleet` 工具描述里提醒；
- 后台运行与完成通知：不做。

验收：多个代码专家可并行工作；写冲突由调用方按工具描述自行避免；主生成停止时全部停止。

---

## 12. 测试计划

### 单元测试

- `SubAgentRequestValidator`：空任务、超时、步数、标签长度、未知模型；
- `SubAgentToolSurface`：只读剔除写工具、递归剔除 `task`、调用参数不能扩大白名单；
- `SubAgentPrompts`：系统提示拼接、完成协议、结果编码；
- `SubAgentStore`：引用、分页（UTF-8 边界）、取消 partial、删除、stale running 清理；
- `SubAgentRegistry`：并发上限、取消、状态迁移；
- `Settings` 序列化 roundtrip。

### 集成测试

- 用 fake Provider 模拟：模型先调用 `task`，再根据子代理结果生成最终回复；
- 子代理内部调用只读工具并返回报告；
- 父协程取消时子代理取消；
- 超时返回 `TIMED_OUT`。

### 手工验证

- 角色助手 + Code Expert：派发代码任务，角色风格不变；
- 关闭子代理配置后，`task` 工具不出现；
- 只读子代理尝试写文件时工具不存在；
- 长报告通过 `read_subagent_result` 分页读取。

---

## 13. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 子代理无人值守写文件/执行命令 | 默认只读；写/命令必须显式开启；派发默认审批 |
| 递归派发 | 子代理工具集剔除 `task`；深度上限 |
| 上下文/成本膨胀 | 干净上下文；结果截断；步数/超时/并发上限 |
| 多子代理并发写冲突 | 首版串行；P4 引入写路径声明和 preflight |
| 子代理结果被误当成用户决策 | host-decision boundary 警告 |
| 大结果丢失 | P2 持久化 + 分页读取 |
| 取消后丢失过程 | 取消保存 partial transcript |
| 与上游合并冲突 | 新代码集中在 `subagent/` 包；对现有文件只做追加式修改 |
| 模型不支持工具调用 | 子代理退化为纯文本；派发前检查模型能力 |

---

## 14. 与现有系统的边界

- **不改 Skill 系统**：`SkillManager`、`SkillMetadata`、技能页保持原样；
- **不改主 Agent 的审批逻辑**：`task` 是普通工具，按 `requiresApproval` 决定；
- **不建子会话**：子代理不写入 `Conversation` 表，不出现在会话列表；
- **不改 `ConversationSession` 单一权威模型**：子代理 transcript 独立存储；
- **不改变主对话的上下文**：子代理只有一个工具调用 + 有界结果。

---

## 15. 后续可选扩展

- `fleet` / `parallel_tasks`：并行派发、依赖图、写租约；
- `continue_from`：续跑已完成/已取消的子代理；
- `task_id`：向运行中的子代理发送补充指令；
- `adopt_ref`：复用已完成的子代理结果；
- `complete_subtask` 证据校验：要求子代理引用实际命令/文件路径；
- 子代理进度预览（推理/文本流）；
- 子代理模型路由与失败重试。

---

## 16. 实现状态（2026-10-31）

### 已完成

- [x] `SubAgent` 模型、`Settings.subAgents`、`Assistant.enabledSubAgents`；
- [x] `task` 工具与子代理独立上下文执行；
- [x] `fleet` 并行派发（最多 8 个任务，共享会话并发上限；不声明写路径）；
- [x] `fleet` 任务 `id` / `depends_on`：依赖完成后才启动，依赖报告会附加到提示词；
- [x] `task` 的 `adopt_ref`：直接复用已有结果，不重新运行；
- [x] 默认流式预览：`task` / `fleet` 卡片显示阶段、耗时和最近活动；
- [x] `complete_subtask` 终局报告工具；
- [x] `continue_from`：从同一对话中已完成/已取消的子代理继续；
- [x] 默认只读工具集、递归防护、超时、步数上限；
- [x] `SubAgentStore`：transcript + meta + `sa_...` 引用；
- [x] `read_subagent_result` 按 UTF-8 字节分页读取；
- [x] 取消保存 partial、启动清理 stale running、删除会话清理；
- [x] 设置 → 工具 → 子代理管理（列表、编辑、删除、只读/审批/步数/超时/模型/思考深度/工具白名单）；
- [x] 子代理编辑改为全屏页面，复用助手的聊天模型选择（`ModelField` + `ModelListSheet`）和思考预算（`ReasoningButton`）；
- [x] 默认工具模板 `SubAgentToolDefaults`：未自定义时共享，修改时复制一份；模板本身可在「设置 → 工具 → 子代理 → 默认工具模板」里编辑；
- [x] 同一对话内批准过一次派发后，后续同一子代理不再审批（内存缓存，删除对话时清理）；是否记住由「记住本次对话的批准」开关控制；
- [x] 子代理温度：默认跟随助手温度，可单独开启并输入 0–2；
- [x] 全局子代理模型与并发上限设置；
- [x] 子代理运行记录页，可查看已完成/已取消结果并分页加载；
- [x] 子代理页面与工具卡片文案接入 `strings.xml`（中/英）；
- [x] 工具模板按读取/写入/执行/设备分组，并支持填写 MCP 等动态工具名；
- [x] 助手详情 → 子代理启用；
- [x] 聊天页 `task` 工具卡片（子代理名、状态、步数、引用）；
- [x] `UsageSource.SUBAGENT`；
- [x] 每会话子代理并发信号量（`subAgentMaxConcurrency`）；
- [x] 单元测试：`SubAgentNormalizationTest`、`SubAgentStoreTest`、`SettingsFieldsTest`。

### 明确不做（P4 收尾决定）

- `task_id` steering：不引入后台任务架构。
- 后台运行、完成通知、进程重启恢复：不做。
- 写路径租约：不做重叠检查；只在 `fleet` 工具描述里提醒不要并行写同一个文件。

> 按当前约定：`task` 不接通知/悬浮窗；流式预览默认开启，不单独提供开关。
