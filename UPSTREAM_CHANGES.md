# 相对上游的改动说明

本文档记录当前 fork 相对 `upstream/master` 的改动，便于后续维护和向上游同步。

- 远程：`origin` 是个人 fork（`AcE77505/rikkahub`），`upstream` 是原项目（`rikkahub/rikkahub`）。
- 2026-11-08 已合并 `upstream/master`（3.1.2）；合并前 `master` 领先上游 60 个提交。下文的 SSH 工作区、工作区工具/提示词、记忆编辑章节是此前已经提交的历史改动，第 7 节是本次上游同步的取舍记录。
- 本批次（2026-11-08）：36 个已跟踪文件修改（`+976 / -189`），另有 4 个新增 Kotlin 文件和 1 个 issue 草稿；不含构建产物，详见第 5 节。
- 本批次（2026-11-10）：新增 DeepSeek 官方导出导入（含变动识别与独立预览页）、备份时间按渠道拆分、助手列表「当前使用」标识；数据库版本 33 → 34。详见第 13 节。
- 本批次（2026-11-11）：在「修改其他助手」工具组新增记忆管理（列出/读取/写入/删除/按文件或目录导入），复用原有开关，不新增设置项。详见第 14 节。
- 本批次（2026-11-17）：上游 3.3.0 同步分析 + 第一批无冲突纳入；同时把 DeepSeek 导入切换为 App 导出格式（网页版实现移到 `app/src/legacy`，保留但不编译）。19 个上游提交、90 个文件；本次已合入 ai / speech / Provider / Phone Use / 自动压缩默认值，message_node 索引、SafeMode、段落气泡与工作区共享存储仍未适配。详见第 18、19、20 节。
- 本批次（2026-11-23）：提示词精简（记忆、工作区、技能列表、助手管理段）。按一次真实请求测量，system prompt 约 28.5K → 19.2K 字符（约 -33%）；用户 memory 数据（profile/preferences）未改动。详见第 24 节。
- 维护策略（2026-11-12）：**Phone Use（AI 操作屏幕）永远不会纳入修改范围，除了从上游按例同步的情况。** 功能兼容性（支持的 Android 版本、渠道、动作集合）完全由上游项目决定，本 fork 不主动改造。详见第 4 节第 13 条与第 15.2 节。
- 旧版文档以 `6304a995` 为基线记录当时未提交的改动；这些改动已经进入 `master`，相关章节保留作历史说明。

---

## 0. 如何判断上游提交能否直接引入（先读这一节）

同步上游时**不要**用下面这些方法判断冲突，它们都会给出错误结论：

| 错误做法 | 为什么错 |
|---|---|
| `git merge-tree --write-tree HEAD <commit>` | 合的是两条完整历史，fork 与上游的所有历史差异都会算成冲突，结果恒为「全部冲突」 |
| `git merge-tree --write-tree --merge-base=<mergeBase> HEAD <commit>` | 仍然以 fork 的历史为一方，同样会把整批历史差异算进来 |
| 按「该提交触及的文件 fork 是否改过」过滤 | 粒度太粗：同文件不同区域 git 能自动合并，会误报大量「需适配」 |
| `git log --all --grep=<主题>` | `--all` 包含 `upstream/master`，命中的往往是上游自己的提交，并非 fork 已实现 |
| 在 fork 代码里 grep 关键词 | 关键词可能来自上游合并或无关代码，不能证明该功能已引入 |

**正确做法**：把单个上游提交当成一次 cherry-pick，只用该提交自己的父提交当 merge-base。

```bash
# 能直接引入：无输出
git merge-tree --write-tree --merge-base=<commit>^ HEAD <commit>

# 有冲突：输出 CONFLICT 行，并列出冲突文件与类型
git merge-tree --write-tree --merge-base=<commit>^ HEAD <commit> | grep CONFLICT
```

- `--merge-base=<commit>^` 让 git 只比较「该提交带来的改动」与「fork 当前的树」，这才是 cherry-pick 的真实冲突。
- 注意 `merge-tree --write-tree` 即使无冲突也可能返回非 0，**必须以输出里有没有 `CONFLICT` 行判断**，不能看退出码。
- 冲突类型要看清：`content` 需要手工合并；`modify/delete` 说明一方删了文件、另一方改过，要判断是「上游删的过期测试」还是「fork 删的上游新增文件」。
- 遍历整批提交的脚本模板见第 26 节末尾。

**另外两步必查**（判断「是否已引入」而不是「是否冲突」）：

1. 先读本文档后面记录的上次同步章节（如第 20、21 节）。手工适配合入的提交在 git 层面永远显示冲突，实际已经在 fork 里。
2. 用 `git log <fork-head> --grep=<提交主题>`（**不加 `--all`**）确认 fork 自己的历史里有没有同主题的提交。

一句话结论：**是否冲突看 `merge-tree --merge-base=<commit>^`；是否已引入看本文档记录 + fork 自己的 `git log --grep`。两者不能互相替代。**

---

## 0.1 快速总览

| 模块 | 主要内容 | 主要文件 |
|---|---|---|
| SSH 远程工作区 | 工作区类型、SSH 连接、远端命令/文件、终端 | `workspace/SshRunner.kt`、`WorkspaceEntity`、`WorkspaceRepository` |
| SSH 密钥 | 公钥查看/复制、明文备份导出、导入恢复 | `SshKeyPage`、`SshRunner`、`assets/ssh/*` |
| 工作区声明 | 本地 / SSH 各允许声明一个 | `WorkspaceDAO`、`WorkspacePage`、`WorkspaceSelectSheet` |
| 工作区工具 | `workspace_shell` → `shell`，新增 `ssh_*` 工具组，sandbox shell | `WorkspaceTools`、`LocalTools` |
| 工作区提示词 | 单模板按声明渲染 1～2 段，设置页实时预览 | `WorkspaceReminderTransformer`、`SettingWorkspacePromptPage` |
| 记忆文件 | 结构化条目编辑、拖动排序、多选删除、路径修改、预览性能 | `MemoryBlocks`、`MemoryFilePage`、`MemoryRepository` |
| 兼容性 | 旧工具名别名、旧审批 key、历史消息渲染 | `LEGACY_WORKSPACE_TOOL_ALIASES`、`ToolUI`、`ChatNotificationManager` |
| API 24 兼容 | minSdk 24、desugaring、API 26 接口替代 | `app/build.gradle.kts`、`SymlinkCompat`、`AudioFocusController`、`WorkspaceFileSystem` |
| 助手预设 | 多组系统提示词、多组预设对话、新建对话选组 | `Assistant.kt`、`AssistantPromptPage`、`ChatPage`、`ChatService` |
| 助手导出/导入 | 原生助手 JSON、第三方对话配置 | `AssistantSerializer`、`AssistantImporter` |
| 助手配置工具 | AI 读写其他助手 | `AssistantConfigTools`、`LocalTools` |
| 工作区/工具链 | shell 硬超时、su 直通 shim | `WorkspaceRepository`、`ToolchainManager` |
| 数据备份分项 | 聊天记录 / MCP / 助手 / 所有可备份内容，逐项合并或覆盖，兼容旧版归档 | `BackupItem`、`BackupFields`、`BackupManager`、`DatabaseBackupTables` |
| DeepSeek 导入 | 官方导出 ZIP 解析、变动识别、覆盖/保留/并存、独立预览页 | `DeepSeekImporter`、`DeepSeekImportPreviewActivity`、`BackupVM` |
| 其他助手记忆工具 | 列出/读取/写入/删除其他助手的记忆文件，支持按本地路径导入 | `AssistantConfigTools`、`LocalTools`、`MemoryRepository` |

---

## 1. SSH 远程工作区

### 1.1 新增文件

| 文件 | 类型 | 内容 |
|---|---|---|
| `workspace/src/main/java/me/rerere/workspace/SshRunner.kt` | 新增 | `SshTarget`、`SshKeyPair`、`SshKeyBackup`、`SshStat`、`SshEnvironment`、`SshTerminalCommand`；SSH 密钥生成/备份导出/导入恢复、Termux/Linux 初始化与重连命令（从 assets 读取）、测试连接、交互式终端命令、远端命令执行、文件读写/stat/delete、路径解析 |
| `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/SshKeyPage.kt` | 新增 | SSH 密钥管理页：公钥查看/复制、导出明文备份（含私钥）、导入恢复（覆盖确认、仅免密 OpenSSH 私钥） |
| `workspace/src/main/assets/ssh/*.sh`（6 个） | 新增 | Termux/Linux 的 setup、start、reconnect 命令模板；占位符 `{{public_key}}`、`{{port}}`、`{{start_script}}` |
| `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_32_33.kt` | 新增 | `workspaces` 表新增 `type`、`ssh_host`、`ssh_port`、`ssh_username`、`ssh_root` |
| `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/33.json` | 新增 | Room schema 33 |

### 1.2 数据库与实体（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `data/db/entity/WorkspaceEntity.kt` | +52 | 新增 `type` / `sshHost` / `sshPort` / `sshUsername` / `sshRoot`；新增 `isSsh`、`sshTarget()`（要求 host+username）、`sshCommandTarget()`（只要求 host，用于复制初始化命令）、`TYPE_LOCAL` / `TYPE_SSH` |
| `data/db/dao/WorkspaceDAO.kt` | +30 / -10 | 新增 `getDeclaredByType`、`declaredFlow(): Flow<List>`、`clearDeclaredByType`、`clearDeclaredById`、`setSshConfig`；`setDeclared` 只清同类型声明 |
| `data/db/AppDatabase.kt` | 1 / 1 | 数据库版本 32 → 33 |
| `data/db/AppDatabaseFactory.kt` | +2 | 注册 `Migration_32_33` |

### 1.3 仓库与执行（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `data/repository/WorkspaceRepository.kt` | +147 / -6 | `getDeclared(type)` / `getDeclaredLocal()` / `getDeclaredSsh()`；`setDeclared` 取消时只清自己；`setSshConfig` 在类型变化时检查目标类型是否已有其他声明；`isSshAvailable`、`sshSetupCommand`、`sshReconnectCommand`、`testSshConnection`；`executeCommand` 增加 SSH 分支；新增 `executeSandboxCommandWithSettings` |
| `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt` | +49 | 新增 `executeCommandIn(dir, ...)`：在任意目录（沙箱）执行命令，HOME 指向该目录、TMPDIR 为 `.tmp` |
| `di/RepositoryModule.kt` | +14 / -2 | 注入 `SshRunner`（密钥目录 `files/ssh`，传入 `context.assets` 读取命令模板）、`sandboxRoot`（`files/sandbox`）、`WorkspaceRepository` 新参数 |
| `di/AppModule.kt` | 1 / 1 | `LocalTools` 构造增加 `sshRunner` 参数 |

### 1.4 UI（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | +287 / -41 | 新增 Connection 分组：类型开关、Host/Port/Username/Remote directory、保存、测试连接；新增「你用的是 Termux 还是一般 Linux？」及两套初始化/重连按钮；切换成功 Toast；SSH 下隐藏本地目录、Linux 工具链、终端 HOME、本地命令环境，仅保留只读 SSH 远程环境说明；终端图标恢复并支持 SSH；SSH 命令环境分组新增「SSH 密钥」入口 |
| `RouteActivity.kt` | 修改 | 新增 `Screen.SshKey` 路由与 `SshKeyPage` entry |
| `ui/pages/extensions/workspace/WorkspaceDetailVM.kt` | +83 / -6 | `setSshConfig` 失败提示与切换成功 Toast（`notice`）；`sshSetupCommand(SshEnvironment)` / `sshReconnectCommand(SshEnvironment)`；测试连接结果 |
| `ui/pages/extensions/workspace/WorkspaceTerminalSession.kt` | +49 / -2 | SSH 工作区分支 `createSshTerminalSession`：把 `ssh -tt` 作为 `TerminalSession` 的子进程，PTY 直接连远端 shell |
| `ui/pages/extensions/workspace/WorkspacePage.kt` | +6 / -4 | 声明列表改为支持本地/SSH 各一个；同类型互斥提示；顶部新增「SSH 密钥」入口 |
| `ui/pages/extensions/workspace/WorkspaceVM.kt` | 2 / 2 | `declared` 从单个变为 `List<WorkspaceEntity>` |
| `ui/components/ai/FilesPicker.kt` | +33 / -18 | 聊天页工作区选择显示两个已声明工作区；声明按类型切换 |
| `ui/components/ai/WorkspaceSelectSheet.kt` | +53 / -15 | 选择器按「本地 / SSH」分组，每组各自有「不声明」项 |

---

## 2. 工作区工具、声明与提示词

### 2.1 工具（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `data/ai/tools/WorkspaceTools.kt` | +220 / -49 | `SSH_TOOL_PREFIX = "ssh_"`；`shell` 取代 `workspace_shell`；`LEGACY_WORKSPACE_TOOL_ALIASES` 增加 `workspace_shell → shell`；`baseWorkspaceToolName`；审批解析兼容旧 key 与 `ssh_` 前缀；工具名由 `toolPrefix` 生成；`WorkspaceToolContext.rootDescription`；无工作区时 shell 走 sandbox；远程文件读写（read/write/edit）与命令分支；远程 `cwd` 相对 `remoteRoot` |
| `data/ai/tools/local/LocalTools.kt` | +55 / -20 | 同时构建两组工具：本地组（声明本地工作区，否则 sandbox）+ SSH 组（`ssh_` 前缀，仅在声明且参数完整时挂载）；两组共用工具开关 |
| `data/ai/tools/ChatToolFactory.kt` | +7 / -5 | 改为读取本地声明工作区；输出截断目录在无本地工作区时回落 sandbox |

### 2.2 兼容旧工具名（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `ui/components/message/tools/ToolUI.kt` | +4 | `resolve` 先剥离 `ssh_` 前缀，再查旧名映射 |
| `ui/components/message/tools/WorkspaceToolUIs.kt` | 1 / 1 | `ShellToolUI.toolName`：`workspace_shell` → `shell` |
| `ui/pages/setting/SettingToolsPage.kt` | 1 / 1 | 审批项 `workspace_shell` → `shell` |
| `service/ChatNotificationManager.kt` | +3 / -2 | shell 超时通知用 `baseWorkspaceToolName(...) == "shell"` 识别 |
| `ui/components/message/ChatMessageEditedFiles.kt` | +1 | 编辑文件列表识别 `ssh_write_file` / `ssh_edit_file` |

### 2.3 提示词（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `data/ai/transformers/WorkspacePrompt.kt` | +10 / -2 | 默认模板改用 `shell`；`renderWorkspacePrompt` 新增 `dropLinesWith`，用于远程段删除 skills/upload 行 |
| `data/ai/transformers/WorkspaceReminderTransformer.kt` | +215 / -105 | 抽出 `buildWorkspacePromptSections`（设置页预览复用）：本地段 + 远程段分别渲染；无声明时输出 sandbox `<file_tools>`；远程段 `workspace_dir = user@host:port:root`、工具名带 `ssh_`、删除本机 skills/upload 行；仅本地段注入 AGENTS.md |
| `ui/pages/setting/SettingWorkspacePromptPage.kt` | +93 / -4 | 新增渲染说明、「实际注入预览」（与请求链路共用同一函数）、占位符分组（通用 / 仅本地有效） |

---

## 3. 记忆文件

### 3.1 新增文件

| 文件 | 类型 | 内容 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/data/model/MemoryBlocks.kt` | 新增 | `MemoryBlock.Entry` / `MemoryBlock.Raw`；`parseMemoryBlocks` 把正文拆成列表项与只读块；`joinMemoryBlocks` 拼回；`[stated]` 在界面隐藏、保存时补回 |

### 3.2 数据层（修改）

| 文件 | 增/删/改 | 具体位置与改动 |
|---|---|---|
| `data/model/MemoryFile.kt` | +14 | 新增 `contentWithBody(newBody)`：只替换正文、保留原始 frontmatter，避免排序/删除条目时重新序列化导致文件变大 |
| `data/repository/MemoryRepository.kt` | +53 | 新增 `moveFile(assistantId, fromPath, toPath, content)`（改路径，目标冲突时返回 `Conflict`）；新增 `getFileFlow(assistantId, path)`（详情页只查单个文件） |
| `data/db/dao/MemoryDAO.kt` | +3 | 新增 `getFileFlow(assistantId, path)` |
| `ui/pages/memory/MemoryFileVM.kt` | +16 / -6 | `state` 改用 `getFileFlow`；`save(newPath, content, onSaved)` 支持路径变更并回调新路径 |

### 3.3 UI（修改）

`ui/pages/memory/MemoryFilePage.kt`（+679 / -112）是本次记忆功能的主要改动：

- **查看页**：
  - 信息卡（最后更新 / 摘要 / 别名合并）整卡点击进入元数据编辑；
  - 正文按条目分块，`LazyColumn` 懒加载；点击任意块进入正文编辑；
  - 删除右上角「编辑」按钮，只保留删除。
- **两组编辑复用同一个页面**（`MemoryEditSection.NONE / METADATA / BODY`）：
  - 元数据编辑：路径（新增对已有文件改路径）、标题、摘要、别名；
  - 正文编辑：条目列表 + 全屏原始文本模式。
- **条目列表**：
  - 点击条目打开底部 sheet 编辑；
  - 右侧拖动手柄（`sh.calvin.reorderable`，长按拖动）排序，松手即时保存；
  - 右侧删除按钮，单条删除需二次确认；
  - 长按条目进入多选，支持全选、多选删除（二次确认）、取消；
  - 顶部「原始文本」切换全屏 `EditText`；「+」新增条目；
  - 标题/段落作为只读块保留，不参与删除/排序。
- **进入性能**：
  - 查看模式不再计算编辑用的 `contentWith` / `toByteArray`；
  - 预览从整段 `MarkdownBlock` 改为按块懒加载纯文本（首屏只渲染可见条目）；
  - VM 只查当前文件，避免进入时加载整个记忆库。
- **保存语义**：条目编辑/删除/排序即时写回；路径变更走 `moveFile` 并导航到新路径。

### 3.4 字符串资源（修改）

| 文件 | 增/删/改 | 内容 |
|---|---|---|
| `app/src/main/res/values/strings.xml` | +44 / -1 | SSH 连接、Termux/Linux 命令、工作区声明、提示词预览、记忆条目等英文文案 |
| `app/src/main/res/values-zh/strings.xml` | +44 / -1 | 对应中文文案 |

---

## 4. 维护注意事项

1. **数据库版本 33**
   - `AppDatabase.version = 33`，迁移 `Migration_32_33` 与 `app/schemas/.../33.json` 必须保持一致。
   - 新增列：`type`（默认 `LOCAL`）、`ssh_host`、`ssh_port`（默认 8022）、`ssh_username`、`ssh_root`。

2. **声明语义已变化**
   - `declared` 不再是全局唯一，而是「本地 / SSH 各最多一个」。
   - 不要恢复 `setDeclared` 的全局清除逻辑；取消声明只清自己（`clearDeclaredById`）。

3. **工具名与别名**
   - 当前工具名：`read_file` / `write_file` / `edit_file` / `shell`；远程组：`ssh_read_file` / `ssh_write_file` / `ssh_edit_file` / `ssh_shell`。
   - `workspace_read_file` / `workspace_write_file` / `workspace_edit_file` / `workspace_shell` 是旧名，执行、UI 渲染和审批都要继续兼容。
   - 审批配置里旧的 `workspace_shell` key 仍会被读取；`ssh_*` 复用基础工具的审批。

4. **提示词**
   - 单模板会按声明的工作区渲染 1～2 段；远程段自动删除含 `skills_dir` / `builtin_skills_dir` / `upload_dir` 的行。
   - 设置页预览与请求链路共用 `buildWorkspacePromptSections`，修改渲染逻辑时不要只改其中一处。

5. **记忆正文**
   - 条目编辑只改 body，必须使用 `MemoryFile.contentWithBody`（保留原始 frontmatter），不要改回 `contentWith`。
   - `[stated]` 是内部标记：条目列表隐藏，`joinMemoryBlocks` 保存时补回。
   - 48KB 限制只拦「变大」的写入；排序、删除不会因为 frontmatter 重排触发限制。
   - 正文编辑是即时保存，左侧「取消」只返回、不回滚。

6. **已知缺口**
   - SSH 工作区下，详情页「文件」标签、CWD 选择器、历史消息「编辑过的文件」导出仍走本地路径。
   - 远程工具的超长输出截断仍落盘到本地 `.tool_outputs`。
   - 记忆预览目前是纯文本条目（不再渲染 Markdown 粗体/链接），这是为进入性能做的取舍。

7. **构建环境**
   - 本机已安装 pnpm（12.15.0）并执行过 `web-ui` 的 `pnpm install`；完整构建可直接运行 `./gradlew :app:assembleGithubRelease`。
   - 没有 pnpm 的环境仍可用 `-x :web:buildWebUi` 跳过 Web UI 构建，但这会影响内置 Web UI 静态资源。
   - minSdk 已降到 24，构建依赖 core library desugaring；不要关闭 `isCoreLibraryDesugaringEnabled`，否则 `java.time` 在 API 24/25 上会崩。

8. **SSH 密钥与命令模板**
   - 命令模板在 `workspace/src/main/assets/ssh/`：`termux_setup.sh`、`termux_start.sh`、`termux_reconnect.sh`、`linux_setup.sh`、`linux_start.sh`、`linux_reconnect.sh`。占位符为 `{{public_key}}`、`{{port}}`、`{{start_script}}`；改命令只改 assets，不触发 Kotlin 重编译。
   - 密钥固定在 app 私有目录 `files/ssh/id_ed25519`（公钥 `id_ed25519.pub`）；`SshRunner.ensureKeyPair()` 只在文件缺失时生成，之后所有 SSH 工作区共用同一对密钥。
   - 清除 app 数据、卸载重装或换设备都会生成新密钥。
   - 密钥管理页（工作区列表顶部，或 SSH 工作区详情页的「SSH 密钥」入口）支持导出明文备份（含私钥）和导入恢复；导入会覆盖现有密钥且只接受免密 OpenSSH 私钥，失败时会恢复旧密钥。
   - 另一台设备要连接同一个远程工作区，需要在那台设备上执行它自己的初始化命令，把它的公钥追加到目标机的 `~/.ssh/authorized_keys`；`authorized_keys` 支持多把公钥，不会覆盖已有设备。
   - 初始化/重连命令里的 `pgrep -x sshd || sshd` 不会重启已经运行的 sshd，因此多台设备可以同时连接同一个远端。
   - 工作区配置（host/port/username/root）可以通过备份恢复，但私钥不一定随备份迁移；换设备通常需要重新执行该设备的初始化命令，或手动导入同一对 `id_ed25519`。

9. **API 24 下限**
   - 新增代码不要直接使用 `java.nio.file`、`Process.waitFor(timeout, unit)`、`AudioFocusRequest` 等 API 26+ 接口；优先复用 `SymlinkCompat`、`AudioFocusController`、`exitValue()` 轮询。
   - `WorkspaceFileSystem` 的 glob 是自实现，语义与 `PathMatcher` 接近但不完全等价（例如字符类范围、复杂转义），改匹配逻辑时要补测试。
   - 上游 `terminal` 模块的 `LocalPtyTransport` 读 `/proc/<pid>/stat` 用的是 `Files.readAllBytes` + `File.toPath`（API 26），已改成 `FileInputStream`；同步 `terminal` 时注意不要被上游版本覆盖回去。

10. **助手预设**
    - `presetMessages` 仍是没有分组时的兼容来源；新数据使用 `presetMessageGroups`。
    - 新建对话传入的 `presetGroupId` 只影响本次创建，不写回助手的默认组。
    - 有效系统提示词一律走 `effectiveSystemPrompt()`；没有选中预设时回退 `systemPrompt`。

11. **assistant_config 工具**
    - 只允许改其他助手；当前助手 id 在构建工具时传入并校验。
    - `update_assistant` 的数组字段是 upsert，不是整体替换；只有 `replace_*` 才清空原列表。
    - 长文本通过本地文件路径传递，避免 provider 的工具参数大小限制。

12. **su 与工具链**
    - `$PREFIX/su-shim/su` 是直通系统 su 的符号链接，必须排在工具链 `bin` 之前；否则 `su -c '复合命令'` 会再次命中 termex 的包装脚本。
    - `SU_COMMAND` / `SU_ARGUMENTS` 继续注入，`su2` 依赖它们；不要把工具链 `bin/su` 移到 PATH 前面。

13. **Phone Use（AI 操作屏幕）**
    - **该功能永远不会纳入修改范围，除了从上游按例同步的情况。** 不要为了 fork 的 `minSdk 24`、渠道差异或本地需求单独改造它。
    - 兼容性完全由上游项目决定。当前上游实现只支持 github 渠道、Android 11+（`PhoneUseManager.isSupported = !IS_PLAY_STORE && SDK_INT >= R`），并依赖 API 30 的 `takeScreenshot` 与 `ACTION_IME_ENTER`；低版本的截图/回车降级不在本 fork 的计划内。
    - 同步上游时整块接受其实现（工具、无障碍服务、UI、字符串、`app/src/github` 源集）；与本地代码冲突时按上游语义合并，不保留 fork 特有分支。
    - 唯一允许的本地改动是「为合并通过而必需的机械修改」（例如把上游引用的 `assistant.localTools` 改成 fork 的 `enabledTools`、补 import），不改行为。

14. **消息里的路径链接**
    - `rikkahub-path:` 是**渲染期临时 Markdown 字符串**里的内部链接标记（`linkifyPaths` 生成、`Markdown.kt` 的 `pathLinkWidget` 消费），只存在于 `MarkdownBlock` 的局部变量；消息正文、数据库、复制、导出、Web 端拿到的都是原文。
    - 2026-11-12 曾尝试改成在解析后的 `MarkdownDocument` 节点上挂链接（避免 scheme 进入文本层），但在 BetterText 0.1.5 的 `Markdown(document = ...)` 下路径不可点击，已回退到字符串方案。要再试节点级方案，先在真机上验证 `inlineLink` 组件确实被调用。
    - `stripInternalPathLinks` / `withoutInternalPathLinks` 是防御模型模仿和旧数据的兜底，不是路径链接机制的一部分；新增用户可见出口时仍应调用。

---

## 5. 2026-11-08 批次

### 5.1 Android 7.0（API 24）兼容

`minSdk` 从 26 降到 24（app、library convention、baselineprofile；search 从 23 升到 24），并启用 core library desugaring（`com.android.tools:desugar_jdk_libs:2.1.5`）。API 26+ 才有的接口改为兼容实现：

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts`、`build-logic/.../AndroidLibraryConventionPlugin.kt`、`search/build.gradle.kts`、`app/baselineprofile/build.gradle.kts` | minSdk 24；打开 `isCoreLibraryDesugaringEnabled`，加 `desugar-jdk-libs` |
| `workspace/.../SymlinkCompat.kt`（新增） | API 26+ 用 `Files.createSymbolicLink`，24/25 回退 `ln -s` |
| `ToolchainManager.kt` | 符号链接判断/创建改用 `SymlinkCompat` |
| `WorkspaceFileSystem.kt` | 去掉 `java.nio.file`；`walkTopDown` 不跟随符号链接；自带 glob→regex（`*`、`**`、`?`、`[abc]`、`{a,b}`、转义） |
| `WorkspaceDocumentsProvider.kt` | 符号链接判断改用 `canonicalPath` 比较 |
| `BackupManager.kt`、`PendingRestore.kt` | 临时目录改用 `File.createTempFile` + 删除后 `mkdirs`；移动优先 `renameTo`，失败再 copy/delete |
| `AudioFocusController.kt`（新增） | API 26+ `AudioFocusRequest`，24/25 回退 `requestAudioFocus(listener, STREAM_VOICE_CALL, ...)`；`ASR.kt`、`VoiceMode.kt` 统一使用 |
| `AppLogo.kt` | `AdaptiveIconDrawable` 加 API 26 判断 |
| `WorkspaceShellRunner.kt` | 用 `exitValue()` 轮询替代 `waitFor(timeout, unit)`；`destroyForcibly` 加 API 26 判断 |
| `RikkaHubApp.kt`、`SettingWebPage.kt` | 启动前台服务改用 `ContextCompat.startForegroundService` |

### 5.2 助手提示词与对话预设

- `Assistant` 新增 `systemPromptPresets` / `defaultSystemPromptPresetId` 与 `presetMessageGroups` / `defaultPresetMessageGroupId`，以及 `effectiveSystemPrompt()`、`presetMessagesFor()`、`activePresetMessages()` 等辅助函数；没有分组时继续使用旧的 `presetMessages`。
- `ChatRequestFactory` 使用有效系统提示词；助手详情页摘要同步。
- `AssistantPromptPage` 支持提示词预设的切换、重命名、删除、新建和内容编辑，以及预设对话组的管理。
- 新建对话时，如果助手配置了多组预设对话，`ChatPage` 先弹出组选择；`RouteActivity`、`ChatVM`、`ViewModelModule`、`navigateToChatPage` 传递 `presetGroupId`，`ChatService.newConversation` 按组插入消息。
- 助手列表新增导出；`AssistantSerializer` 负责原生助手 JSON 导出/解码（清理本机专属引用，保留提示词、预设、正则和生成参数）；`AssistantImporter` 优先识别原生导出，并支持第三方「对话配置」（`data.systemPresets` / `data.systemPromptPresets`）。
- 中英文字符串同步。

### 5.3 助手配置 AI 工具

- 新增 `LocalToolOption.AssistantConfig`（`assistant_config`，设置页「工具」里默认关闭的开关）。
- `AssistantConfigTools.kt` 提供 `list_assistants` / `get_assistant` / `update_assistant`：
  - 只能读取/修改当前会话之外的助手，当前助手禁止修改；
  - `update_assistant` 的标量字段可选，`system_prompt_presets` / `preset_message_groups` 按 id/title upsert，另有 `replace_*` / `delete_*`；
  - 长提示词或预设对话可传绝对文件路径（`system_prompt_file` / `prompt_file` / `messages_file`），避免工具参数大小限制。
- `LocalTools.getTools` 新增 `currentAssistantId`，`ChatToolFactory` 传入；只有开关开启且存在当前助手时才挂载。

### 5.4 工作区与工具链

- shell 工具的 `overtime` 改为硬超时：调用方显式传入时，到点停止并返回结果，不受全局 `shellAutoStopOnOvertime` 影响（`WorkspaceRepository` 两处）。
- 工具链 su：
  - 注入 `SU_COMMAND`（系统 PATH 探测到的真实 su）与 `SU_ARGUMENTS`，供 termex 的 `su2` / `sudo` 使用；
  - 新增 `$PREFIX/su-shim/su` 直通系统 su，并置于工具链 `bin` 之前，绕过 termex `su` 脚本对含空格 `-c` 参数的字段分割缺陷；
  - 找不到 su 时不创建 shim，行为保持原样。

---

## 6. 本次未包含的改动

以下内容属于构建/本地环境，不进入 Git 工作树，故未列在文件清单中：

- 全局安装 pnpm、`web-ui/node_modules` 与 `web/src/main/resources/static` 的本地构建产物；
- `app/build/outputs/apk/...` 下的 APK 产物。

如果后续把这些改动提交或拆分成多个 commit，建议按「SSH 工作区」「工作区工具与提示词」「记忆结构化编辑」三组拆分，便于向上游提交 PR。

---

## 7. 上游 3.1.2 同步（2026-11-08）

合并 `upstream/master`（`fe5a28f9 chore: bump version to 3.1.2`，共 82 个上游提交），解决 60 个冲突文件。版本号更新为 `3.1.2` / `195`。

### 7.1 已合并的上游改动

- Markdown 渲染迁移到 BetterText：删除 `ui/markdown/MarkdownParser`、`LatexText`、`highlight` 模块，`Markdown.kt` / `MarkdownUtils.kt` 改用 `me.rerere.bettertext.*`。
- 会话消息队列重构为 `ConversationInbox`（`session.inbox`）：`ChatService`、`ConversationSession`、`ConversationSessionManager`、`ChatVM` 改用 inbox；复用上游的后台生成与消息队列逻辑。
- 浮动窗：采用上游 `ChatFloatingContent`、`ChatFloatingWindows`、`ChatFloatingTracker` 与 `FloatingChatUiStateTest`。
- 对话菜单与导出保存文件、历史删除同步收藏、消息概览左滑、顶栏浮动胶囊等 UI 改动。
- 压缩上下文保留消息数恢复为手动输入（上游 e85c1d90）。
- 记忆整理模型设置、媒体提供商独立详情页、联网搜索入口、图片裁剪 Compose、扫码 CameraX + zxing、德语、APK 瘦身、技能目录修复等上游提交随合并进入。
- `terminal` 模块、`material-color-utilities` 源码、`ModelsDevUpdater` 4 小时刷新、`TinyfishSearchService` 的 `purpose` 参数、推荐提供商 STA1N 等。

### 7.2 保留的 fork 定制

- 工作区仍使用 fork 的「Android 原生 + termex 工具链」架构（`WorkspaceManager` / `ToolchainManager` / `SshRunner`），保留 `declared` / `directory` / `toolchainEnabled` 与 `sshHost` / `sshPort` / `sshUsername` / `sshRoot` 字段，数据库版本 33。
- `minSdk 24`、core library desugaring、`SymlinkCompat`、`AudioFocusController`、`WorkspaceFileSystem` 的自有 glob 实现。
- 助手多组提示词 / 预设对话、`assistant_config` 工具、`AssistantSerializer` 导出导入。
- `SU_COMMAND` + `$PREFIX/su-shim` 的 su 修复；shell 显式 `overtime` 硬超时。

### 7.3 未合并的上游工作区架构

上游 3.1.2 的工作区是 `WorkspaceBackend` + `RootfsInstaller` + `SshWorkspaceBackend` / `SshConnectionPool`（含跳板机）架构，与 fork 已重构掉的 proot/rootfs 方案不兼容。以下内容**未合并并已删除**，fork 继续使用自己的 `SshRunner` 与本机工作区实现：

- `workspace/.../WorkspaceBackend.kt`、`LocalWorkspaceBackend.kt`、`RootfsInstaller.kt`、`ProotTermination.kt`、`WorkspaceBackgroundCommand.kt`
- `workspace/.../ssh/*`（`SshConfig`、`SshConnection`、`SshConnectionPool`、`SshShell`、`SshWorkspaceBackend`）及对应测试
- `app/.../data/model/SshWorkspaceSettings.kt`、`WorkspaceSshEditorPage.kt` / `WorkspaceSshEditorVM.kt`、`SshTerminalTransport.kt`、`SecretCipher.kt`
- 后台 shell：`BackgroundShellManager` / `BackgroundShellForegroundService` / `BackgroundTasksSheet`、`BackgroundShellLogTailTest`（依赖上游 `WorkspaceBackgroundCommand`）

因此上游的「SSH 跳板机」「后台运行 shell」能力在本 fork 中**暂不可用**，后续如需支持应基于 fork 的 `SshRunner` / `WorkspaceShellRunner` 单独适配。

### 7.4 合并后验证

- `./gradlew assembleGithubRelease --no-daemon`：**BUILD SUCCESSFUL**（3m 15s），产物为 3.1.2 的 arm64 / x86_64 / universal APK。
- `./gradlew test --no-daemon`：510 个测试，7 个失败，全部是 `androidx.datastore` 在 Windows 临时目录执行 `atomicMoveTo` 失败（`FileStorage.kt:118` / `FileMoves.android.kt:33`），属本机 Windows 测试环境问题，与合并逻辑无关。
- lint：合并后语言资源出现 114 个 `ExtraTranslation`（上游 rootfs 相关 key 在默认 values 被 fork 删除），已从上游默认 values / values-zh 补齐 19 个 key 后消除。
- `.gitignore` 增加 `**/build/`，避免模块构建产物被误提交。

---

## 8. 后台 Shell（2026-11-08）

上游 3.1.2 的后台命令能力依赖其 `WorkspaceBackend`/`WorkspaceBackgroundCommand` 架构，合并时未直接引入。本次按 fork 的「Android 原生 + termex 工具链」重新实现，行为与上游保持一致（仅本地工作区/沙箱支持，SSH 工作区仍前台执行）。

### 8.1 执行层

- `workspace/.../WorkspaceBackgroundCommand.kt`（新增）：后台命令接口与本地实现；stdout/stderr 合并写入工作区 TMPDIR 的日志文件。
- `HostShellRunner.startBackground`：复用前台命令的环境（termex 的 `PATH` / `LD_LIBRARY_PATH` / `PREFIX` / `HOME` / `TMPDIR`）与 shell；`kill()` 复用 `killProcessGroup`，连同孙进程一起结束。
- `WorkspaceManager.startBackgroundCommand` / `startBackgroundCommandIn`、`WorkspaceRepository.startBackgroundCommand` / `startSandboxBackgroundCommand`：本地工作区与沙箱支持；SSH 工作区返回 null。

### 8.2 任务管理

- `BackgroundShellManager`：内存任务表、每会话并发上限、输出增量读取、日志尾部、停止/终止、完成通知事件（Posted / Withdrawn）。
- `BackgroundShellForegroundService`：有任务运行时保持进程在前台，通知栏显示运行数量。
- `ChatService`：监听通知事件并写入/撤回会话 inbox；删除会话时终止该会话的后台任务。
- `ChatVM`：暴露 `backgroundTasks` 与 `stopBackgroundTask`。

### 8.3 工具与界面

- `shell` 新增 `run_in_background` 参数（仅本地且设置开启时暴露）；新增 `shell_output`、`shell_kill` 工具。
- `ShellToolUI` 增加后台态标题/摘要/详情；新增 `ShellOutputToolUI`、`ShellKillToolUI` 并注册到 `ToolUIRegistry`。
- 输入栏恢复后台任务按钮（角标显示运行数量），`BackgroundTasksSheet` 提供任务列表、日志详情和停止操作。

### 8.4 设置项

- `Settings` 新增 `shellBackgroundEnabled`（默认开）、`shellBackgroundMaxRunning`（默认 5）、`shellBackgroundNotify`（默认开）。
- 设置 → 工具 → 「后台命令」卡片（仅 shell 开关开启时显示）：
  - 允许后台运行；
  - 每对话并发上限（1 / 2 / 3 / 5 / 10，行尾显示当前值，点击弹窗选择）；
  - 完成后插入对话提示。

### 8.5 边界

- 后台任务只属于当前会话；删除会话会终止任务。
- 应用进程退出后任务表丢失（内存态）；前台服务降低被系统回收的概率。
- 日志为 stdout/stderr 合并文件，放在工作区 TMPDIR；AI 用 `shell_output` 读取，用户可在任务面板查看末尾。

---

## 9. 对话地图多选（2026-11-08）

上游的对话导出多选只在普通消息列表里实现，概览（对话地图）没有选择状态；顶栏「对话地图」入口还会显式清空多选，导致切换视图后选择丢失。

- `ChatListPreview` 接收 `ChatSelectionState`：
  - 每轮卡片按 `messageNodes` 的 `firstIndex..lastIndex` 映射到节点 id；
  - 多选状态下显示 `TriStateCheckbox`（全选 / 半选 / 未选），点击卡片切换该轮，长按进入多选；
  - 地图底部复用 `ChatSelectionToolbar`，确认后打开 `ChatExportSheet`；
  - 地图根节点加不透明背景，避免与普通列表的工具栏叠加。
- `ChatList` 将同一个 `ChatSelectionState` 传给两个视图；`ChatPage` 的「对话地图」入口不再清空 `selectionState.selecting`，切换界面保留选择。
- 两个界面共用 `selectedItems`（节点 id）：普通列表单独选中的节点会让地图对应轮次显示半选；在地图选中整轮会同步到普通列表。

---

## 10. 长按会话导出（2026-11-08）

- 会话列表长按菜单新增「导出」：`ConversationList` / `ConversationItem` 增加 `onExport` 回调，`ChatDrawer` 维护 `exportTarget`。
- 导出目标从 `ConversationRepository.getConversationById` 重新读取，确保拿到完整会话；`ChatExportSheet` 的 `selectedMessages` 直接传全部 `currentMessage`，不经过多选。
- 导出沿用现有格式面板（Markdown / 图片、包含/展开思考），保存和分享走 SAF（`CreateDocument`）。

---

## 11. 数据备份分项（2026-11-08）

上游备份只有 `DATABASE` / `FILES` 两个粗粒度开关，设置项无法单独选择或恢复。本次把备份拆成「选项 + 资源」两层：界面只暴露四个选项，内部仍按资源精确导出/恢复。

### 11.1 备份选项

| 选项 | 内容 |
|---|---|
| 聊天记录 | 对话、消息、助手分组、收藏、记忆文件与整理进度、用量、翻译历史、媒体生成、托管文件索引 |
| MCP | MCP 服务器与 OAuth 授权状态 |
| 助手 | 助手、助手标签、子代理、快捷消息、模式注入、Lorebook、子代理模型与并发设置 |
| 所有可备份内容 | 以上三项 + 提供商、搜索、语音、媒体生成、界面/网络/WebDAV/S3 设置、工作区、SSH 密钥、附件/技能/字体/生成媒体 |

- `BackupItem`：界面选项；`BackupResource`：实际资源（9 种）。`BackupSelection.effectiveItems` 保证选了 `ALL` 时忽略其它选项，`BackupVM.updateLocalBackupItems` 做同样的归一化。
- 默认值为 `ALL`，即完整备份。
- `BackupItem.containsSecrets` 标记会写入密钥/token 的选项，导出和导入前各弹一次明文提示（本地页面）。

### 11.2 归档格式

| 条目 | 说明 |
|---|---|
| `backup.json` | 格式版本与本次包含的资源列表（`BackupManifest`） |
| `settings.json` | 所选资源拥有的设置字段（键名与 `Settings` 属性一致）+ `launchCount` |
| `rikka_hub.db` | 选「所有可备份内容」时的整库快照，沿用旧版文件名以兼容上游 |
| `tables.db` | 部分选择时的整库快照（新格式，旧版不认识） |
| `upload/` `skills/` `fonts/` `media_creation/` | 文件项 |
| `ssh/` | 工作区项，含 `id_ed25519` 私钥 |

- 数据库导出用 `VACUUM main INTO '<path>'`，随后 `normalize`（完整性校验 + checkpoint + 去 sidecar）。
  **不要用 `ATTACH` 拼只含所选表的库**：requery 的 `execSQL` 不会把 `?` 绑定到 `ATTACH`/`VACUUM` 上，且对新建目标库会报 `SQLITE_CANTOPEN`。
- 导出前会校验快照里确实含有待导出的表，缺失直接报错。

### 11.3 恢复

- 逐项选择「合并」或「覆盖」，默认：聊天记录/助手/所有可备份内容合并，MCP 覆盖。
- 覆盖：`DELETE` 后整表插入；合并：按主键 `ON CONFLICT ... DO UPDATE`，
  **不能用 `INSERT OR REPLACE`**——它会先删行，触发外键 `ON DELETE CASCADE`，把备份里没有的子表数据（如消息）一起删掉。
- 整个分表写入在一个事务里完成，通过 `PendingRestore` 在下次启动、Room 打开之前应用。
- 设置按资源字段合入盘上现有值（`SettingsStore.patchBeforeInitialization`），未备份字段保持现值。

### 11.4 兼容性

| 方向 | 结果 |
|---|---|
| 旧版归档 → 新版 | 兼容：无 `backup.json` 走旧格式分支，整库 + 完整设置 + 附件按所选资源恢复 |
| 新版「所有可备份内容」→ 旧版 | 兼容：完整 `settings.json` + `rikka_hub.db` + 相同附件目录；`backup.json`、`ssh/` 被旧版忽略 |
| 新版「部分选择」→ 旧版 | 不安全：旧版不认识 `tables.db`（数据库不恢复），且会把缺失的设置字段写成默认值 |

- 旧枚举名映射：`DATABASE` → 聊天记录，其余（`FILES` 及上一版九项）→ 所有可备份内容。

### 11.5 其他

- 点击顶部错误提示会把完整报错复制到剪贴板并提示「已复制」（`ui/.../Toaster.kt`，仅 `ToastType.Error`）。
- 导出失败提示改用 `backup_page_backup_failed`，不再误用「恢复失败」。
- 新增测试：
  - `BackupItemSerializerTest`：枚举序列化与旧值兼容、`ALL` 互斥、资源展开；
  - `BackupSettingsFieldsTest`：所有资源的字段并集覆盖 `SETTINGS_FIELDS`，完整 `settings.json` 覆盖全部持久化字段；
  - `BackupTableListTest`：表名清单与 Room schema（`app/schemas/...`）逐表对齐；
  - `BackupManagerTest`（androidTest）：`ALL` 产出 `rikka_hub.db`、部分选择产出 `tables.db`。

### 11.6 已知限制

- `message_fts` / `message_fts_rowid` 不参与恢复：`message_fts` 是 FTS5 虚表，不能按普通表复制。恢复后旧消息无法被全文搜索，需要在恢复后重建索引（尚未实现）。
- 数据库快照始终是整库，部分选择时备份体积与会包含未选资源的表数据（`workspaces` 仅少量行）；恢复时仍只写所选资源的表。

### 11.7 本次改动文件

- 新增：`data/datastore/BackupItem.kt`、`data/datastore/BackupFields.kt`、`data/sync/DatabaseBackupTables.kt`
- 修改：`data/datastore/PreferencesStore.kt`、`data/datastore/Settings.kt`、`data/sync/BackupManager.kt`、`data/sync/DatabaseBackup.kt`、`data/sync/S3Sync.kt`、`data/sync/s3/S3Config.kt`、`data/sync/webdav/WebDavSync.kt`、`ui/components/ui/ConnectedChoiceButtons.kt`、`ui/pages/backup/BackupVM.kt`、`ui/pages/backup/components/BackupComponents.kt`、`ui/pages/backup/tabs/{ImportExportTab,WebDavTab,S3Tab}.kt`、`ui/.../toast/Toaster.kt`、`app/src/androidTest/.../BackupManagerTest.kt`
- 字符串：`app` 与 `ui` 模块共 9 种语言的 `values*/strings.xml`

---

## 12. 渐变背景动画跟随生命周期（2026-11-08）

原先 `MeshGradientBackground` 用 `rememberInfiniteTransition` 驱动四个光斑，没有显式的生命周期控制。

- 改用 4 个 `Animatable` + `LaunchedEffect(resumed)`，`rememberAppLifecycleState()` 到达 `RESUMED` 才推进；
  切到后台或失去焦点时 effect 取消，动画停在当前位置，回前台从该位置继续，不会跳变。
- 每个相位的单圈跨度取 `2π` 的整数倍（p1 20 圈 / 110 秒、p3 10 圈 / 85 秒、p4 10 圈 / 62 秒），
  保证 `sin` / `cos` 在循环衔接处连续。
- 视觉效果与原来一致，只是驱动方式改变。

收益边界：Compose 默认在 Activity `STOPPED` 时会暂停 recomposer 帧；这里用 `RESUMED` 更严格，
进入最近任务、分屏失焦等场景就能停，且行为与 OEM 实现无关。改动只在一个文件，可随时回退。

---

## 13. DeepSeek 官方导出导入（2026-11-10）

新增「从 DeepSeek 导入」：读取 DeepSeek 网页版 *设置 → 数据管理 → 导出数据* 得到的 ZIP，
把其中 `conversations.json` 的对话树还原为 RikkaHub 会话。支持重复导入时识别有变化的对话，
并由用户逐个决定覆盖 / 保留 / 并存。

### 13.1 支持的导出格式

| 条目 | 说明 |
|---|---|
| 文件 | ZIP 内的 `conversations.json`；同包的 `user.json` 只含账号身份，忽略 |
| 会话 | `{ id, title, inserted_at, updated_at, mapping }`，时间为带偏移的 RFC 3339 字符串 |
| 消息树 | `mapping: { nodeKey -> { id, parent, children[], message } }`；`parent`/`children` 引用的是 **key** |
| 消息 | `{ model, inserted_at, fragments[] }`，**没有 `role` 字段**，角色由 fragment 类型推导 |
| 片段 | `REQUEST` / `RESPONSE` / `THINK` / `SEARCH` / `TOOL_SEARCH` / `FILE` / `TOOL_OPEN` / `TOOL_FIND` / `TEMPLATE_RESPONSE` / `TIP` |
| 实测规模 | 10 MB ZIP、解压 42 MB、1946 个会话、15549 个节点；完整解析约 1.1 秒 |

### 13.2 片段映射

| fragment | 归属 | 转换结果 |
|---|---|---|
| `REQUEST` | 用户 | `UIMessagePart.Text` |
| `FILE` | 用户 | `Text("[Attachment] 文件名, N KB (导出包不含文件本体)")` |
| `RESPONSE` / `TEMPLATE_RESPONSE` | 助手 | `Text`；`[citation:N]` 改写为 `[citation,域名](N)` |
| `THINK` | 助手 | `Reasoning`，统一排在正文前 |
| `SEARCH` / `TOOL_SEARCH` | 助手 | `Tool("search_web")`，`output.items = [{id,index,title,url,text:""}]`，可点击引用 |
| `TIP` / `TOOL_OPEN` / `TOOL_FIND` | 助手 | 丢弃并计数：导出没有留下可还原的内容 |

- 一个消息内多个搜索引擎片段的结果按书写顺序合并编号，正文 `[citation:N]` 指向合并后的第 N 条；
  超出范围时保留原文。
- 角色的判定：出现任意助手片段即为 `ASSISTANT`，否则 `USER`。实测不存在同一消息混合用户与助手片段。

### 13.3 分支与时间

- 主链取根节点 `children` 的第一条，沿单子节点一路向下。
- 同一提问下有多条助手回复（用户点过「重新生成」）→ 合并成一个只装回答节点的候选列表，
  可在 App 内左右切换；统计为 `alternativeReplies`。
- 其它分支（编辑提问、某轮之后另起）→ 按链展开后追加到会话末尾，避免静默丢消息；
  统计为 `branchMessages`。RikkaHub 没有通用的分支树结构，只能这样保留内容。
- `Conversation.createAt/updateAt` 取会话的 `inserted_at` / `updated_at`，缺失时回落到节点时间。

### 13.4 变动识别与决策

数据库新增 `conversationentity.source_updated_at`，记录**上次导入时导出文件里该对话的 `updated_at`**。

| 本地 | `source_updated_at` | 判定 |
|---|---|---|
| 不存在 | — | 新增，直接导入 |
| 存在 | 与本次导出的 `updated_at` 相同 | 无变化，跳过 |
| 存在 | 与本次导出的 `updated_at` 不同 | 有变化，交给用户决定 |
| 存在 | 为空（旧版本导入） | 回退比较本地 `update_at` 与导出的 `updated_at`；相同视为无变化 |

- 旧数据判定为无变化时，导入完成后把导出时间补写进 `source_updated_at`（自愈），
  下次导入不再依赖回退比较；本地继续对话不会影响该字段。
- 三种处理：
  - **保留**：不导入文件内容，本地会话保持原样。
  - **覆盖**：先 `deleteConversation`（节点、收藏、FTS 一并清理），再用导出内容重建；本地后加的消息会丢失。
  - **并存**：用 `stableUuid("deepseek:conversation:<id>#copy:<来源时间>")` 生成副本 id，
    标题追加 ` (DeepSeek yyyy-MM-dd)`，原会话保留；副本不参与后续导入匹配。
- 会话 id 用 `UUID.nameUUIDFromBytes("deepseek:conversation:<rawId>")`（UUID v3），
  消息/节点 id 同理，保证跨进程、跨版本稳定。

### 13.5 导入流程与界面

1. 「设置 → 备份与恢复 → 从其他APP导入 → 从DeepSeek导入」（已移到该分组第一项）。
2. 弹出助手选择对话框：列出全部助手，点击只切换高亮，「确认」后才继续（默认选中当前助手，带「当前使用」标识）。
3. 选择文件后先扫描：
   - 没有有变化的对话 → 直接导入并显示结果弹窗；
   - 有有变化的对话 → 启动独立 `DeepSeekImportPreviewActivity`。
4. 预览页（非对话框）：
   - 顶部三个等宽分类按钮：新增 / 有变化 / 无变化，名称在上、数量在下；
     选中项用 `primaryContainer`，未选中项透明背景 + `outlineVariant` 细边框。
   - 搜索框只过滤当前分类。
   - 「全部设为」只在「有变化」栏显示；所有有变化项选择一致时回显选中，否则保持未选中。
   - 有变化项默认不选任何操作；未选完点「开始导入」会切到「有变化」并提示。
   - 右上角信息图标打开说明弹窗（原对话 / 新对话 / 保留 / 覆盖 / 并存）；首次进入自动弹一次
     （SharedPreferences key `deepseek_import_preview_help_shown`）。
5. 结果弹窗：新增 / 已覆盖 / 已并存 / 已保留原对话 / 无变化 / 无法解析；可查看「已保留的对话」列表（支持搜索）。
6. 只有真正写入会话时才提示重启；预览页取消不提示重启（修复了旧实现“进入预览即触发重启提示”的问题）。

### 13.6 数据层

| 文件 | 改动 |
|---|---|
| `data/db/AppDatabase.kt` | 版本 33 → 34，新增 `AutoMigration(from = 33, to = 34)` |
| `data/db/entity/ConversationEntity.kt` | 新增 `@ColumnInfo("source_updated_at") sourceUpdatedAt: Long?` |
| `data/db/dao/ConversationDAO.kt` | 新增 `ConversationImportState(id, sourceUpdatedAt, updateAt)`、`getImportStates(ids)`、`updateSourceUpdatedAt` |
| `data/model/Conversation.kt` | 新增 `sourceUpdatedAt: Long?`，参与可空默认值序列化 |
| `data/repository/ConversationRepository.kt` | 实体双向映射；`getImportStates(ids)`（分批 900）、`backfillSourceUpdatedAt(values)`（单事务）；`ImportState` |
| `app/schemas/.../34.json` | 新增 Room schema 34 |

### 13.7 备份时间按渠道拆分

原先本地导出、WebDAV、S3 共用一个 `BackupReminderConfig.lastBackupTime`，
导致本地备份后 WebDAV / S3 页也显示该时间。

- 新增 `lastWebDavBackupTime`、`lastS3BackupTime`（默认 `0L`）。
- `BackupVM.recordBackupTime(channel)`：本地只更新全局；WebDAV / S3 同时更新各自字段。
- `WebDavTab` / `S3Tab` 改读各自字段，为 0 时显示「无记录」；提醒卡片仍用全局“任意渠道最近一次”。
- 旧数据没有渠道信息，升级后网络页会显示「无记录」，各自成功备份一次后恢复。

### 13.8 助手列表「当前使用」标识

- 抽屉的助手选择列表（`AssistantPickerSheet`）：当前助手行在编辑按钮左侧显示「当前使用」。
- 助手设置页（`AssistantPage`）：当前助手行在右侧操作菜单左边显示「当前使用」。
- 导入的助手选择对话框同样显示该标识，并用 `primaryContainer` 背景区分。

### 13.9 本次改动文件

- 新增：
  - `data/sync/importer/DeepSeekImporter.kt`（扫描、流式解析、决策导入、结果统计）
  - `ui/activity/DeepSeekImportPreviewActivity.kt`（独立预览页 + `DeepSeekImportSession`）
  - `ui/pages/backup/components/BackupAssistantPickerDialog.kt`
  - `ui/pages/backup/components/DeepSeekImportResultDialog.kt`
  - `app/src/test/java/.../DeepSeekImporterTest.kt`
  - `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/34.json`
- 修改：
  - `data/db/AppDatabase.kt`、`data/db/entity/ConversationEntity.kt`、`data/db/dao/ConversationDAO.kt`
  - `data/model/Conversation.kt`、`data/repository/ConversationRepository.kt`
  - `data/datastore/Settings.kt`（`BackupReminderConfig`）
  - `ui/pages/backup/BackupVM.kt`
  - `ui/pages/backup/tabs/ImportExportTab.kt`、`ui/pages/backup/tabs/WebDavTab.kt`、`ui/pages/backup/tabs/S3Tab.kt`
  - `ui/components/ai/AssistantPicker.kt`、`ui/pages/assistant/AssistantPage.kt`
  - `app/src/main/AndroidManifest.xml`（注册预览 Activity）
  - `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`

### 13.10 测试

`DeepSeekImporterTest`（8 个用例）：

- fragment → 角色 / 文本 / Reasoning / 搜索工具 / 引用改写 / 附件占位；
- 重新生成的多个回答合并为同一节点的候选；
- 编辑提问的分支保留为尾部节点；
- 时间解析与稳定 id（重复导入同一文件得到相同会话 id）；
- 无法解析的会话跳过而不中断整次导入；
- 顶层不是数组时抛出 `IllegalArgumentException`；
- 已存在会话按决策执行，`source_updated_at` 正确写入；
- `scan` 只读取 id / 标题 / 更新时间。

### 13.11 已知限制

- 官方导出不标记「当前分支」，主链按 `children` 书写顺序取第一条，存在多叶子时只能启发式选择。
- 导出包不含附件二进制，`FILE` 只保留占位文本；`TOOL_OPEN` / `TIP` 无法还原。
- 旧版本导入的会话没有 `source_updated_at`，首次导入会按本地 `update_at` 回退判断，
  本地改过的对话会进入「有变化」；导入一次完成补写后恢复精确判定。
- 判断依据是会话级 `updated_at`。DeepSeek 在置顶、重命名时也会刷新它，可能出现“内容没变但被列为有变化”的误报；
  这类条目选择保留即可，不会丢数据。

---

## 14. 其他助手的记忆工具（2026-11-11）

在现有的「修改其他助手」工具组里新增记忆管理能力，复用同一个 `AssistantConfig` 开关，不引入新的设置项。

### 14.1 工具清单

| 工具 | 作用 |
|---|---|
| `list_assistant_memories` | 列出目标助手的记忆文件：`path`、`title`、`description`、`version`、`updated_at`、`size_bytes` |
| `read_assistant_memory` | 按路径批量读取（单次最多 20 个），返回 `content` 与 `version`，不存在的路径放入 `not_found` |
| `write_assistant_memory` | 新建或整体覆盖单个文件；支持 `content` 或 `content_file`（绝对路径，UTF-8，≤1 MiB）；`if_version` 支持 `"new"` / 读取到的版本 |
| `delete_assistant_memory` | 删除单个文件，可带 `if_version` 防止误删已变化的文件 |
| `import_assistant_memory` | 从本地绝对路径导入：单个 `.md` 文件，或目录（最多 3 层、200 个文件，保留相对路径）；`overwrite` 默认 `false`（跳过已存在），`true` 时覆盖 |

### 14.2 目标解析与安全限制

- 目标助手按 id 或精确名称匹配（复用 `resolveAssistant`）。
- 以下情况直接拒绝，返回 `CANNOT_MODIFY_SELF`：
  - 目标就是当前对话的助手；
  - 目标使用全局记忆且当前助手也使用全局记忆（实际是同一个记忆库）。
- 所有写入走 `MemoryRepository`，因此保留原有的路径规范（必须以 `.md` 结尾、不允许隐藏目录、最长 128 字符）与 48 KiB 单文件上限。
- `import_assistant_memory` 读取的路径必须绝对、可读；目录扫描跳过隐藏文件，超过数量上限时提示导入子目录。
- 导入结果分三类返回：`imported`、`skipped`（已存在）、`failed`（读取失败或写入被拒绝，附原因）。

### 14.3 改动文件

| 文件 | 改动 |
|---|---|
| `data/ai/tools/local/AssistantConfigTools.kt` | `buildAssistantConfigTools` 增加 `memoryRepository` 参数；新增 5 个记忆工具与目标解析、路径收集辅助函数 |
| `data/ai/tools/local/LocalTools.kt` | 构造注入 `MemoryRepository`，`AssistantConfig` 分支传入 |
| `di/AppModule.kt` | `LocalTools` 构造参数增加一个依赖 |

### 14.4 说明

- 没有新增 `LocalToolOption`，沿用「修改其他助手」开关；关闭该开关时这些记忆工具也不会挂载。
- 工具只操作记忆库（Markdown 文件），不暴露模型、MCP、工作区等本机配置。
- 文件路径读取沿用提示词工具的做法，仅限 App 进程可读的绝对路径；Android 分区限制仍然存在。

---

## 15. 上游 3.2.0 同步（2026-11-11）

合并 `upstream/3.2.0`（`90269e39`，22 个提交、83 个文件 `+3623 / -364`），17 个文件冲突。3.2.0 没有数据库改动（Room 仍为 34），也没有工作区架构改动。

### 15.1 直接合入

- `ai`：Claude thinking 块签名 / redacted 回传、Mistral 推理模型多轮 422、`AgentHooks.shouldPause` 与 `Paused` 结束原因。
- 网络：新增 `network_security_config.xml`，在系统 CA 之外信任用户安装的 CA。
- 提供商：内置 STA1N、默认提供商全部开启、可用模型面板手动刷新。
- 设置页通知权限卡片（`LocalNetworkPermissionCard.kt` 重命名为 `PermissionCards.kt`，旧函数名保留）。
- 快捷消息短按追加 / 长按替换、Web 端 token 统计、调试页 500k token 对话、README 与翻译补全。
- 后台 shell 前台服务类型 `dataSync` → `specialUse`（fork 的 `BackgroundShellForegroundService` 与上游同源，直接命中）。

### 15.2 Phone Use（新功能）

> 该功能永远不会纳入修改范围，除了从上游按例同步的情况。兼容性完全由上游决定；本节的记录只是说明本次同步合入了什么，不代表 fork 会在此之上做适配。

- 工具（`PhoneUseTool`）、`PhoneUseManager`、工具 UI 在 `main` 源集；无障碍服务与手势指示器放在新增的 `app/src/github` 源集，Play 版不声明该服务。
- `LocalToolOption.PhoneUse`；`LocalTools` 构造新增 `PhoneUseManager`、`FilesManager`，`AppModule` 同步注入。
- 助手本地工具页：fork 的本地工具是全局 `Settings.localTools`，上游写的 `assistant.localTools` 改为 `enabledTools`。
- 开启 Switch 前要求悬浮窗；无障碍服务未开时跳到系统设置，开关照常打开。运行时由 `PhoneUseManager.isSupported`（`!IS_PLAY_STORE && SDK >= 30`）拦截。

### 15.3 自动压缩

- 设置字段 `enableAutoCompress` / `autoCompressThreshold` 已归类到 `BackupSettingsFields` 的 `SETTINGS` 资源，保证 `BackupSettingsFieldsTest` 的字段覆盖。
- `ChatService` 合入 `ReplyTarget`、`launchAutoCompressIfNeeded`、`compressMidTurn`、`insertCompressionCheckpoint`，保留 fork 的 shell 硬超时、子代理免审批、`ConversationInbox`。
- `AgentLoop`：fork 的 terminal 工具立即 `Completed` 与上游的 `toolsExecuted` / `shouldPause` 并存。
- `ChatVM` 同时保留 `backgroundTasks` 与 `sessionActivity`；`ChatList` 保留 `pathOpenEnabled` 并新增 `compressing`。

### 15.4 对话地图

`ChatListPreview` 手工合并：保留 fork 的多选 / 导出 / 背景，采用上游的固定搜索栏布局与 `produceState` + `conflate` 的后台计算，列表 key 改用 `PreviewRound.id`。

### 15.5 文档

README / README_ZH_CN / README_ZH_TW 补 Phone Use 一行；中文 README 继续使用 fork 的工作区描述，不采用上游的 proot 说法。

### 15.6 验证

按当次要求未执行编译与测试，合入后需要重新构建并跑 `./gradlew test`。

---

## 16. Skills 导入与导出（2026-11-13）

### 16.1 GitHub 导入改用 Git Trees API

`SkillsVM.importSkillFromGitHub` 不再逐目录调用 Contents API，改为参考 Chatbox 的做法：

- `GET /repos/{owner}/{repo}/git/trees/{ref}?recursive=1` 一次拿到整棵树（默认 `HEAD`，支持 URL 里的 `tree/{branch}/{sub/path}`）；
- 找出任意深度的 `SKILL.md`，一个技能 = 一个父目录，支持一次导入多个技能（上限 100 个）；
- 文件内容走 `raw.githubusercontent.com`，不消耗 GitHub API 配额；
- 树被截断、为空或接口失败时回退到旧的 Contents API，只导入根目录的 `SKILL.md`；
- 单文件上限 10 MiB、单技能最多 2000 个文件；格式不完整的技能跳过，不影响同仓库其他技能。

### 16.2 `view_skill` 支持读取图片

> 工具名从 `use_skill` 改为 `view_skill`（对齐 Open WebUI 的命名）；旧对话、旧工具提示词覆盖和子代理白名单里的 `use_skill` 会自动映射到新名。

`view_skill` 的 `path` 参数现在按扩展名区分：

- `png` / `jpg` / `jpeg` / `webp` / `gif` 返回 `UIMessagePart.Image("file://...")`，交给模型内置识图能力；是否支持由模型决定，本地不做判断；
- 其他文件仍按 UTF-8 文本返回。

### 16.3 新增 `export_skill` 工具（始终挂载，无独立开关）

- 把技能目录（或其中 `path` 指定的文件/子目录）复制到本地工作区；没有声明本地工作区时使用 sandbox；
- 默认目标 `skills/<skill-name>/`，可用 `destination` 指定工作区内的其他目录；
- `overwrite` 默认 false，已存在的文件跳过；目标路径做 canonical 校验，禁止逃出工作区；
- 只要助手的 skills 工具组挂载且存在可用技能，这个工具就存在，不新增设置项。

### 16.4 默认提示词

- `SkillsTools` 的 system prompt 说明 `view_skill` 可读文本/图片，以及 `export_skill` 用于执行脚本/处理二进制；
- `WorkspacePrompt` 默认模板新增对应两行，并带上 `{{skills_dir}}` 占位符，使 SSH 远程段按既有 `dropLinesWith` 逻辑删除这些行。

### 16.5 验证

按当次要求未执行编译。

---

## 17. 工具提示词编辑（2026-11-13）

- 设置页在「工具」后面新增「工具提示词」，按分类进入，分类内再选工具，不把所有编辑框堆在一页：
  - 工作区提示词（复用现有 `SettingWorkspacePromptPage`）
  - 技能：`view_skill`、`export_skill`
  - 记忆：`memory_read` / `memory_write` / `memory_str_replace` / `memory_append` / `memory_delete` / `memory_list`
  - 搜索：`search_web`、`scrape_web`
  - 对话检索：`recent_chats`、`conversation_search`、`conversation_read`
  - 本地工具：时间、剪贴板、TTS、屏幕时间、日历、图表、`ask_user`
  - 工作区工具：`read_file` / `write_file` / `edit_file` / `shell` / `shell_output` / `shell_kill`（`ssh_*` 复用同名基础工具的覆盖）
  - 助手配置：`list_assistants` / `get_assistant` / `update_assistant` 及其他助手记忆工具
- 存储：`Settings.toolDescriptionOverrides` / `toolSystemPromptOverrides`（工具名 → 文本），空文本表示使用内置默认；已纳入备份的 SETTINGS 字段。
- 应用：`ChatToolFactory` 在返回工具列表前统一替换 `description` / `systemPrompt`，不改各工具构建函数。
- 设置 → 扩展管理里的「提示词」改名为「提示词注入」。
- 未开放：子代理（`task`/`fleet` 的描述会动态列出当前启用的子代理，覆盖会丢列表；子代理自身提示词在子代理配置里）、MCP（描述由服务端提供）、Phone Use（跟随上游）。
- 编辑页：默认值直接预填并可编辑；右上角保存（无二次确认）与重置（二次确认）；未保存退出时弹窗询问保存还是丢弃，丢弃后直接返回上一级。
- 工作区工具默认值用 `{workspace_root}`、本地时间类工具用 `{timezone}` 占位符展示；保存覆盖后按用户文本原样注入，占位符不会自动替换。

---

## 18. 上游 3.3.0 同步分析（2026-11-17）

> 本次只完成调查与分类，**尚未合并代码**。冲突次数以 `3.2.0..HEAD` 的已提交历史统计，工作树里尚未提交的改动另计。

上游 `3.3.0`（`eb33917e chore: bump version to 3.3.0`）相对 `3.2.0` 共 19 个提交、90 个文件 `+5917 / -401`；版本号 3.3.0 / 197，minSdk 仍是 26（fork 为 24）。

数据库是本次最大的冲突点：

| | fork | 上游 3.3.0 |
|---|---|---|
| Room 版本 | 34 | 34 |
| 32 → 33 | fork 的工作区字段（`declared` / `directory` / `toolchain*` / SSH 字段） | `message_node` 复合索引 `(conversation_id, node_index)` |
| 33 → 34 | AutoMigration：`conversation.source_updated_at`（DeepSeek 导入） | AutoMigration：工作区 `storage_mounts` / `ssh_config` |
| 34 schema identityHash | `df49b7df...` | `4c94f9b4...` |

两边版本号相同但 schema 不同，不能把上游 `34.json` / `Migration_32_33` 直接拷进来。

### 18.1 可直接纳入（冲突低）

| 上游提交 | 内容 | 备注 |
|---|---|---|
| `3adeba47` | 请求体日志只在 debug 构建打印 | ai 模块；本地无改动 |
| `2898c3de`、`d3f128f3`、`14d60e44` | Gemini 单独下发的 thoughtSignature part 兼容 + trace 回放测试 | `GoogleProvider` / `GoogleStreamDecoder` 本地无改动；`ChatMessageCot.kt` 本地改过 1 次，约 3 行小适配 |
| `90ef90b7` | Vertex 服务账号支持 Interactions API | `ProviderSetting` / `GoogleProvider` / `InteractionsAPI` / `ProviderConfigure` 本地无改动 |
| `a75f3f9c` | Provider 测试连接弹窗点击模型搜索框后自动关闭 | `ProviderConnectionTester` / `SettingProviderDetailPage` 本地无改动，可整文件取上游 |
| `f361bbc1` | DeepSeek 语音合成与语音识别 | speech 模块、`SettingSpeechPage`、ASR/TTS 配置组件本地无改动；`ui/hooks/ASR.kt` 本地改过 1 次（AudioFocus / API 24），需保留 |
| `7677f649`、`64452581`、`b10c1685` | Phone Use 后台亮屏、截图位图回收、微信禁操作 | 按 fork 政策整块接受；相关文件本地无改动 |
| `66149047` | 自动压缩上下文默认开启 | `Settings.enableAutoCompress` 默认值 false → true；`Settings.kt` 本地改过 9 次，单行手工改，并同步测试断言 |
| `90269e39` | README 功能列表 | 可同步；fork README 的工作区描述保留 |
| `eb33917e` | 版本号 3.3.0 / 197 | 按 fork 发布策略决定 |

### 18.2 需要额外适配

1. **`c1c3c4d1` message_node 复合索引**
   - 上游把它放在 `Migration_32_33`；fork 的 32→33 已被工作区字段占用，不能改。
   - 新建 `Migration_34_35`：创建 `index_message_node_conversation_id_node_index (conversation_id, node_index)`，删除 `index_message_node_conversation_id`。
   - 同步 `MessageNodeEntity` 索引、`AppDatabase.version = 35`、生成 `35.json`；保留 fork 的 33/34 schema。
   - 不要合并上游 `34.json`（字段和 identityHash 都不同）。

2. **`cfb7d961` 助手回复按段落拆分气泡**
   - `MarkdownParagraphs.kt` 与测试可直接新增。
   - `ChatMessage.kt` 本地改过 2 次（路径链接、工具/气泡渲染），只移植“开启分段后按段落生成多个 `ChatMessageBubble`”的分支，保留 fork 的其余逻辑。
   - `DisplaySetting` 新增 `splitAssistantBubbleByParagraph`（`Settings.kt` 本地改过 9 次，注意与现有字段合并）。
   - `SettingPreferencesUIPage` 本地无改动，可直接加上游设置项。
   - 流式生成时只有最后一段使用 `streaming = true`。

3. **`27976e4d` 安全模式清除超大设置**
   - `SafeModeActivity` 本地无改动，UI 可直接取上游。
   - `PreferencesStore` 本地改过 8 次、`SettingsFields` 改过 7 次：需要机械合入 `largeStoredFieldsFlow(minChars)`、`resetStoredField(keyName)`、`SettingsField.reset(settings)`，并确认 fork 的 `SETTINGS_FIELDS` / `MISSING_KEY_DEFAULTS` 覆盖所有字段。
   - 新增文案上游暂为硬编码英文，可按 fork 的 i18n 规范补 key。

4. **`8e2afbd3` 后台任务详情长命令滚动**
   - `BackgroundTasksSheet.kt` 本地改过 1 次（fork 的后台 shell 与上游同源），手工移植 `BoxWithConstraints` + 命令区最高一半高度 + 内部滚动。

5. **`f361bbc1` 的 `ui/hooks/ASR.kt`**
   - 上游新增 DeepSeek ASR 分支；fork 在该文件保留 `AudioFocusController` / API 24 兼容，需要逐段合并。

6. **字符串资源**
   - 上游 3.3.0 在全部语言新增/修改约 27 个 key；fork 的 `strings.xml` 有 23 个本地提交。
   - 用 `tools/i18n` 按 key 合并，不要整文件覆盖；新增文案需补齐 fork 支持的语言目录。

### 18.3 不建议直接纳入

1. **`fab0c077` 本地工作区挂载共享存储**
   - 依赖上游的 PRoot + Rootfs + `WorkspaceBackend` + bind mount：新增 `WorkspaceStorageMount`、`WorkspaceEntity.storage_mounts` / `ssh_config`、`WorkspaceStorageMountCard`，`WorkspaceManager` / `WorkspaceRepository` / `LocalWorkspaceBackend` 大改，github 源集声明 `MANAGE_EXTERNAL_STORAGE`。
   - fork 的工作区已重构为 Android 原生 + termex，`WorkspaceManager` 的目录、HOME/TMP、SSH 字段与上游完全不同，直接合并会破坏工作区功能。
   - 如需要该能力，应基于 fork 架构另做设计（例如把外部目录作为工作目录或额外可读写根），不要引入 Rootfs 挂载表。

2. **`f93764e6` DeepSeek 当前分支导入**
   - 上游新增独立 `DeepSeekImporter.kt`，只沿 `current_message_id` 的祖先链导入当前分支。
   - fork 已有自己的 `DeepSeekImporter`（分支展开、变动识别、独立预览页、覆盖/保留/并存），语义和数据结构都不同，不能替换。
   - 可选后续增强：在 fork 的预览页增加“只导入当前分支”选项，复用上游 `currentBranch` 的思路。

3. **上游 34 schema / `Migration_32_33`**
   - 与 fork 的迁移链、字段、identityHash 冲突；已安装用户会触发 Room 校验失败，不能直接使用（见 18.4）。

### 18.4 数据库迁移建议

- 保留 fork 的 `Migration_32_33`（工作区字段）与 `AutoMigration(33,34)`（`source_updated_at`）。
- 只把 message_node 索引作为新的 `Migration_34_35` 引入；版本号升到 35，生成 `app/schemas/.../35.json`。
- 若以后引入共享存储等其他字段，另起 `Migration_35_36`，不要和索引迁移混在一起。
- 验证两条路径：已有 34 库升级到 35；全新安装直接建 35。

### 18.5 建议合并顺序

1. ai（Gemini / Vertex / RequestLog）+ ProviderConnectionTester。
2. speech（DeepSeek TTS / ASR）+ SafeMode + 自动压缩默认值。
3. Phone Use（按政策整块接受）。
4. message_node 索引迁移（34 → 35）。
5. 段落气泡（`MarkdownParagraphs` + `ChatMessage` + `DisplaySetting` + 设置页 + 字符串）。
6. 暂不做：工作区共享存储、上游 DeepSeek 导入替换。

验证：`./gradlew test`（Windows 上 Datastore 测试的既有失败除外）、`./gradlew assembleGithubRelease`、旧库升级、全新安装、Phone Use 在 Android 11+ 的行为、fork `minSdk 24` 下新代码的 API 检查。

### 18.6 结论

- 约一半上游改动（ai、speech、Provider、SafeMode、Phone Use、自动压缩）可以低冲突纳入。
- 段落气泡需要改 `ChatMessage` / `Settings` / 字符串，属于中等适配。
- message_node 索引必须新开迁移，不能复用上游 34 迁移。
- 工作区共享存储和 DeepSeek 导入实现与 fork 架构/既有功能冲突，暂不纳入。
- 下一步可按 18.5 的顺序继续合并，每批单独验证。

### 18.7 本次已纳入（2026-11-17 第一批）

已直接取 `3.3.0` 版本或手工合并以下无冲突改动：

- **ai**：`RequestLog`、Claude/OpenAI/Gemini 的调试日志条件打印、Gemini thoughtSignature、Vertex Interactions API、`ai/build.gradle.kts` 的 `buildConfig`。
- **Provider**：`ProviderConnectionTester` 重构、`SettingProviderDetailPage` 的弹窗修复、`ProviderConfigure` 的 Vertex 服务账号配置。
- **Phone Use**：`ChatFloatingWindows` 亮屏、`PhoneUseAccessibilityService` 位图回收、`PhoneUseTool` 微信禁操作。
- **Speech**：DeepSeek TTS/ASR（`ASRProviderSetting`、`TTSProviderSetting`、`Wav`、`DeepSeekASRController`、`DeepSeekTTSProvider`、`MiMoASRController`、`TTSManager`、设置页与配置组件）；`ui/hooks/ASR.kt` 手工补 DeepSeek 分支，保留 AudioFocus/API 24 逻辑。
- **自动压缩**：`Settings.enableAutoCompress` 默认值改为 `true`，`SettingsFieldsTest` 样本同步。
- **Gemini 空文本**：`ChatMessageCot` 忽略只带 thoughtSignature 的空文本，不打断思考块。
- 顺手修复 `SettingsFieldsTest` 对既有 `toolNameDisplay` 字段的期望（样本值和 `STORED_KEYS`），这是本 fork 之前新增设置项遗留的测试缺口。

**未纳入**：段落气泡、SafeMode、`message_node` 索引、工作区共享存储、README/版本号。DeepSeek 导入并未直接替换为上游版本，而是以 App 导出解析为基础重新适配，见第 19 节。

验证：`:app:compileGithubReleaseKotlin` BUILD SUCCESSFUL；`SettingsFieldsTest` 与 `PathLinkifyTest` 共 24 个用例通过。

---

## 19. DeepSeek 导入切换为 App 导出（2026-11-17）

### 19.1 背景

上游 3.3.0 新增的 `DeepSeekImporter` 面向 **DeepSeek App 端导出**：

```
manifest.json                 {"export_format_version":3}
chat_session_index.json       会话摘要列表
chat_sessions/<id>.json       messages 通过 parent_id 组成分支树
chat_sessions/<id>_files/     附件
```

fork 之前的实现面向网页版导出的 `conversations.json`，只支持网页版格式。现改为以 App 导出为主，同时保留 fork 原有的预览、覆盖/保留/并存、分支展开和稳定 id。

### 19.2 新实现

`app/.../data/sync/importer/DeepSeekImporter.kt`（主源集）：

- 校验 `manifest.json` 存在且 `export_format_version >= 3`；缺失或版本过低抛 `DeepSeekAppExportException`。
- `scan` 优先读 `chat_session_index.json`，没有 index 时回退逐个读会话元信息；预览不解析消息、不保存附件。
- 以 `message.id` + `parent_id` 构建树，`current_message_id` 回溯出主链。
- 同一父节点下的多条 ASSISTANT 回复合并为 `MessageNode` 候选列表，`selectIndex` 指向当前分支。
- 主链之外的分支继续展开追加，统计 `branchMessages`。
- fragment 映射：`THINK`、`FILE`、`TOOL_SEARCH/OPEN/IMAGE_SEARCH/FIND`、`REQUEST/RESPONSE/CONTENT_RISK/PARTIAL_FILE_READ/TEMPLATE_RESPONSE/TIP`。
- 附件按 `export_path` 从 ZIP 读取，通过 `saveAttachment` 存入上传目录；缺失时保留文件名占位。
- 沿用 fork 的稳定 id：`deepseek:conversation:<sessionId>`、`deepseek:node:<sessionId>:<messageId>`、`deepseek:branch:<sessionId>:<messageId>`。

共享数据模型提取到 `DeepSeekImportModels.kt`（`DeepSeekImportResult`、`DeepSeekConversationSummary`、`DeepSeekImportDecision`、`DeepSeekImportAction`、`DeepSeekAttachment`、异常）。

### 19.3 网页版实现保留但不编译

- 旧 `DeepSeekImporter.kt` 移到 `app/src/legacy/java/.../DeepSeekImporterWeb.kt`。
- 旧测试移到 `app/src/legacy/test/.../DeepSeekImporterWebTest.kt`。
- `app/src/legacy` 不在 Android Gradle 默认 sourceSets 中，因此保留代码供参考，不参与编译，也不会进入 APK。

### 19.4 UI/错误提示

- `BackupVM` 构造新增 `Application`，附件保存改用 `FilesManager.saveUploadFromBytes`。
- `ImportExportTab` 捕获 `DeepSeekAppExportException`，提示「请确保导出的 zip 来源于 DeepSeek App，网页版导出不受支持。」
- 新增字符串 `backup_page_import_deepseek_app_only`、`backup_page_import_deepseek_missing_attachment`。
- 预览页、覆盖/保留/并存流程不变。

### 19.5 真机问题与修复

导入 App 导出时曾报 `String index out of range: -24`，Logcat 堆栈定位到：

```
java.net.DefaultFileNameMap.getContentTypeFor
java.net.URLConnection.guessContentTypeFromName
DeepSeekImporter.parseMessage
```

Android 的 `URLConnection.guessContentTypeFromName` 对少数附件文件名会抛 `StringIndexOutOfBoundsException`。已改为 `runCatching` 包住，并按常见扩展名兜底；单个附件保存失败也只保留占位文本，不再中断整次导入。

### 19.6 验证

- 新增 `DeepSeekImporterTest`（5 个用例）：manifest 缺失/版本过低、index 扫描、当前分支 + 候选回复 + 分支展开、附件与缺失占位。
- `:app:testGithubDebugUnitTest --tests "...DeepSeekImporterTest"` BUILD SUCCESSFUL。
- `:app:compileGithubReleaseKotlin`、`assembleGithubRelease` BUILD SUCCESSFUL。
- 真机导入 `deepseek_data-2026-11-17.zip` 成功：1989 个会话中新增 176、无变化 1813、有变化 0；没有预览决策是因为来源时间全部一致。

---

## 20. 上游 3.3.0 当前同步状态（2026-11-17）

### 20.1 已合入

- ai：Gemini thoughtSignature、Vertex Interactions API、请求体日志仅 debug、相关 trace 依赖。
- Provider：测试连接弹窗修复、`ProviderConnectionTester` 重构。
- Phone Use：后台亮屏、截图位图回收、微信禁操作（按 fork 政策整块接受）。
- Speech：DeepSeek TTS / ASR 及设置页适配。
- 自动压缩默认开启。
- DeepSeek 导入切换为 App 导出解析，保留预览/覆盖/保留/并存、分支展开、候选回复、附件与稳定 id。
- 网页版 DeepSeek 导入实现与测试移到 `app/src/legacy`，保留但不参与编译。
- 真机导入大包验证通过；`String index out of range` 已定位为 `URLConnection.guessContentTypeFromName` 的 Android 异常并修复。

### 20.2 尚未适配的上游 3.3.0 项目

| 项目 | 上游提交 | 当前状态 | 原因 / 下一步 |
|---|---|---|---|
| 助手回复按段落拆分气泡 | `cfb7d961` | 已合入（第 21 节） | 新增 `MarkdownParagraphs` 与单测，`ChatMessage` 只替换助手气泡渲染分支，展示设置里新增「按段落拆分气泡」开关 |
| 安全模式清除超大设置 | `27976e4d` | 已合入（第 21 节） | 按 fork 的字段表机械合入 `largeStoredFieldsFlow`、`resetStoredField`、`SettingsField.reset`；`SafeModeActivity` 取上游 UI，文案改为字符串资源 |
| `message_node` 复合索引 | `c1c3c4d1` | 已合入（第 21 节） | 新开 `Migration_34_35`，`MessageNodeEntity`、`AppDatabase.version = 35`、生成 `35.json`，并补 `Migration_34_35_Test`；保留 fork 的 33/34 schema |
| 本地工作区挂载共享存储 | `fab0c077` | 不纳入 | 依赖上游 PRoot/Rootfs + `WorkspaceBackend` + bind mount，与 fork 的 Android 原生 + termex 工作区架构不兼容；如需要应在 fork 架构上重做 |
| 上游 DeepSeek 当前分支导入实现 | `f93764e6` | 不直接替换 | fork 已按 App 导出格式重新适配（第 19 节），保留自己的预览/覆盖/并存和分支展开；上游的 `currentBranch` 只导当前分支，不能直接替换 |
| 上游 33/34 schema 与 `Migration_32_33` | `fab0c077`、`c1c3c4d1` | 不能使用 | 与 fork 的迁移链、Workspace 字段和 identityHash 冲突，直接使用会让已安装用户的 Room 校验失败 |
| github 源集 `MANAGE_EXTERNAL_STORAGE` 与共享存储提示词 | `fab0c077` | 未采用 | 随工作区共享存储功能一起暂不纳入 |
| 版本号与 README 功能列表 | `eb33917e`、`90269e39` | 已更新（第 21 节） | 版本号升到 3.3.0 / 197；README 补 SillyTavern 角色卡导入，暗色模式与多模态输入按 fork 现状保留 |
| 上游新增的 Gemini trace / DeepSeek 导入测试 | `d3f128f3`、`14d60e44`、`f93764e6` | 场景已覆盖，录制资源未移植 | 混用本地函数与 Google Search 的签名保留由 `GoogleToolCombinationTest` 覆盖；上游录制 trace 与 `GoogleThoughtSignaturePartTest` 未移植（第 21.4 节） |
| 上游 SafeMode 新增文案本地化 | `27976e4d` | 已处理（第 21 节） | 新增 `safe_mode_large_fields_*` / `safe_mode_clear*` 键，8 个语言目录全部补齐；「取消」复用现有 `cancel` |

---

## 21. 上游 3.3.0 剩余适配（2026-11-17 第二批）

第 20.2 节里剩余的三项代码适配与 SafeMode 文案本地化已完成，仍保留的「不纳入」项见 21.4。

### 21.1 `message_node` 复合索引（`c1c3c4d1`）

- `MessageNodeEntity` 的索引改为 `Index("conversation_id", "node_index")`。
- 新增手写 `Migration_34_35`：建 `index_message_node_conversation_id_node_index`、删 `index_message_node_conversation_id`，不动表数据（非唯一索引，重复 `node_index` 不影响迁移）。
- `AppDatabase.version = 35`，`Migration_34_35` 注册在 `AppDatabaseFactory.addMigrations`；`AutoMigration(33, 34)` 保持原样。
- 由 KSP 生成 `app/schemas/.../35.json`（version 35，identityHash `fb921f9c...`），没有采用上游 `33.json` / `34.json`。
- 新增 `Migration_34_35_Test`（androidTest）：顺序分页命中新索引且无 `TEMP B-TREE`；同一会话重复 `node_index` 迁移后可完整分页。

### 21.2 助手回复按段落拆分气泡（`cfb7d961`）

- 新增 `ui/components/message/MarkdownParagraphs.kt`：空行拆段，代码块、`$$` / `\[` 公式块内不拆，列表项的缩进续行归回上一段；配 8 个单测。
- `ChatMessage`：只替换「助手气泡」分支，按段落生成多个 `ChatMessageBubble`，仅最后一段 `streaming = true`；fork 的 `onOpenPath`、`SelectableContent`、路径链接等逻辑保留。消息本身仍是一条，复制 / 分支 / 导出不受影响。
- `DisplaySetting.splitAssistantBubbleByParagraph`（默认 `false`），`SettingPreferencesUIPage` 在开启助手气泡后显示该开关。
- 8 个语言目录补齐 `setting_display_page_split_assistant_bubble_*`。

### 21.3 安全模式清除超大设置（`27976e4d`）

- `SettingsStore.largeStoredFieldsFlow(minChars)` 直接看盘上原始字符串长度；`resetStoredField(keyName)` 走 `update`，内存与盘同时清理，避免启动后第一次写入又把超大值写回去。
- `SettingsField.reset(settings)` 回落到 `MISSING_KEY_DEFAULTS`（如未选模型仍指向内置自动模型）；认不出的 key 不做任何事。
- `SafeModeActivity` 取上游 UI：列出 ≥500 万字符的字段、二次确认清除、清除时禁用按钮；「进入应用」改主按钮并排在第一位，「切换助手」改描边按钮；说明文案去掉「可以切换助手解决问题」。
- 文案改为字符串资源 `safe_mode_large_fields_title` / `safe_mode_large_fields_chars` / `safe_mode_clear` / `safe_mode_clear_title` / `safe_mode_clear_message`，8 个语言目录全部补齐（不再保留上游的硬编码英文）。
- `SettingsStoreTest` 增加「清除超大字段后盘上变小、其他字段保留」用例。

### 21.4 仍未纳入

| 项目 | 原因 |
|---|---|
| 本地工作区挂载共享存储（`fab0c077`） | 依赖上游 PRoot/Rootfs + bind mount，与 fork 的 Android 原生 + termex 工作区架构不兼容，需在 fork 架构上重做 |
| github 源集 `MANAGE_EXTERNAL_STORAGE` | 随共享存储功能一起暂不引入 |
| 上游 DeepSeek 当前分支导入实现（`f93764e6`） | fork 已按 App 导出格式重新适配（第 19 节），语义与数据结构不同，不替换 |
| 上游 33/34 schema 与 `Migration_32_33` | 与 fork 迁移链、Workspace 字段、identityHash 冲突 |
| 上游 Gemini 搜索 trace 录制资源（`d3f128f3`、`14d60e44`） | 混用函数 + Google Search 的签名场景已由 fork 的 `GoogleToolCombinationTest` 手写覆盖（流式解析、toolCall/toolResponse 原样回传、续轮 buildContents）；录制 trace 与 `GoogleThoughtSignaturePartTest` 属可选回归资产，需要时再取 |

### 21.5 版本号与 README

- `app/build.gradle.kts`：`versionCode 197` / `versionName "3.3.0"`，与上游 `eb33917e` 对齐。
- README（中/繁）补「SillyTavern 角色卡导入」；上游删掉的暗色模式、多模态输入两行按 fork 现状保留，工作区描述继续用 fork 的 Android 原生 + termex 版本。
- 上游 README 的英文版差异（Phone Use、供应商顺序）fork 已具备，不再改动。

### 21.6 验证

- `:app:compileGithubDebugKotlin`、`:app:compileGithubDebugAndroidTestKotlin` BUILD SUCCESSFUL。
- `MarkdownParagraphsTest`（8 例）、`SettingsFieldsTest` 通过。
- `SettingsStoreTest` 在 Windows 上仍是既有的 `FileStorage` 重命名失败（7 例，含新增用例），与本次改动无关；新增用例逻辑与上游一致，需在 Linux/macOS 或真机验证。
- `:app:assembleGithubRelease`（含 `lintVitalGithubRelease`）BUILD SUCCESSFUL，3.3.0 / 197 的 APK 可正常打包。
- 真机需验证：旧库 34 → 35 升级、全新安装建 35、段落气泡在流式生成中的表现、安全模式清除超大世界书后不再闪退。

---

## 22. obscura Android 构建与浏览器 CLI（2026-11-17）

### 22.1 发布位置

- 源码与补丁：`https://github.com/AcE77505/obscura` 的 `android` 分支（fork 自 `h4ckf0r0day/obscura`，基于 `v0.2.7`）。
- 预编译产物：Release `android-v0.2.7`：
  - `libobscura-arm64-v8a.so`（约 74 MB）
  - `libobscura-x86_64.so`（约 78 MB）
  - `obscura-snapshot-arm64-v8a.bin` / `obscura-snapshot-x86_64.bin`（约 2.4 MB）
  - `SHA256SUMS`
- 上游源码不能直接交叉编译，修改清单见 22.2。

### 22.2 源码修改

1. `android/patched/v8`：`RUSTY_V8_ARCHIVE` / `RUSTY_V8_SRC_BINDING_PATH` 只对 android target 生效，避免 host build script 下载不存在的 Windows/Linux V8 归档。
2. `android/patched/deno_core`：Android 的 `errno_location()` 使用 bionic `__errno()`。
3. `crates/obscura-js/build.rs`：交叉编译改为读取 `OBSCURA_SNAPSHOT_FILE`，不再用 host V8 生成快照。
4. `crates/obscura-js/Cargo.toml`：删除 `[build-dependencies] deno_core`，避免为 host 构建 V8。
5. V8 归档：`android/prepare-v8.sh` 下载 `satouriko/rusty-v8-mobile@v150.4.0` 的 Android 静态库，合并 43 个 `simdutf__*` 转发、`__libcpp_verbose_abort`、compiler-rt 的 `clear_cache.o`。
6. 构建参数：NDK API 29、`sysroot`、`libunwind`、`c++_static`、BoringSSL 的 CMake/Go、16 KB 页对齐。

### 22.3 构建方式

- Linux/WSL/macOS：`./android/build.sh --abi arm64-v8a`（或 `x86_64`）。
- Windows：`.\android\build.ps1 -Abi arm64-v8a`（需要 Git Bash、Rust、NDK、Go、LLVM/libclang、CMake、Python、curl）。
- CI：`.github/workflows/android.yml`；`snapshot` job 在原生 arm64/x64 runner 生成快照，`build` job 交叉编译，tag `android-v*` 触发 release。
- 快照：`android/snapshots/obscura-snapshot-<abi>.bin`，缺失时从 Release 下载；arm64/x64 host 可用 `android/make-snapshot.sh` 生成。
- 输出：`android/dist/<abi>/libobscura.so`。

### 22.4 rikkahub 接入

- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libobscura.so` 已放入仓库；`BundledExecutables` 把它链接成 `obscura` 并加入工作区 shell 的 `PATH`。
- 不再注册 `browser_fetch` / `browser_screenshot`；`BrowserTool.kt` 与对应测试已删除。
- 新增 `LocalToolOption.ObscuraCli`（旧 `browser` 值在 `normalized()` 中迁移），开关只影响系统提示是否声明 `obscura` CLI，不注册工具 schema。
- 开启 `ObscuraCli` 会自动开启 `WorkspaceShell` 和 `WorkspaceReadFile`。
- `WorkspaceReminderTransformer` 注入 `DEFAULT_OBSCURA_CLI_PROMPT`（可在「设置 → 工具提示词 → 本地工具 → obscura_cli」覆盖）。
- 提示词包含 `fetch` / `--dump` / `--eval` / `--selector` / `-s` / `--allow-private-network` / `--obey-robots` / `--stealth` 的用法和限制。
- 看图：`obscura fetch <url> -s shot.png --quiet` 后用 `read_file` 读取 PNG；`read_file` 对图片返回 `UIMessagePart.Image`（上限 8 MB），模型需支持图片。

### 22.5 APK 打包

- 保持原有逻辑：`ndk.abiFilters = arm64-v8a, x86_64`；`splits.abi` 生成 arm64/x86 split + universal；`useLegacyPackaging = true`，native lib 继续压缩。
- 未采用 `.apks` / `noCompress`：`libobscura.so` 需要 `extractNativeLibs=true` 才能进入 `nativeLibraryDir` 执行；split APK 若 Stored 则 `nativeLibraryDir` 为空，obscura 无法运行。临时添加的 bundletool 任务已移除。
- 版本：`versionCode 197` / `versionName "3.3.0"`。

### 22.6 验证

- obscura fork CI：`snapshot arm64/x64`、`build arm64/x64`、`release` 全部成功；Release `android-v0.2.7` 资产齐全。
- 真机（arm64 Android 10+）：`libobscura.so --version`、`fetch https://example.com --eval 'document.title'` 正常。
- rikkahub：`:app:compileGithubDebugKotlin`、`:app:assembleGithubRelease`（3.3.0/197）成功；APK 包含 `lib/arm64-v8a/libobscura.so` 与 `lib/x86_64/libobscura.so`。
- 待真机验证：工作区 shell 中 `obscura` 命令、`read_file` 读截图、提示词开关的实际调用。

### 22.7 注意

- `libobscura.so` 约 74/79 MB，两个 ABI 合计约 153 MB；universal APK 会明显变大，建议分发 arm64 split。
- 当前是“CLI + 提示词”方案，没有 `browser_fetch` 的参数 schema；模型需要按提示词自行拼命令，长页面要用 `head -c` 或 `--selector`。
- 交叉编译必须有目标架构快照；Windows 不能本地生成 arm64 快照，需从 Release 下载。
- 交互式浏览（点击/填表/登录）尚未接入；后续可考虑 `obscura mcp` 复用现有 MCP 体系。

---

## 23. 日志导出到本地（2026-11-23）

### 23.1 背景

「请求日志」页面展示的内容只存在于 `common/.../android/Logging.kt` 的 `recentLogs` 内存列表（最多 100 条），进程被回收或重启后全部丢失，也没法把日志拿给别人排查。本次在页面里补一个导出入口，不改动日志本身的存储方式。

### 23.2 实现

`app/.../ui/pages/log/LogPage.kt`：

- 在「记录请求」开关卡片下方新增「保存到本地」卡片（`SaveLogsCard`，`Download01` 图标，整行可点）。
- 点击时对 `Logging.getRecentLogs()` 按时间倒序取快照，用 `JsonInstantPretty` + `ListSerializer(LogEntry.serializer())` 编码为 JSON（条目带 `type` 判别字段），再通过 `ActivityResultContracts.CreateDocument("application/json")` 让用户选择保存位置。
- 默认文件名 `rikkahub-logs-yyyyMMdd-HHmmss.json`；真正的写入发生在 `CreateDocument` 回调里，用 `contentResolver.openOutputStream(uri)` 在 `Dispatchers.IO` 写入，成功/失败以 Toast 提示。
- 没有日志时只提示「暂无可保存的日志」，不弹文件选择器。
- 导出内容包含完整请求头与请求体（可能含 API Key 等敏感信息），只写到用户自己选定的位置，不做任何上传；导出后内存日志保持不变。

### 23.3 新增字符串

`log_page_save_logs`、`log_page_save_logs_desc`、`log_page_save_logs_empty`、`log_page_save_logs_success`、`log_page_save_logs_failed`（`values` / `values-zh`，其余语言回退英文）。

### 23.4 验证

- `:app:compileGithubDebugKotlin` BUILD SUCCESSFUL。
- `:app:assembleGithubRelease` BUILD SUCCESSFUL。
- 待真机验证：系统文件选择器落盘、导出的 JSON 可正常打开、导出的请求体/响应头内容完整。

---

## 24. 提示词精简（2026-11-23）

### 24.1 背景与测量

对一次真实请求（`/v1/chat/completions`，28,492 字符 system prompt）逐段测量，发现四类可压缩内容：

| 段落 | 精简前 | 精简后（约） | 主要问题 |
|---|---:|---:|---|
| `<memory_system>` + `<memory_format>` | 14,543 | 9,673 | 主对话每轮注入完整的写入格式规则；规则与 format 互相重复 |
| `**Skills**` + 技能列表 | 10,735 | 7,341 | 37 个技能每个 4 行 XML，描述最长 959 字符 |
| `<workspace>` | 2,183 | ~1,300 | 技能/工具/环境说明重复，旧模板还带 `workspace_shell` 旧名 |
| 助手管理段 | 582 | ~390 | 与工具 description 重复 |

合计约 28.5K → 19.2K 字符（-33%）。这是静态 system prompt 的节省，不含工具 schema 与对话历史。

### 24.2 记忆提示词

`app/.../data/ai/prompts/MemoryPrompts.kt`：

- `buildMemoryPrompt`（主对话）不再注入完整 `MEMORY_FORMAT_PROMPT`（约 4.3K），改为新的 `<memory_write_rules>`（约 1.1K），只覆盖“用户明确要求写入”所需的最小规则：frontmatter、`[stated]`、文件选择、敏感信息、编辑前 read/if_version、忘掉语义。
- `MEMORY_SYSTEM_RULES` 3.0K → 1.6K：`When to read` 4→3 条、`How to apply` 8→5 条、`When to write` 8→2 条；删除与 format、与 `memory_*` 工具 description 重复的 `Never store` / `if_version` 细节。
- `MEMORY_SYSTEM_INTRO` 压成两行。
- `buildMemoryConsolidationPrompt`（后台整理器）行为不变，仍使用完整 `MEMORY_FORMAT_PROMPT`。

### 24.3 工作区提示词

- `WorkspacePrompt.kt`：默认模板精简。目录说明合并成一行；技能与内置技能合并成一行；上传目录只读说明压成一句；删除重复的 `view_skill` / `export_skill` 用法（`**Skills**` 段已经说明）与逐工具说明。
- `WorkspaceReminderTransformer.kt`：
  - `buildToolList` 输出一行 `- File tools: ...`，保留 `toolPrefix`，远程段仍是 `ssh_*` 工具名。
  - `buildShellEnv` 把 `bash` 版本与额外工具合并成 `- shell runs bash (v); extra tools: ...`，去掉重复的 `Available commands` 行。
- **注意**：`DEFAULT_WORKSPACE_PROMPT` 只在设置里的 `workspacePrompt` 为空时使用。已经保存过自定义模板的设备不会自动更新，需要在「设置 → 工具 → 工作区提示词」点一次重置；旧模板里的 `workspace_shell` / `workspace_edit_file` 是历史工具名。

### 24.4 技能列表

`app/.../data/ai/tools/SkillsTools.kt`：

- `<available_skills>` 从每个技能 4 行改为单行 `<skill name="..." description="..."/>`。
- 列表中的 description 截断到 300 字符（完整描述仍由 `view_skill` 读取）；`MAX_SKILL_DESCRIPTION_LENGTH` 的 1024 硬上限不变。
- `escapeXml` 增加 `"` → `&quot;`，避免属性值闭合注入。
- 只影响系统提示里的列表，不影响 `view_skill` 的读取与执行；技能元数据本身未被修改。

### 24.5 助手管理段

- `data/ai/tools/local/AssistantConfigTools.kt` 与 `ToolPromptCatalog.kt` 的默认文本同步压缩（582 → ~390 字符），保留“只在用户要求时改、不能改当前助手、数组字段是 upsert、长文本用 *_file、replace_* 才清空”。

### 24.6 未改动的部分

- 用户记忆数据（`/profile.md`、`/preferences.md`、`/areas/*.md`）未修改。`profile.md` 里有一段 881 字符的静态文件索引，与运行时 `<memory_listing>` 重复，且不会随文件增删更新；这是用户数据，留给用户在 App 内自行处理。
- 未给 `MEMORY_FORMAT_PROMPT` 增加“不要把文件清单抄进 profile”的防回归规则；如果以后整理器再次生成索引段，需要再补。

### 24.7 验证

- `MemoryPromptTest`、`SkillsToolsTest` 通过。
- `assembleGithubRelease`：BUILD SUCCESSFUL（3m 31s，含 `lintVitalGithubRelease` 与 R8）。
- 待真机验证：重置工作区提示词后的实际注入、技能路由是否仍准确、用户说“记住”时主对话写入是否正常。

### 24.8 提示词改动的可见标记

- 新增 `app/.../data/ai/prompts/PromptCustomization.kt`：
  - `Settings.hasCustomizedWorkspacePrompt`：`workspacePrompt.trim()` 与 `DEFAULT_WORKSPACE_PROMPT.trim()` 不同即视为改过；旧版本保存下来的旧模板也会命中。
  - `Settings.hasCustomizedToolPrompt(name)`：`toolDescriptionOverrides` 或 `toolSystemPromptOverrides` 中存在该工具即视为改过；保存时改回默认会自动移除覆盖，所以不存在“假标记”。
- `ui/pages/setting/SettingToolPromptsPage.kt`：
  - 工作区提示词行、各分类行、分类内的具体工具行，右侧显示纯文本「已修改」。
  - 样式模仿助手列表的「当前使用」：`labelMedium` + 纯文本；颜色用 `tertiary`，与「当前使用」的 `primary` 区分。
  - 只表示“这一项被改过”，不展示内容、数量或修改次数。
- 新增字符串 `tool_prompts_modified`（`values` = Modified，`values-zh` = 已修改；其余语言回退英文）。
- 新增 `PromptCustomizationTest`：覆盖默认模板不标记、自定义/旧模板标记、按工具命中的覆盖。测试通过。

### 24.9 反馈中的软件问题修复

- **新增 `{{cur_datetime}}` 占位符**（`PlaceholderTransformer`）：
  - 默认助手模板用的是 `{{cur_date}}`，一直能正常替换；反馈里的 `{{cur_datetime}}` 来自自定义助手提示词，此前不在占位符集合里，会原样留给模型。
  - 新占位符输出本地化日期 + 短时间，精确到分钟；新增字符串 `placeholder_current_datetime`（`values` / `values-zh`，其余语言回退英文）。
  - 影响：它写在 system prompt 里会随请求变化，降低 prompt cache 命中；默认模板不使用，只有显式写它的助手受影响。
- **workspace 的 shell 表述去掉字面矛盾**（`WorkspaceReminderTransformer.buildShellEnv`）：
  - 原来第一行说命令直接跑在 `/system/bin/sh` + toybox，第二行又说 `shell` 用 bash。
  - 现在第一行只说“Android 上使用 toybox 工具，不是 Linux 发行版、没有包管理器”；`shell` 用 bash、额外工具列表放在第二行。未启用工具链时仍明确说明回退到 `/system/bin/sh`。
- **未修的低收益重复**：workspace 段的 “Read SKILL.md” 与 `**Skills**` 段说明；`<memory_write_rules>` 与 `MEMORY_SYSTEM_RULES` 的少量重叠。前者在未启用技能工具时是唯一提示，后者是主对话写入的兜底，保留。
- 验证：`:app:compileGithubDebugKotlin` 与 `PromptCustomizationTest` 通过。
- 注意：自定义工作区提示词需要在设置里重置一次，才能看到新的 shell 文案。

---

## 25. obscura 编译期可选（2026-11-23）

### 25.1 背景

obscura 的 `libobscura.so` 在 `jniLibs/arm64-v8a` 与 `jniLibs/x86_64` 合计约 153 MB，但它是可选的浏览器 CLI。之前所有构建都会带上，导致不需要它的用户也必须下载大包。

### 25.2 开关

- Gradle property：`-PwithObscura=true`，默认 `false`。
- `app/build.gradle.kts`：
  - 顶层 `includeObscura` 读取该 property。
  - `defaultConfig` 生成 `BuildConfig.INCLUDE_OBSCURA`。
  - `packaging.jniLibs.excludes += "**/libobscura.so"` 只在开关关闭时生效；`.so` 源文件仍留在仓库，但不会进入 APK。
- 代码分支（全部读 `BuildConfig.INCLUDE_OBSCURA`）：
  - `SettingsNormalization.withLocalToolMigration()`：关闭时移除旧的 `Browser` / `ObscuraCli` 开关，避免从带 obscura 的版本切回来时残留。
  - `WorkspaceReminderTransformer`：关闭时不注入 `DEFAULT_OBSCURA_CLI_PROMPT`。
  - `ToolPromptCatalog`：关闭时从「设置 → 工具提示词」列表中过滤 `obscura_cli`。
  - `AssistantLocalToolPage`：关闭时隐藏「Obscura CLI」开关。
- `BundledExecutables` 无需改动：`.so` 不存在时 `prepare()` 自然跳过，shim 不进入 PATH。

### 25.3 构建命令

```bash
# 不带 obscura（默认）
./gradlew assembleGithubRelease

# 带 obscura
./gradlew assembleGithubRelease -PwithObscura=true
```

两次构建会写同一个输出目录，需要分别保存产物；本次已复制为 `*-without-obscura.apk` / `*-with-obscura.apk`。

### 25.4 验证

| 产物 | 不带 obscura | 带 obscura |
|---|---:|---:|
| arm64-v8a | 16 MB | 49 MB |
| x86_64 | 16 MB | 51 MB |
| universal | 18 MB | 87 MB |

- 两次 `assembleGithubRelease` 均 BUILD SUCCESSFUL（3m 43s / 3m 39s，含 lintVital 与 R8）。
- 包体积差与 `libobscura.so` 的大小一致，说明排除生效。
- 待真机验证：不带包的工作区 shell 中 `obscura` 命令不存在、设置页无 Obscura 入口；带包行为与之前一致。

### 25.5 注意

- 两个版本使用同一个 `versionCode`，不能同时安装；切换安装前需卸载或覆盖安装。
- 从带 obscura 的版本覆盖安装到不带版本时，数据库里的 `obscura_cli` 开关会在设置归一化时被移除，不会留下无效开关。

---

## 26. 上游 3.3.1 同步（2026-11-24）

### 26.1 范围

`upstream/master` 从 `e033a47d` 前进到 `2435dbdf`（3.3.1），共 35 个提交。其中 3.3.0 的 19 个（`e033a47d..eb33917e`）此前已按第 20、21 节手工适配合入 fork，本次只处理 3.3.0 之后的 16 个。

### 26.2 已引入（无冲突，10 个）

| 上游提交 | 内容 |
|---|---|
| `bfac2440` | 发布时压缩 mapping 再上传 |
| `2cd6acac` | 修复开启 VPN 时应用内更新包无法下载 |
| `ce0e3607` | 允许删除内置默认助手，至少保留一个 |
| `a9e9e53a` | 移除自动关闭未使用模板 Issue 的工作流 |
| `1ed0a77c` | 新增商汤日日新图标并扩展 NVIDIA 匹配规则 |
| `358e2931` | bettertext 升级至 v0.1.6 |
| `e4646a77` | Markdown 导出写入消息时间 |
| `48a68f87` | 模型名最多显示两行 |
| `fbfb9926` | Google 模型列表缺 displayName 时回退到 modelId |
| `2435dbdf` | 版本号 3.3.1（`versionCode 198` / `versionName "3.3.1"`） |

统一用 `git cherry-pick -x` 逐个引入，保留了上游提交号。全部无冲突。

### 26.3 待适配（有冲突，6 个）

| 上游提交 | 冲突文件 | 说明 |
|---|---|---|
| `63a5192d` 消息概览标出压缩点 | `ChatListPreview.kt` | 小：加 `PreviewRound.checkpoint` 与分组条件 |
| `b7cf74dd` 识别被系统停掉的无障碍服务 | `LocalTools.kt` | 小：新增 `isCheckedInSystem` 回调参数 |
| `a8508e23` Android 9/10 导入含 `#` 文件名附件崩溃 | `DeepSeekImporter.kt`、测试 | 中：fork 导入已改为 App 导出格式，需迁移修复 |
| `a2479770a` 打 tag 自动发布工作流 | `.agents/skills/publish-release/SKILL.md` | 小：工作流本身新增，冲突只在被 fork 改过的 skill 文档 |
| `2d93676e` 安全模式页导出/导入备份 | `BackupManager.kt`、`DatabaseBackup.kt` | 中：需接到 fork 的备份分项模型 |
| `41d77f1e` 快速入门页 | 11 处（DI、备份页、8 个 strings.xml） | 大：7 个新文件可直取；其 `DeepSeekMigrator` 依赖上游导入实现，不兼容 fork |

### 26.4 顺带修复

- `BackupSettingsFields`（`data/datastore/BackupItem.kt`）缺少 `toolNameDisplay` 字段，导致 `BackupSettingsFieldsTest` 一直失败。该问题由 `c6c08d90` 引入 `toolNameDisplay` 时漏更新备份资源清单造成，与本次同步无关；本次补上并让测试通过。

### 26.5 验证

- `:app:compileGithubDebugKotlin` BUILD SUCCESSFUL。
- `:app:testGithubDebugUnitTest`：582 个测试，9 个失败，全部是 Windows 上 `FileStorage` 的 `atomicMoveTo` 重命名失败（与第 7.4 节记录的同类问题一致）；`BackupSettingsFieldsTest` 的失败已修复。
- 待办：6 个冲突提交的适配，见 26.3。
