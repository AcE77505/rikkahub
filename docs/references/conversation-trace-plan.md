# 对话 Trace 功能计划（跨会话请求审计）

> 状态：**计划，未开始实现**。本文只记录设计与取舍，落地时按第 7 节的顺序推进。

## 1. 目标

在 A 会话里快速查看 B 会话的每一次模型调用：请求侧（system prompt 全文、工具列表与 schema、
完整消息列表、原始 wire body）与响应侧（输出文本、工具调用、usage、状态）。用于调试、
问题定位与对话质量评估。

与「日志页」的区别：日志页是运维用的 HTTP 流水（内存环形 100 条、无会话归属、无响应体）；
本功能是按会话组织的结构化记录，可持久化、可跨会话查询。

## 2. 调研结论（外部项目怎么做）

| 项目 | 能看 system prompt | 能看工具 schema | 记流式分片 | 本地持久化 | 脱敏 |
|---|---|---|---|---|---|
| LibreChat Trace Viewer | 是 | 是 | 否（只记 TTFT） | 否（读 Langfuse） | 部署开关 + 截断 |
| Cherry Studio Trace | 是 | 是 | 是（tee 整段，512KB 上限） | 是（本地 JSONL，权限 0700） | `redaction.ts` |
| DeepSeek Harness session-inspector | 是 | 是 | 是（逐 chunk 子行） | 是（session log） | 无（仅 loopback） |
| Langfuse / LangSmith / Helicone | 是 | 是 | 否（只到 TTFT） | 服务端 | 有 mask |
| Open WebUI / LobeChat / Chatbox | 未查到本地查看器 | — | — | — | — |

可借鉴的三个要点：

1. **LibreChat**：记录层直接存 `prompt.messages` + `prompt.tools[]`，前端切「对话视图 / 原始
   JSON」；预算算法只保 system + 最新 N 条并记 `omitted` 计数，避免长上下文撑爆单条记录。
2. **Cherry Studio**：HTTP 层用 `response.body.tee()` 旁路累积响应原文，并明确「采集不得影响
   真实请求」；落盘用临时文件 + rename，文件权限 0700。
3. **DeepSeek Harness**：把流式分片作为子行展示，按 `turn/start–end`、`step/start–end` 分组，
   是逐分片留存的做法。

来源链接见附录。

## 3. 代码现状

- `app/.../data/ai/RequestLoggingInterceptor.kt`：记录 url/method/headers/requestBody/status/
  duration/error，**不含响应体**，只脱敏 `Proxy-Authorization`。
- `common/.../android/Logging.kt`：`LogEntry.RequestLog` 存内存环形缓冲（`MAX_RECENT_LOGS = 100`），
  不持久化，**没有 conversationId**。
- `app/.../ui/pages/log/LogPage.kt`：入口 `Screen.Log`，可导出 JSON。
- 可复用的现有资产：
  - `StepRequest(messages, params)`（`ChatRequestFactory.buildStep`）是「模型实际看到的
    system prompt + tools + 消息」的**唯一权威快照点**，埋点放这里可避免事后重建带来的偏差。
  - `Tool(name, description, parameters: () -> InputSchema?, systemPrompt, ...)`：工具 schema 与
    system prompt 在请求组装时已可完整取得。
  - `TextGenerationParams(model, temperature, topP, maxTokens, tools, reasoningLevel,
    customHeaders, customBody, sessionId)`：参数快照直接可取。
  - `StreamChunk`、`SseEvent`、`StreamChunkDecoder`：与传输解耦，可旁路录制。
  - `ai/src/test/resources/stream-traces/`：已有的「录制 SSE → 语义快照」fixture，可参考格式。
- 开关基础：`Settings.developerMode`（默认 `false`）已存在。

## 4. 数据模型

三层，对齐 LibreChat：

### 4.1 TraceTurn（一次「用户问 → 多步工具 → 最终答」）

```
turnId, conversationId, turnIndex,
assistantMessageId, nodeIndex,
startedAt, endedAt, status(ok|error|running),
stepCount, toolCallCount, usage 汇总
```

### 4.2 TraceStep（一次模型调用）

请求侧：

```
stepId, turnId, conversationId, stepIndex, parentStepId?
modelId, modelSnapshot
params: temperature / topP / maxTokens / reasoningLevel / sessionId
systemPrompt: String          // 全文，不截断（受总预算约束）
tools: [{ name, description, inputSchema(JSON) }]
messages: [UIMessage 序列化]   // 受预算约束，见 5.2
rawBody: String?              // provider 实际发出的 wire JSON（受字节上限）
```

响应侧：

```
outputText, reasoningText
toolCalls: [{ name, args }]
usage: { prompt, completion, reasoning, cached }
finishReason, status(ok|error|running), errorMessage
completionStartTime (TTFT), durationMs
```

### 4.3 体积约束（重要）

- **消息预算**：只保 system + 最新 N 条，并记录 `omitted` 计数（LibreChat 的
  `SYSTEM_SHARE / MESSAGE_SHARE` 思路）。
- **图片剥离**：请求体可能含历史图片 base64。存 trace 前替换为
  `{ mimeType, byteSize, sha256 }`，不保留原始字节。
- **字节上限**：单条 step 的 `rawBody` 与整份 trace 文件都要有上限（参考 Cherry
  `MAX_BODY_BYTES = 512KB`、`MAX_TRACE_FILE_BYTES = 8MB`）。

## 5. 存储（本地 JSONL）

- 目录：`context.filesDir/traces/<conversationId>/`，文件名按 turn 分组，
  例如 `turn-0007.json`（一个 turn 一个文件，内含该 turn 的所有 step）。
- 写入：临时文件 + `rename` 原子落盘；在流结束/出错时 flush，避免阻塞 UI。
- 体积控制：整目录上限（默认 64MB），超出按最旧 turn 淘汰。
- 不入 Room：避免大 BLOB 影响会话读取（现有 `MessageNodeEntity.messages` 已是整段 JSON）。

## 6. 入口与 UI

- 新开 `Screen.Trace`，与 `Screen.Log` 分开。
- 聊天页溢出菜单进入；面板顶部有「会话选择器」（下拉选任意会话，含未在界面打开的），
  实现「A 里看 B」。
- 主视图：Turn 时间线 → 展开看 step 列表。
- Step 详情三 Tab：
  1. 请求：system prompt / tools（名字 + schema）/ messages / raw body
  2. 响应：结构化（文本、推理、工具调用）/ 原文
  3. 元信息：model、参数、usage、TTFT、耗时、状态
- 双视图开关：对话视图 ↔ 原始 JSON。
- 大文本默认折叠，超过阈值先显示截断预览。

## 7. 实施顺序

1. **模型与存储层**：`TraceTurn` / `TraceStep` 数据类 + JSONL 读写（含体积上限、原子写）。
2. **采集点**：在 `ChatRequestFactory.buildStep` 记录请求侧快照；在 agent 循环的响应收尾处
   记录响应侧。全程 try/catch 静默失败，绝不打断主链路。
3. **开关**：`Settings.developerMode && Settings.traceEnabled`，两者同时为真才记录。
4. **UI**：`Screen.Trace` + 会话选择器 + 三 Tab 详情。
5. **脱敏与导出**：脱敏 pass + 导出单条 turn。
6. （后续可选）逐 chunk 录制、原始 SSE 留存。

## 8. 隐私与脱敏

现有 `RequestLoggingInterceptor` 只屏蔽 `Proxy-Authorization`，不足。计划按 Cherry 的做法：

- Header 按键名子串屏蔽：`KEY / TOKEN / SECRET / AUTH / CREDENTIAL / PASS / COOKIE / SESSION`。
- URL query 与 userinfo 中的 `key=`、token 类参数替换为占位符。
- Body 内 `sk-*`、`Bearer <token>` 替换。
- 导出前再跑一次脱敏 pass。
- trace 文件位于 app 私有目录，不导出到公共目录前必须提示用户内容含 prompt 与工具参数。

## 9. 风险与注意点

| 风险 | 处理 |
|---|---|
| 采集影响主链路 | 全部 try/catch 静默；不用 OkHttp 拦截器缓冲流式响应 |
| 大请求体（图片 base64） | 剥离为 mimeType + 字节数 + sha256 |
| 长上下文撑爆单条记录 | system + 最新 N 条 + `omitted` 计数 |
| 记录膨胀 | 目录级体积上限 + 按最旧淘汰 |
| 敏感信息泄漏 | 复用并扩展脱敏规则，导出前二次脱敏 |
| 流式分片缺失 | 本期不做；后续可在 `StreamChunkDecoder` 层旁路 |

## 10. 本期不做

- 逐 chunk 的 `StreamChunk` 留存与原始 SSE 二级存储。
- 与 Langfuse / OTel 等外部平台对接。
- 对普通用户默认开启（仅开发者模式）。
- 把 trace 集成进现有日志页。

## 附录：调研来源

- Langfuse 数据模型：`https://langfuse.com/docs/observability/data-model`
- Langfuse Sessions：`https://langfuse.com/docs/tracing-features/sessions`
- Langfuse masking：`https://langfuse.com/docs/observability/features/masking`
- LangSmith 概念：`https://docs.langchain.com/langsmith/observability-concepts`
- Helicone Sessions：`https://docs.helicone.ai/features/sessions`
- OpenLLMetry 语义约定：`https://github.com/traceloop/openllmetry`
- LibreChat Trace 源码：`https://github.com/danny-avila/LibreChat/tree/main/client/src/components/Chat/Trace`
- Cherry Studio Trace 文档：`https://github.com/CherryHQ/cherry-studio`（`docs/.../developer-tools/trace.md`）
- DeepSeek Harness：`https://github.com/deepseek-ai/deepseek-harness`（`packages/experimental/inspector`、`packages/experimental/session-inspector`）
