# 设置项目录（开发者用）

> 用途：快速定位某个设置项在界面的哪个位置，以及理清设置项的分布是否合理。**不是给终端用户看的文档。**
> 生成时间 2026-11-25。路径按左侧第一个 `→` 表示进入下一层。

配套阅读：`docs/references/onboarding-ux-research.md`（新手引导体验调研）。

## 0. 层级总览

```
设置
├─ 通用设置
│   ├─ 偏好设置
│   │   ├─ 主题
│   │   ├─ 通知
│   │   ├─ 通用
│   │   ├─ 界面
│   │   └─ 网络
│   ├─ 助手
│   ├─ 扩展管理
│   ├─ 工具
│   │   ├─ 全部本地工具
│   │   ├─ 子代理（→ 详情 / 默认工具 / 运行记录）
│   │   ├─ 工作区列表
│   │   └─ 终端目录
│   └─ 工具提示词（→ 分类 → 单个工具）
├─ 模型与服务
│   ├─ 默认模型
│   ├─ 服务商（→ 详情）
│   ├─ 搜索服务（→ 详情）
│   ├─ 语音服务（→ 详情）
│   ├─ 媒体生成（→ 详情）
│   ├─ MCP（→ 详情）
│   └─ 网页服务器
├─ 数据设置
│   ├─ 备份与恢复
│   └─ 聊天记录存储
└─ 关于
    ├─ 关于
    ├─ 快速入门
    ├─ 文档
    ├─ 请求日志
    ├─ 赞助
    └─ 分享
```

## 1. 设置项明细

### 设置主页（`Screen.Setting`，`SettingPage.kt`）

**通用设置**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 偏好设置 | 入口 → 偏好设置 | `setting_page_preferences` |
| 助手 | 入口 → 助手 | `setting_page_assistant` |
| 扩展管理 | 入口 → 扩展管理 | `setting_page_extensions` |
| 工具 | 入口 → 工具 | `setting_page_tools` |
| 工具提示词 | 入口 → 工具提示词 | `setting_page_tool_prompts` |

**模型与服务**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 默认模型和提示词 | 入口 → 默认模型 | `setting_page_default_model` |
| 提供商 | 入口 → 服务商 | `setting_page_providers` |
| 搜索服务 | 入口 → 搜索服务 | `setting_page_search_service` |
| 语音服务 | 入口 → 语音服务 | `setting_page_tts_service` |
| 媒体 | 入口 → 媒体生成 | `setting_page_media` |
| MCP | 入口 → MCP | `setting_page_mcp` |
| Web服务器 | 入口 → 网页服务器 | `setting_page_web_server` |

**数据设置**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 数据备份 | 入口 → 备份与恢复 | `setting_page_data_backup` |
| 存储管理 | 入口 → 聊天记录存储 | `setting_page_chat_storage` |

**关于**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 关于 | 入口 → 关于 | `setting_page_about` |
| 快速入门 | 入口 → 快速入门 | `setting_page_quick_start` |
| 使用文档 | 入口 → 文档 | `setting_page_documentation` |
| 请求日志 | 入口 → 请求日志 | `setting_page_request_logs` |
| 赞助 | 入口 → 赞助 | `setting_page_donate` |
| 分享 | 入口 → 分享 | `setting_page_share` |

### 偏好设置（`SettingPreferencesPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 主题 | 入口 → 主题 | `setting_page_preferences_theme` |
| 通知 | 入口 → 通知 | `setting_page_preferences_notification` |
| 常规 | 入口 → 通用 | `setting_page_preferences_general` |
| 界面偏好设置 | 入口 → 界面 | `setting_page_preferences_ui` |
| 网络 | 入口 → 网络 | `setting_page_preferences_network` |

### 偏好设置 → 界面（`SettingPreferencesUIPage.kt`）

**消息显示**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 显示用户头像 | 开关 | `setting_display_page_show_user_avatar_title` |
| 显示助手消息气泡 | 开关 | `setting_display_page_show_assistant_bubble_title` |
| 按段落拆分气泡 | 开关 | `setting_display_page_split_assistant_bubble_title` |
| 气泡不透明度 | 滑块 | `setting_display_page_bubble_opacity_title` |
| 聊天列表模型图标 | 开关 | `setting_display_page_chat_list_model_icon_title` |
| 显示模型名称 | 开关 | `setting_display_page_show_model_name_title` |
| 在消息中显示日期和时间 | 开关 | `setting_display_page_show_datetime_in_message_title` |
| 显示Token和上下文统计 | 开关 | `setting_display_page_show_token_usage_title` |
| 显示思考内容 | 开关 | `setting_display_page_show_thinking_content_title` |
| 自动折叠思考 | 开关 | `setting_display_page_auto_collapse_thinking_title` |
| 启用 LaTeX 渲染 | 开关 | `setting_display_page_enable_latex_rendering_title` |
| 引号内文字高亮 | 开关 | `setting_display_page_enable_quote_highlight_title` |
| 聊天字体 | 选项 | `setting_display_page_chat_font_family_title` |
| 自定义字体 | 入口（字体导入） | `setting_display_page_custom_font_title` |
| 聊天字体大小 | 滑块 | `setting_display_page_font_size_title` |

**代码显示**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 代码块自动换行 | 开关 | `setting_display_page_code_block_auto_wrap_title` |
| 代码块自动折叠 | 开关 | `setting_display_page_code_block_auto_collapse_title` |
| 显示行号 | 开关 | `setting_display_page_show_line_numbers_title` |

**会话菜单**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 显示请求前缀 | 开关（默认关） | `setting_display_page_show_request_prefix_title` |

### 偏好设置 → 通用（`SettingPreferencesGeneralPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 启动时新建对话 | 开关 | `setting_display_page_create_new_conversation_on_start_title` |
| 按Enter发送 | 开关 | `setting_display_page_send_on_enter_title` |
| 自动压缩上下文 | 开关 | `setting_display_page_auto_compress_title` |
| 触发比例 | 滑块（依赖上一项） | `setting_display_page_auto_compress_threshold_title` |
| 消息导航按钮 | 开关 | `setting_display_page_show_message_jumper_title` |
| 消息导航按钮位置 | 开关 | `setting_display_page_message_jumper_position_title` |
| 自动滚动 | 开关 | `setting_display_page_enable_auto_scroll_title` |
| 输入栏背景效果 | 开关 | `setting_display_page_background_effect_title` |
| 背景效果类型 | 选项（依赖上一项） | `setting_display_page_background_effect_type` |
| 消息生成触觉反馈 | 开关 | `setting_display_page_enable_message_generation_haptic_effect_title` |
| 跳过图片编辑 | 开关 | `setting_display_page_skip_crop_image_title` |
| 粘贴长文本为文件 | 开关 | `setting_display_page_paste_long_text_as_file_title` |
| 文本长度阈值 | 滑块（依赖上一项） | `setting_display_page_paste_long_text_threshold_title` |
| 音量键翻页 | 开关 | `setting_display_page_volume_key_scroll_title` |
| 滚动幅度 | 滑块（依赖上一项） | `setting_display_page_volume_key_scroll_ratio` |

**TTS设置**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 默认播放速度 | 滑块 | `setting_tts_page_default_playback_speed` |
| TTS仅朗读引用内容 | 开关 | `setting_display_page_tts_only_read_quoted_title` |
| TTS不朗读括号内的内容 | 开关 | `setting_display_page_tts_read_outside_brackets_title` |
| 生成后自动播放TTS | 开关 | `setting_display_page_auto_play_tts_title` |
| 语音模式回复时继续聆听 | 开关 | `setting_display_page_voice_listen_while_replying_title` |

### 偏好设置 → 通知（`SettingPreferencesNotificationPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 显示更新 | 开关 | `setting_display_page_show_updates_title` |
| 生成消息后启用通知 | 开关 | `setting_display_page_notification_message_generated` |
| 实时更新通知 | 开关（Android 15+） | `setting_display_page_live_update_notification` |
| 后台悬浮窗 | 开关 | `setting_page_preferences_notification_floating_window` |
| 授予悬浮窗权限 | 权限入口（依赖上一项） | `setting_page_preferences_notification_overlay_permission` |

### 偏好设置 → 主题（`SettingPreferencesThemePage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 动态颜色 | 开关 | `setting_page_dynamic_color` |
| 主题 | 入口 → 主题列表 | `setting_page_theme_setting` |
| AMOLED 暗色模式 | 开关 | `setting_display_page_amoled_dark_mode_title` |

### 偏好设置 → 网络（`SettingPreferencesNetworkPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 代理 | 开关 + 输入 | `setting_page_preferences_network_proxy` |
| 自动重试 | 开关 | `setting_page_preferences_network_auto_retry` |

### 工具（`SettingToolsPage.kt`）

**动态工具**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 动态工具 | 开关 | `setting_tools_dynamic_title` |
| 始终加载 | 入口（依赖上一项） | `setting_tools_dynamic_always_loaded` |

**常用工具**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 时间信息 | 开关 | `assistant_page_local_tools_time_info_title` |
| 询问用户 | 开关 | `assistant_page_local_tools_ask_user_title` |
| 全部本地工具 | 入口 → 全部本地工具 | `setting_tools_all_local_tools` |

**代码与文件**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 读取文件 | 开关 | `workspace_tool_read_file` |
| 写入文件 | 开关（依赖读取） | `workspace_tool_write_file` |
| 编辑文件 | 开关（依赖读取） | `workspace_tool_edit_file` |
| 执行命令 | 开关 | `workspace_tool_shell` |
| 超时提示阈值 | 选项（依赖 shell） | `setting_tools_shell_overtime_threshold` |
| 超时后自动停止命令 | 开关（依赖 shell） | `setting_tools_shell_auto_stop` |
| 允许后台运行 | 开关（依赖 shell） | `setting_tools_shell_background_enabled` |
| 每对话并发上限 | 选项（依赖上一项） | `setting_tools_shell_background_limit` |
| 完成后插入提示 | 开关（依赖上一项） | `setting_tools_shell_background_notify` |
| 工具审批 | 入口 → 审批弹窗 | `setting_tools_tool_approval` |

**工作区**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 工作区 | 入口 → 工作区列表 | `setting_tools_workspace` |
| 终端目录 | 入口 → 终端目录 | `setting_tools_terminal_dir` |

**点击路径的优先打开方式**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| MT 管理器（%s） | 单选（每个已安装 MT 一项） | `setting_path_open_mt_with_package` |
| 系统 | 单选 | `setting_path_open_system` |
| 每次询问 | 单选 | `setting_path_open_ask` |

**高级**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 修改其他助手 | 开关 | `tool_assistant_config` |
| 子代理 | 入口 → 子代理 | `setting_tools_subagents` |

**显示**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 工具标识符 | 选项（三选一） | `setting_tools_tool_identifier_display_title` |

### 全部本地工具（`AssistantLocalToolPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| JavaScript 引擎 | 开关 | `assistant_page_local_tools_javascript_engine_title` |
| 剪贴板 | 开关 | `assistant_page_local_tools_clipboard_title` |
| 文字转语音 | 开关 | `assistant_page_local_tools_tts_title` |
| 屏幕时间 | 开关 | `assistant_page_local_tools_screen_time_title` |
| 日历 | 开关 | `assistant_page_local_tools_calendar_title` |
| 图表显示 | 开关 | `assistant_page_local_tools_chart_display_title` |
| 系统事务 | 开关 | `assistant_page_local_tools_system_intent_title` |
| 定位 | 开关 | `assistant_page_local_tools_location_title` |
| Obscura CLI | 开关（仅含 obscura 构建） | `assistant_page_local_tools_obscura_cli_title` |
| Phone Use | 开关（仅 github + Android 11+） | `assistant_page_local_tools_phone_use_title` |

### 工具提示词（`SettingToolPromptsPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 工作区提示词 | 入口 → 工作区提示词 | `setting_tools_workspace_prompt` |
| 技能 | 入口 → 分类 | `tool_prompts_category_skills` |
| 记忆 | 入口 → 分类 | `tool_prompts_category_memory` |
| 搜索 | 入口 → 分类 | `tool_prompts_category_search` |
| 对话检索 | 入口 → 分类 | `tool_prompts_category_conversation` |
| 本地工具 | 入口 → 分类 | `tool_prompts_category_local_tools` |
| 工作区工具 | 入口 → 分类 | `tool_prompts_category_workspace_tools` |
| 动态工具 | 入口 → 分类 | `tool_prompts_category_dynamic_tools` |
| 助手配置 | 入口 → 分类 | `tool_prompts_category_assistant_config` |

### 子代理（`SettingSubAgentsPage.kt` / `SubAgentDetailPage.kt`）

**全局设置**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 默认子代理模型 | 选项（模型选择） | `subagent_default_model` |
| 并发上限 | 选项 | `subagent_concurrency_limit` |
| 运行记录 | 入口 → 运行记录 | `subagent_runs` |

**默认工具模板**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 默认工具模板 | 入口 → 工具模板 | `subagent_default_tools` |
| 新建子代理 | 入口 → 新建子代理 | `subagent_new` |

**基本**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 名称 | 输入 | `subagent_name` |
| 描述 | 输入 | `subagent_description` |
| 系统提示词 | 输入（多行） | `subagent_system_prompt` |

**模型与思考**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 聊天模型 | 选项 | `subagent_chat_model` |
| 思考预算 | 滑块 | `subagent_thinking_budget` |
| 温度 | 滑块 | `subagent_temperature` |

**权限**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 只读 | 开关 | `subagent_readonly` |
| 派发时需要审批 | 开关 | `subagent_requires_approval` |
| 记住本次对话的批准 | 开关 | `subagent_remember_approval` |

**允许工具**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 使用默认工具模板 | 开关 | `subagent_use_default_tools` |
| 其他工具名 | 入口 | `subagent_extra_tools` |

**运行限制**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 最大步数 | 输入 | `subagent_max_steps` |
| 超时（秒） | 输入 | `subagent_timeout_seconds` |

### 网页服务器（`SettingWebPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 端口 | 输入 | `setting_page_web_server_port` |
| 仅本地主机 | 开关 | `setting_page_web_server_localhost_only` |
| 启用身份验证 | 开关 | `setting_page_web_server_jwt_enable` |
| 访问密码 | 输入（依赖上一项） | `setting_page_web_server_password` |
| 局域网地址 | 只读 | `setting_page_web_server_lan_address` |
| mDNS地址 | 只读 | `setting_page_web_server_mdns_address` |
| 本机地址 | 只读 | `setting_page_web_server_local_address` |

### 搜索服务（`SettingSearchPage.kt`）

**通用选项**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 结果数量 | 选项 | `setting_page_search_result_size` |

*服务商*

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 添加提供商 | 入口 → 添加服务商 | `setting_page_search_add_provider` |

### 服务商（`SettingProviderPage.kt` / `SettingProviderDetailPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 添加提供商 | 入口 → 添加 | `setting_provider_page_add_provider` |
| 导入供应商配置 | 入口 → 从配置导入 | `setting_provider_page_import_dialog_title` |

### MCP（`SettingMcpPage.kt`）

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 添加一个即可开始使用 | 空态提示 | `setting_mcp_page_add_one_to_get_started` |
| 导入MCP服务器 | 入口 → 导入配置 | `setting_mcp_page_import_title` |

### 媒体生成（`SettingMediaPage.kt`）

**提供商**

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| 添加提供商 | 入口 → 添加 | `setting_media_page_add_provider` |
| 素材存储 | 入口 → 素材存储 | `setting_media_page_asset_storage` |

### 快速入门（`QuickStartPage.kt`）

*两个 Tab*

| 设置项 | 类型 | 字段 / key |
|---|---|---|
| DeepSeek | Tab | `quick_start_page_tab_deepseek` |
| 其他服务商 | Tab | `quick_start_page_tab_general` |

## 2. 分布观察（供调整时参考）

以下是整理过程中发现的、与「设置项应该放在哪里」相关的事实，不是改动建议。

### 2.1 深度

设置项最深到第 4 层：

```
设置 → 偏好设置 → 界面 → 消息显示 → 某个开关
设置 → 工具 → 代码与文件 → 从属选项
设置 → 助手 → 详情 → 子代理
```

超过 3 层的项目前只有：偏好设置下的 5 个子页、工具的从属配置、子代理详情。

### 2.2 「偏好设置」与顶层混杂

顶层「设置」本身有 20 个条目，其中 6 个是纯入口（偏好设置、助手、扩展管理、工具、工具提示词、快速入门），11 个是二级页面入口，3 个是动作（赞助、分享、文档）。

「偏好设置」里又套了 5 个子页，其中「通用」与顶层「通用设置」名称相近但内容不同：

| 名称 | 路径 | 内容 |
|---|---|---|
| 通用设置 | 设置 → 通用设置（分组名） | 只是一组入口的标题，本身不可点 |
| 通用 | 设置 → 偏好设置 → 通用 | 启动行为、输入、压缩、TTS 等 20 项 |

这是唯一的名称重复。其余分组标题（模型与服务、数据设置、关于）与子页标题没有冲突。

### 2.3 依赖关系（换位时要注意）

这些设置项之间存在「父关闭则子无效」的关系，重排时必须保持父在前：

| 父项 | 子项 |
|---|---|
| 读取文件 | 写入文件、编辑文件 |
| 命令执行 | 超时阈值、自动停止、后台命令（后台命令下又有并发上限、完成提示） |
| 启用动态工具 | 始终加载 |
| 自动压缩上下文 | 触发比例 |
| 输入栏背景效果 | 背景效果类型 |
| 粘贴长文本为文件 | 文本长度阈值 |
| 音量键滚动 | 滚动比例 |
| 网页服务器 JWT | 访问密码 |
| 悬浮窗 | 悬浮窗权限 |
| 子代理「使用默认工具」 | 额外工具 |

界面上这些子项的处理方式不统一：工具页在父项关闭时**置灰**（2026-11-25 改动后），其余的多数是**不显示**。

### 2.4 按形态分类的数量

| 形态 | 数量 | 典型位置 |
|---|---|---|
| 开关 | 约 70 | 偏好设置各子页、工具页、本地工具页 |
| 单选 / 选项 | 约 18 | 工具标识符、路径打开方式、字体、背景效果 |
| 滑块 | 9 | 气泡不透明度、字体大小、阈值类 |
| 入口（进入下一层） | 约 35 | 设置主页、工具页、子代理页 |
| 只读展示 | 3 | 网页服务器的地址三行 |

### 2.5 快速入门页的位置

「快速入门」目前挂在「设置 → 关于」分组下，与「关于」「文档」「请求日志」「赞助」「分享」并列。它承担的是新手上手引导，但位置在最后一组。

这一点与 `onboarding-ux-research.md` 第 5 节相关：调研显示新手引导应尽早出现在路径上，而不是排在「关于」里。
