# 相对上游的改动说明

本文档记录当前 fork 相对 `upstream/master` 的改动，便于后续维护和向上游同步。

- 远程：`origin` 是个人 fork（`AcE77505/rikkahub`），`upstream` 是原项目（`rikkahub/rikkahub`）。
- 2026-11-08 已合并 `upstream/master`（3.1.2）；合并前 `master` 领先上游 60 个提交。下文的 SSH 工作区、工作区工具/提示词、记忆编辑章节是此前已经提交的历史改动，第 7 节是本次上游同步的取舍记录。
- 本批次（2026-11-08）：36 个已跟踪文件修改（`+976 / -189`），另有 4 个新增 Kotlin 文件和 1 个 issue 草稿；不含构建产物，详见第 5 节。
- 本批次（2026-11-10）：新增 DeepSeek 官方导出导入（含变动识别与独立预览页）、备份时间按渠道拆分、助手列表「当前使用」标识；数据库版本 33 → 34。详见第 13 节。
- 本批次（2026-11-11）：在「修改其他助手」工具组新增记忆管理（列出/读取/写入/删除/按文件或目录导入），复用原有开关，不新增设置项。详见第 14 节。
- 旧版文档以 `6304a995` 为基线记录当时未提交的改动；这些改动已经进入 `master`，相关章节保留作历史说明。

---

## 0. 快速总览

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
