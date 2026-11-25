# 能力目录与按需工具发现（capability / tool search）调研

> 状态：**调研记录，未做实现**。与 `conversation-trace-plan.md` 并列，供后续设计「工具太多导致
> 提示词膨胀」问题的解决方案时参考。

## 1. Reasonix 的实现（本次直接读了源码）

用 `gh repo clone esengine/DeepSeek-Reasonix` 拉取，关键代码在：

- `internal/runtime/capability/{capability,catalog,semantic,ledger,audit}.go`
- `internal/session/control/{capability,composition_inventory}.go`

### 1.1 数据模型

统一目录 `Entry`，把「工具 / 技能 / MCP 服务器 / MCP 工具 / 工具来源」放进同一个模型：

```go
type Entry struct {
    ID, Kind, Name, Description, Source string
    Status        Status   // ready|configured|disabled|failed|stale
    ReadOnly, Destructive bool
    Cost          string
    AutoUse       AutoUse  // off|suggest|prefer|require
    Triggers, NegativeTriggers []string
    ToolName      string
    Aliases       []string
    ConnectSource, ConnectName string
    Requires      []string // 依赖的其他 capability
    Profiles      []string // economy|balanced|delivery
    AutoStart     bool     // MCP 的 auto_start
    FailureReason string   // host 证实的失败原因
}
```

要点：`Status` 与 `FailureReason` 由 **host 观察**得出，不是模型猜的；`Aliases` 让旧工具名仍然可路由。

### 1.2 目录构建（`catalog.go`）

`BuildCatalog(CatalogOptions)` 的输入包含：实时工具、技能、插件配置、**持久化的 MCP schema 缓存**
（`CachedTools` + `CacheKeyOK`）、已连接与失败状态、`ProxyTools`。

关键设计：**`auto_start=false` 的 MCP 服务器也能贡献具体的 `mcp-tool` 候选**，因为工具清单来自磁盘
缓存，不需要先连接。缓存 key 不匹配时标记 `stale` 而不是隐藏，避免「明明配置了却查不到」。

### 1.3 路由（`capability.go` 的 `routeCapabilities`）

1. 确定性路由：按 `AutoUse`（require/prefer）与 triggers 匹配。
2. 无命中时调用轻量模型做语义路由（`semantic.go`）：
   - 约束：最多 64 个候选、最多返回 3 条、超时 3s、结果缓存 5 分钟、max tokens 256。
   - **失败立即回退到确定性结果**，不阻塞主链路。
   - 路由自身的 token/成本/延迟记进 `RouterAudit`，避免「优化工具」反而变成隐藏开销。
3. 已有被调用的技能会从候选里剔除，避免重复建议。

### 1.4 注入形态（`RenderTransientBlock`）

只往 system prompt 前注入极小的一段，且**只列相关候选**：

```
<capability-route version="1">
Relevant capabilities for this turn:
- source:skills/pr prefer: the skill trigger matches...
- mcp-tool:mt/mt_file_get_access_policy prefer: the user named this MCP tool
- skills suggest: ... 
</capability-route>
```

每行格式是 `- <target> <policy>: <reason>`，必要时追加 `(status=stale)` 与下一步动作
（`connect_tool_source` 或 `use_capability(action="call", ...)`）。

### 1.5 记账与审计（`ledger.go` / `audit.go`）

- `Ledger`：**turn 级**，记录每个被路由候选的结局
  `pending|invoked|succeeded|failed|unavailable|declined`，以及失败原因、是否已发过重试提醒。
- `Audit`：**进程级、不持久化**的计数器，覆盖漏斗各环节（路由数、require/prefer/suggest 命中、
  缺失与恢复、技能调用失败、MCP 调用失败），外加语义路由自身的花费。
- 设计意图很明确：**route 决策不靠事后重新解析提示词**，而是以结构化对象传给 agent
  （`SeedCapabilityRoute`）。

## 2. 外部同类机制

| 实现 | 机制 | 关键点 | 是否保 prompt cache |
|---|---|---|---|
| Anthropic Tool Search Tool | `tool_search_tool_bm25/regex_20251119` + `defer_loading` | `defer_loading` 只控制「进不进 context」，**不控制是否随请求发送**；`tool_reference` 内联展开 | 是（deferred 排除在前缀外） |
| Anthropic Claude Code | 内置 `ToolSearch` + `ENABLE_TOOL_SEARCH=auto:N` | 默认开启；MCP 后台非阻塞连接，`alwaysLoad` 才阻塞 | 是 |
| OpenAI Responses | `{"type":"tool_search"}` + `defer_loading` + `namespace` | namespace 分组省的 token 远多于逐个 defer | 是（追加到末尾） |
| Open WebUI | `search_tools` + `call_tool` 双入口 | BM25 / embeddings / LLM decisions 三种引擎；按 schema 字节数超阈值才 defer | 否（自建 manifest） |
| LangChain | `ProviderToolSearchMiddleware` | 只转发 provider 原生能力；动态工具过滤会破坏前缀 | 否 |
| LlamaIndex / Semantic Kernel | 语义检索后只 advertise 命中项 | 客户端自建检索 | 否 |
| MetaMCP / MCPJungle | 网关侧聚合 + namespace 分组 | 连接由网关代理 | — |
| Cline / Roo / Zed / Continue | 全量注入 + 手动开关 | 无按需发现 | 否 |

**官方 token 数据**（可核查）：

- Anthropic：50+ MCP 工具约 72K tokens → tool search 下约 8.7K，**减少 85%**；上下文保留
  191,300 vs 122,800 tokens。
- OpenAI：36 个 MCP server 全开，置于 tool search 之后**总 token 减 47%**，精度不变。

## 3. 对 RikkaHub 的启示

### 3.1 现状（已用 gh + 本地代码核对）

- `ChatRequestFactory` 第 122/151/279 行把 `turn.tools` 整份塞进请求参数。
- `ChatToolFactory.createTools(...)` 每次组装全部工具：本地工具 + 工作区/SSH 工具 + 技能工具 +
  MCP 工具 + 子代理工具。
- `McpSessionRegistry.syncTools` 已把 `listTools()` 结果持久化到 `McpServerConfig.commonOptions.tools`
  ——**这正是做「影子工具」所需的前置条件**：不连接也能知道某服务器有哪些工具。
- 我们在上一轮提示词精简中已经看到：37 个技能 + 60+ MCP 工具的 schema 与描述是提示词的主体开销。

### 3.2 最小可行方案（不改 provider 协议）

RikkaHub 是客户端，不掌握 provider 的 `defer_loading`（那是 Anthropic/OpenAI 服务端能力）。可行路线
与 Open WebUI 一致：**客户端自建 meta-tool**。

1. **统一目录**：从 `TurnContext` 汇总 工具 / 技能 / MCP 服务器 / MCP 工具，形成 `Entry`
   （至少含 `id/kind/name/description/status/readOnly/destructive/autoUse/triggers/toolName`）。
2. **超阈值才启用**：按「schema 序列化后的字节数」决定哪些工具进入 deferred 集合，并在设置里提供
   `alwaysLoaded` 白名单（对齐 Open WebUI 的 `defer_threshold` + `always_loaded`）。
3. **两个入口工具**：
   - `search_tools(query, count)` → 返回匹配工具的完整定义；
   - `call_tool(calls=[{tool_name, params}])` → 批量调用，**每个调用保留各自的审批与结果**
     （Open WebUI 的 `split_tool_call_batches` 就是为了不破坏既有审批流）。
4. **注入一段极短的发现说明**，并明确要求「不要直接调用 deferred 工具」「未搜索前不要断言能力不可用」
   ——这两句是 Open WebUI 与 Claude Code 都写在提示词里的。
5. **MCP 懒连接**：用 `commonOptions.tools` 的持久化清单注册「影子工具」，真实调用前 `EnsureConnected`。
   第三方实现（`magicwubiao/go-magic` 的 `lazy.go`）印证了这条路径可行，且缓存只是「上次看到的清单」，
   连上后会刷新。

### 3.3 需要特别注意的坑

| 坑 | 说明 |
|---|---|
| prompt cache | RikkaHub 的 system prompt 里带动态内容本来就影响命中；把工具改成按需加载**能改善**这一点（前缀变小），但要避免把「本轮命中的工具」写进 system prompt 前缀 |
| 审批语义 | 批量调用不能绕过单工具的审批；必须把每个 call 拆回独立的工具调用再走审批 |
| 失败可见性 | 服务器连接失败要有 `status=failed` + `FailureReason`，否则模型会反复重试 |
| 语义路由成本 | 若引入 LLM 语义路由，必须像 Reasonix 一样计量它的 token 与花费，并设置超时与回退 |
| 别名与旧名 | RikkaHub 已有 `LEGACY_WORKSPACE_TOOL_ALIASES`、`SKILL_TOOL_ALIASES`，目录模型要能承载别名 |
| 子代理白名单 | 子代理的 `allowedTools` 依赖具体工具名，按需加载后要保证白名单仍能被解析 |

## 4. 与 trace 功能的关系

两者互补：

- **trace** 回答「这次请求到底发了什么」；
- **capability 目录**回答「这次请求为什么只发了这些工具」。

落地 trace 后，`TraceStep` 里记录的 `tools` 字段正好可以验证按需加载是否按预期工作（哪些被 defer、
哪些被 search 命中、search 的命中率如何）。

## 附录：可核查来源

- Reasonix 源码：`https://github.com/esengine/DeepSeek-Reasonix`（`internal/runtime/capability/`、`internal/session/control/capability.go`）
- Anthropic Tool Search Tool：`https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool`
- Anthropic Advanced Tool Use 博客：`https://www.anthropic.com/engineering/advanced-tool-use`
- Claude Code tool search：`https://code.claude.com/docs/en/agent-sdk/tool-search.md`
- OpenAI tool search 指南：`https://developers.openai.com/api/docs/guides/tools-tool-search`
- Azure Foundry 机制说明：`https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/tool-search`
- Open WebUI：`backend/open_webui/utils/tool_search.py`、`backend/open_webui/tools/builtin.py`（`search_tools`）
- LangChain：`libs/langchain_v1/langchain/agents/middleware/provider_tool_search.py`
- 第三方 MCP 懒连接实现：`https://github.com/magicwubiao/go-magic`（`internal/mcp/lazy.go`）

---

## 5. 动态工具实现方案（2026-11-24 决策）

### 5.1 RikkaHub 实际现状（更正第 3.1 节的表述）

用 `gh` 与本地代码核对后确认三点：

1. **AI 侧已经是全量注入**，不是「不知道有哪些工具」。`McpManager.getAllAvailableTools` 只按
   `server.commonOptions.enable` 与 `assistant.mcpServers` 过滤，**不看连接状态**；
   `ChatToolFactory` 把每个 `McpTool.inputSchema` 直接变成 `Tool.parameters`。
2. **持久化清单确实存在且可用**（`McpServerConfig.commonOptions.tools`，含 `inputSchema`），
   所以「不连接也能声明工具」这一行为已经成立——**缺的是「只发索引、按需展开」的机制**，
   而不是数据基础。
3. **连接状态没有进入提示词**。`McpStatus`（Idle/Connecting/Connected/Reconnecting/Error）
   只用于 UI；模型看到 `mcp__xxx__yyy` 会认为可用，实际调用时才报错。

### 5.2 借鉴来源（两个都要）

| 能力 | 借鉴 | 原因 |
|---|---|---|
| 动态工具开关 + `search_tools`/`call_tool` 双入口 | Open WebUI | 纯客户端实现，不需要 provider 的 `defer_loading` |
| 状态/失败原因进入目录、别名、turn 级记账 | Reasonix | `Entry` 模型把 `Status`/`FailureReason`/`Aliases` 统一管理 |
| 检索算法 | Open WebUI（扩展字段） | 已有 CJK bigram 分词与 IDF 归零兜底 |

**不要**照搬 Anthropic/OpenAI 的 `defer_loading`：那是服务端能力，客户端无法控制。

### 5.3 中文分词调研结论

这是 BM25 路线最关键的一环。核实到的现成做法：

| 方案 | 实现 | 依赖 | 评价 |
|---|---|---|---|
| **CJK 字符二元组** | Open WebUI `tokenize()`：`_CJK_RE` 命中连续 CJK 后按 `chunk[i:i+2]` 切 | 零 | **推荐**，无需词典 |
| jieba 分词 | eagle-eye `skill_retriever.py`、jieba-android | 词典 + 初始化开销 | 精度略高，但 Android 移植最后一次更新是 2018 年、169 星，维护风险大 |
| 纯 ASCII 正则 | VulnClaw `_TOKEN_RE = [a-z0-9]+` | 零 | **反例**：中文被整体丢弃，见 issue #251 |

Open WebUI 的完整实现（`backend/open_webui/utils/tool_search.py:229-245`）：

```python
_WORD_RE  = regex.compile(r'[\p{L}\p{N}\p{M}]+')
_CAMEL_RE = re.compile(r'(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])')
_CJK_RE   = re.compile(r'([\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uac00-\ud7af]+)')

for word in _WORD_RE.findall(NFKC(text)):
    for part in _CAMEL_RE.split(word):          # camelCase 拆分
        for chunk in _CJK_RE.split(part.casefold()):
            if _CJK_RE.fullmatch(chunk):
                tokens += [chunk[i:i+2] for i in range(len(chunk)-1)] or [chunk]   # 二元组
            elif chunk:
                tokens.append(chunk)
```

三个必须照做的细节：

1. **CJK 按二字切**：`提个bug` → `提个`、`个b`、`bug`；单字时回退为单字本身。
2. **camelCase 拆分**：`getAccessPolicy` → `get`、`Access`、`Policy`，否则工具名里的驼峰会漏检。
3. **IDF 归零兜底**：小语料里某词出现在半数以上文档时 BM25 的 IDF 为 0，Open WebUI 额外加
   `len(set(query_tokens) & set(doc_tokens))` 作为原始词重叠加分。**工具集只有几十个时这几乎必然发生**，
   不照抄会出现「明明有匹配却搜不到」。

**jieba 不推荐**：需要词典与 `initialize()`，APK 体积与首次加载延迟都要付；而且中文工具检索的
粒度是「关键词命中」，二元组已足够。若日后确有精度需求，可作为可选增强层，而不是唯一路径。

### 5.4 检索文档应包含的字段（比 Open WebUI 多）

Open WebUI 只索引 `name + description + 参数名`：

```python
def _document_text(name, spec):
    properties = (spec.get('parameters') or {}).get('properties') or {}
    return ' '.join([name, spec.get('description') or '', *properties.keys()])
```

RikkaHub 必须扩展，因为「在提示词内搜索」是明确需求：

| 字段 | 说明 |
|---|---|
| 工具名（含 `mcp__server__tool` 全名与去前缀名） | 支持精确名直返 |
| 工具描述（含 `toolDescriptionOverrides`） | 用户覆盖的版本优先 |
| **工具 systemPrompt（含 `toolSystemPromptOverrides`）** | 这是相对 Open WebUI 的关键扩展 |
| 参数名 | 参数级语义提示 |
| 技能 name + description | 技能路由 |
| 别名（`LEGACY_WORKSPACE_TOOL_ALIASES`、`SKILL_TOOL_ALIASES`） | 旧名仍可命中 |
| 状态与失败原因 | 命中后能提示「服务器未连接」而不是让模型瞎试 |

### 5.5 检索与调用设计

**检索引擎**：默认 BM25（本地、零成本、确定性强）；设置里可选开启「LLM 判定」作为增强。

LLM 判定若启用，必须照 Reasonix 的处理：

- 超时（3~30s 视规模）、结果缓存、失败立即回退 BM25；
- **单独计量 router 的 token 与花费**，避免优化工具加载反成隐性开销；
- 分批（Open WebUI 用 `DECISIONS_BATCH_SIZE = 400`）。

**入口工具**（已确定双入口）：

- `search_tools(query, count)` → 返回命中工具的完整定义（含 schema 与提示词摘要）
- `call_tool(calls=[{tool_name, params}])` → 批量调用，**必须拆回独立调用以保留单工具审批**
  （Open WebUI 的 `split_tool_call_batches` 就是为此）

提示词里要写死两句（Open WebUI 与 Claude Code 都有）：

- `Do not call deferred tools directly.`
- `Never tell the user a capability is unavailable without searching first.`

### 5.5.1 模型什么时候才会去搜索（关键缺口）

只靠上面两句是不够的。第二句只在「用户明确问某能力是否存在」时触发；而实际最常见的两种情形是：

- 用户描述含糊（「帮我看看这个」「这个方案怎么样」）；
- 用户根本没提工具需求。

这时模型手里只有 `search_tools` / `call_tool`，其余工具对它而言不存在，**它没有任何理由去搜索**。Open WebUI 同样有这个缺口，它只是靠 `search_tools` 自身的 description 被动暗示。

因此必须补三层主动暴露机制（缺一不可，可靠性从上到下递减）：

#### 第一层：每轮路由注入候选（Reasonix 式，最可靠）

在组装请求前，用**用户本轮消息**跑一次 BM25（或已启用的 LLM 判定），取 top 3~5 命中项，作为一小段
提示注入 system prompt：

```
<capability-route version="1">
Relevant capabilities for this turn:
- mcp-tool:mt/mt_file_search prefer: query mentions files and search
- skills suggest: research — the task asks for external facts
</capability-route>
```

要点：

- 每行 `- <id> <policy>: <reason>`，**总长控制在几百字符**，避免又变成提示词膨胀；
- 无命中时**不注入**（不能出现空壳块）；
- 这段是**每轮变化的**，会影响 prompt cache 前缀；因此应放在 system prompt 的**末尾**，让前面的稳定部分还能命中缓存（OpenAI 与 Anthropic 文档都强调「把变动内容追加到末尾」）；
- 注入的候选同时喂给 turn 级 ledger，供审计与结果回填。

这样即使模型不主动搜索，命中的工具也已经在它眼前——**兜底不依赖模型主动性**。

#### 第二层：告知「未列出的工具数量与领域」

让模型知道自己「有所不知」，才有搜索的动机：

```
<available_tools>
47 additional tools are not listed here. They cover workspace and SSH file
access, MCP servers, skills, and subagents. Call search_tools before planning
when the task might need them.
</available_tools>
```

要点：

- 只给**数量 + 领域分类**，不列具体名字（否则等于又把清单塞回去）；
- 数量与分类按本轮实际 deferred 集合动态生成；
- 这句要放在 system prompt 的**稳定区**（数量在会话内变化不大），避免破坏缓存。

#### 第三层：强化 `search_tools` 的 description

Open WebUI 的描述是「Discover available tools by capability」，过于被动。改为带明确触发条件：

```
Discover additional tools that are not listed in this session.
Use this when:
- the user asks for something that the current tools cannot do
- the task involves files, shell commands, external services, or MCP
- the request is vague and you need to know what is available before answering
Pass an exact tool name if you already know it; otherwise pass short keywords.
```

工具 description 是模型每轮都看得到的内容，比 system prompt 里的规则更贴近决策点。

#### 三层的关系

| 层 | 触发条件 | 是否依赖模型主动性 | 成本 |
|---|---|---|---|
| 路由注入 | 每轮自动 | 否 | 一次本地 BM25 |
| 数量与领域提示 | 每轮自动 | 部分（模型需据此判断要搜） | 几十字符 |
| 强化 description | 模型每次看到工具列表 | 是 | 固定字符数 |

路由注入是主路径，后两层是补充；三者叠加后，即便用户描述含糊，也有较高概率让相关工具出现在模型视野里。

### 5.6 开关与筛选规则

- 设置 → 工具 → 「动态工具」开关（默认关闭）。
- **筛选按类别，不按体积**：只把 `mcp__*` 工具移出请求，内置工具始终常驻。
  - 依据：真正膨胀上下文的是 MCP（单服务器几十个工具、上百 KB schema，且清单随服务器变化）；
    内置工具数量固定、schema 也小，按字节判断收益低且依赖主观阈值。
  - 对齐 Reasonix 的做法：它也是把 provider 可见的 `mcp__*` 全部移出，只留 `use_capability` 代理。
- `always_loaded`：通配符白名单（`*` 与 `?`），命中的工具即使属于 MCP 也保持常驻。
  技能工具（`view_skill`、`export_skill`）始终在此名单内。
- 曾经实现过的字节阈值（`defer_threshold`，默认 400，照 Open WebUI）已废弃。

### 5.7 实施顺序

1. 目录构建：汇总工具/技能/MCP，生成检索文档（含 5.4 的全部字段）。
2. 分词 + BM25：照 5.3 的三个细节实现，配单元测试（中文、驼峰、单字、IDF 归零）。
3. **每轮路由注入**（5.5.1 第一层）：用本轮用户消息跑 BM25，注入 top 3~5 候选；
   无命中不注入；块放在 system prompt 末尾以免破坏缓存。
4. `search_tools` + `call_tool` 两个工具，`search_tools` 用 5.5.1 第三层的触发式描述。
5. 「未列出工具数量 + 领域」提示（5.5.1 第二层），按 deferred 集合动态生成。
6. 阈值与白名单设置项。
7. 状态注入：让 deferred 工具的命中结果带 `status`，避免模型盲调。
8. （可选）LLM 判定引擎 + 记账；启用后同时用于第 3 步的预筛。

### 5.8 待决问题

- 技能是否与工具共用同一套 deferred 机制（Reasonix 是统一的，Open WebUI 只管工具）。
- 子代理的 `allowedTools` 白名单在 deferred 后如何解析（需要按名字直接放行，不走搜索）。
- 审批 UI 是否要显示「这个工具是搜索后才出现的」。

## 附录（本方案的额外来源）

- Open WebUI 分词与检索：`backend/open_webui/utils/tool_search.py`（`tokenize`、`_document_text`、`search_deferred_tools`、`split_tool_call_batches`）
- 中文分词反例（纯 ASCII 正则丢弃中文）：`Netw0rkNoob/VulnClaw` issue #251
- 字符二元组方案：同上 issue 的「建议方案」；Open WebUI `_CJK_RE` 实现
- 分层技能检索参考：`willingning-coder/eagle-eye`（`src/skill_retriever.py`，L1 三层硬触发匹配）
- Android jieba 移植现状：`452896915/jieba-android`（2018 年后未更新）
