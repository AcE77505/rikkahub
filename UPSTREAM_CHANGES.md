# 相对上游的改动说明

本文档记录当前 fork 相对 `upstream/master` 的改动，便于后续维护和向上游同步。

- 远程：`origin` 是个人 fork（`AcE77505/rikkahub`），`upstream` 是原项目（`rikkahub/rikkahub`）。
- 截至 2026-11-08，`master` 已领先 `upstream/master` 60 个提交；下文的 SSH 工作区、工作区工具/提示词、记忆编辑章节是此前已经提交的历史改动。
- 本批次（2026-11-08）：36 个已跟踪文件修改（`+976 / -189`），另有 4 个新增 Kotlin 文件和 1 个 issue 草稿；不含构建产物，详见第 5 节。
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
