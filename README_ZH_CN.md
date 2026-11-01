<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>RikkaHub</h1>

一个原生Android LLM 聊天客户端，支持切换不同的供应商进行聊天 🤖💬

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文

点击链接加入群聊 👉 [【RikkaHub】](https://qm.qq.com/q/I8MSU0FkOu)

</div>

<div align="center">
  <img src="docs/img/poster.png" alt="RikkaHub" width="800" />
</div>


## 🚀 下载

🔗 [前往官网下载](https://rikka-ai.com/download)（推荐）

🔗 [前往 Google Play 下载](https://play.google.com/store/apps/details?id=me.rerere.rikkahub)

> [!WARNING]
> RikkaHub 存在许多 fork 版本，fork 版本出现问题与 RikkaHub 无关，请谨慎使用 fork 版本，避免隐私泄露或者过度索要权限问题。

## 🍴 这个 Fork 与上游的区别

这个 Fork 基于 [RikkaHub](https://github.com/rikkahub/rikkahub)。如果你已经用过上游，先看这里；下面没列出的部分保持上游行为。

### 工作区：原生 shell + SSH，不再使用 proot rootfs

- 上游使用基于 proot 的 Linux 工作区；本 Fork 移除 proot/rootfs，命令直接跑在 Android 原生 shell（`/system/bin/sh` + toybox）。
- 可选 Termux/Linux 工具链，提供 bash 和额外命令。
- 工作区支持本地和 SSH 远程；SSH 支持密码/密钥认证和主机指纹确认。
- 文件工具改为 `read_file`、`write_file`、`edit_file`、`shell`；旧对话里的 `workspace_*` 名字通过别名继续可用。

### 子代理

- 新增 `task` 工具：主助手可以把代码修改、调查、审查等独立工作派发给子代理。
- `fleet` 可以并行派发多个独立子代理，受会话并发上限约束。
- 子代理默认流式预览：`task` / `fleet` 卡片显示阶段、耗时和最近活动。
- 子代理用 `complete_subtask` 提交终局报告；已完成或已取消的运行可以用 `continue_from` 继续。
- 子代理在独立上下文运行，只回传最终报告，不会把中间过程带进主对话。
- 在 **设置 → 工具 → 子代理** 配置：聊天模型、思考预算、温度、只读、允许工具、审批、最大步数和超时。
- 结果以 `sa_...` 持久化，可用 `read_subagent_result` 分页读取；取消也会保留 partial。
- 子代理不能再派发子代理。
- 定义全局共享，每个助手单独启用。

### 工具审批

- 工具审批由 **设置 → 工具 → 工具审批** 的全局开关控制。
- 编辑/写入工作区外路径不再额外叠加一个绕过开关的隐藏审批。
- 子代理可以在当前对话内记住一次批准，单独开关放在“派发时需要审批”下面。

### 其他 Fork 改动

- 记忆条目支持结构化编辑、拖动排序和更快的预览。
- 工作区工具改名并兼容旧对话；修复 MCP 开关状态、工作区提示词和终端目录入口。

> 上面没有列出的核心部分（提供商、聊天界面、MCP、技能、Web、媒体生成、翻译等）保持与上游兼容。

> 子代理详细设计：[SUBAGENT_PLAN.md](SUBAGENT_PLAN.md)

## 💖 赞助商

| 赞助商 | 介绍 |
|:---:|:---|
| <img src="docs/sponsors/aihubmix.png" alt="Aihubmix" width="50" /><br /><b>Aihubmix</b> | 感谢 <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> 的资金支持。我们推荐使用 aihubmix 作为全球主流模型的一站式服务平台。（OpenAI、Claude、Google Gemini、DeepSeek、Qwen 以及数百种其他模型）。 |
| <img src="docs/sponsors/api-mart.png" alt="APIMart" width="50" /><br /><b><a href="https://go.apimart.ai/gh-rikkahub">APIMart</a></b> | 感谢 APIMart 赞助了本项目！APIMart 是专注 AI 图片/视频生成的低价 API 平台，GPT-Image-2 低至 $0.006/张，1 美元可出图 160+ 张。图片、视频一套异步 API 通吃，提交任务拿 ID、回调取结果，跑批万张不超时、换模型不改代码。按量付费、无月费，通过<a href="https://go.apimart.ai/gh-rikkahub">此注册链接</a>注册即可开用。 |
| <img src="docs/sponsors/suixiang.jpg" alt="随想AI中转" width="50" /><br /><b><a href="https://sui-xiang.com">随想AI中转</a></b> | 感谢<a href="https://sui-xiang.com">随想AI中转</a>对本项目的赞助！随想AI中转 是一家可靠高效的 API 中继服务提供商，提供 Claude、Codex、Gemini 等的中继服务。注重隐私的中转站·无数据倒卖·无模型掺水，隐私，透明，极速售后。新账户注册每日签到就送 0.5 元测试额度，充值额度 1:1，无需订阅，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。99.9% 可用性，关键调用从不掉队。 |
| <img src="docs/sponsors/ztest.png" alt="真测 ztest.ai" width="50" /><br /><b><a href="https://ztest.ai">真测 ztest.ai</a></b> | 感谢<a href="https://ztest.ai">真测 ztest.ai</a>对本项目的赞助！真测 ztest.ai 是一个 AI 中转站模型检测平台，检测结果数据全公开，23 项探针覆盖协议、身份、能力、内容完整性、安全性、性能六大维度，交叉印证识别伪造与降级。作为独立第三方验证平台，实时监测 AI 中转站的模型真实性、响应质量与服务可用性。 |
| <img src="docs/sponsors/maru.png" alt="MaruCode" width="50" /><br /><b><a href="https://api.muteki.site/register?aff=Rikkahub&promo=Rikkahub">MaruCode</a></b> | <b><a href="https://api.muteki.site/register?aff=Rikkahub&promo=Rikkahub">MaruCode</a></b> 是一家偶尔做做慈善的小破站 API，自营号池，主要提供 Codex、Claude Code、GPT Image 等主流模型，支持 Websocket 协议，明码标价(Codex 0.25x, CC 1.5x)，透明汇率(1:1)，<a href="https://api.muteki.site/register?aff=Rikkahub&promo=Rikkahub">新用户注册送 2 刀</a>。<a href="https://images-2.muteki.site">生图工作台🖼️</a> |
| <img src="docs/sponsors/naapi.png" alt="钠API" width="50" /><br /><b><a href="https://naapi.cc/register?aff=rikkahub">钠API</a></b> | 感谢<a href="https://naapi.cc/register?aff=rikkahub">钠API</a>对本项目的赞助！钠API（naapi.cc）是 OpenAI 兼容的 LLM 网关，以优惠价格提供 OpenAI、Anthropic、Google 等 300+ 模型访问。一个 base URL 加一个 key 即可接入；多线路冗余、自动故障切换，长链路 SSE 不中断；按量付费、充值额度 1:1，无订阅、无隐藏加价。 |

## ✨ 功能特色

- 🎨 现代化安卓APP设计（Material 3 Expressive / 预测性返回）和 🌙 暗色模式
- 📦 工作区：Android 原生 shell，可选 Termux/Linux 工具链，支持本地和 SSH 远程工作区
- 🎬 媒体生成（图片、视频）
- 🖥️ Web多端访问支持
- 🛠️ MCP 与 Skills 支持
- 🔄 多种类型的供应商支持，自定义 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖼️ 多模态输入支持
- 📝 Markdown 渲染（支持代码高亮、数学公式、表格、Mermaid）
- 🔍 搜索功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 变量（模型名称、时间等）
- 🤳 二维码导出和导入提供商
- 🤖 智能体自定义
- 🧑‍💻 子代理：独立上下文派发任务，结果持久化
- 🧠 基于文件的记忆
- 📝 AI翻译
- 🌐 自定义HTTP请求头和请求体

## ✨ 开发

> [!IMPORTANT]
> 本项目不接受 Pull Request（PR）。

本项目使用[Android Studio](https://developer.android.com/studio)开发。

技术栈文档:

- [Kotlin](https://kotlinlang.org/) (开发语言)
- [Koin](https://insert-koin.io/) (依赖注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好数据存储)
- [Room](https://developer.android.com/training/data-storage/room) (数据库)
- [Coil](https://coil-kt.github.io/coil/) (图片加载)
- [Material 3 Expressive](https://m3.material.io/) (UI 设计)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (导航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客户端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> 你需要在 `app` 文件夹下添加 `google-services.json` 文件才能构建应用。

## 💰 捐赠

* [Ko-fi](https://ko-fi.com/reovodev)
* [爱发电](https://afdian.com/a/reovo)

## ⭐ Star History

如果喜欢这个项目，请给个Star ⭐

<a href="https://www.star-history.com/?type=date&repos=re-ovo%2Frikkahub">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&theme=dark&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
 </picture>
</a>

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 开源。
