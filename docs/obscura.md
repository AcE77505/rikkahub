# 内置无头浏览器（obscura）

RikkaHub 内置了 [obscura](https://github.com/h4ckf0r0day/obscura) 的 Android 交叉编译产物：

- AI 侧：`browser_fetch` / `browser_screenshot` 两个内置工具（设置 → 本地工具 → 浏览器）
- 终端侧：工作区终端和工作区 `shell` 工具里可以直接执行 `obscura ...`

## 产物从哪来

obscura 内嵌 V8，没有 Android 预编译包，必须在 Linux 主机上用 NDK 从源码编译。
CI 工作流 `.github/workflows/build-obscura.yml` 负责这件事：

1. `ubuntu-22.04` + NDK r26d + stable Rust，matrix 覆盖 `arm64-v8a` / `x86_64`
2. `V8_FROM_SOURCE=1` 从源码编 V8，`--profile release-dist`
3. 因为 crates.io 发布的 `v8` crate 排除了两个大目录，构建前会补齐：
   - `third_party/rust/chromium_crates_io/vendor`：从 rusty_v8 仓库的 `third_party/rust` 子模块取
   - ICU 的 `icudtl.dat`：直接关闭 V8 i18n（`GN_ARGS=v8_enable_i18n_support=false`）
4. 产物重命名为 `libobscura.so` 上传 artifact（`obscura-android-arm64-v8a` / `obscura-android-x86_64`）

## 放进仓库

下载 artifact 后放到：

```
app/src/main/jniLibs/arm64-v8a/libobscura.so
app/src/main/jniLibs/x86_64/libobscura.so
```

`app/build.gradle.kts` 已经开启 `useLegacyPackaging = true`，安装时 native lib 会解压到
`applicationInfo.nativeLibraryDir`（即 `<安装目录>/lib/arm64`）。

Android 10+ 只允许从 `nativeLibraryDir` 执行文件，且文件名必须是 `lib*.so`，所以不能改名。

## CLI 可用性

`me.rerere.workspace.BundledExecutables` 在 `files/bundled-bin/bin/` 下建符号链接
`obscura -> nativeLibraryDir/libobscura.so`，并把这个目录注入：

- 工作区 `shell` 工具与后台命令的 `PATH`
- 工作区终端的 `PATH`

因此终端里直接 `obscura fetch https://example.com --dump markdown` 即可。

## 已知限制

- **一次性调用**：`browser_*` 工具每次都是独立的 `obscura fetch`，不能保持会话、点击、填表或走登录流程。
  交互能力需要后续接 `obscura mcp --http` 或 `obscura serve`（CDP）。
- **没有 i18n**：V8 关闭了 ICU 支持，页面里的 `Intl.*` 不可用（obscura 自身 JS bootstrap 不用）。
- **中文字体**：`fetch --screenshot` 没有 `--font-dir` 参数，CJK 文本可能渲染成方框。
- **scrape 不可用**：只打包了 `obscura` 主程序，并行 `scrape` 需要的 `obscura-worker` 没有构建。
- **内网默认拒绝**：obscura 有 SSRF 防护，访问 localhost / 局域网需要显式 `--allow-private-network`
  （工具参数 `allow_private_network`）。
- **体积**：stealth + render 的单个 ABI 约 67MB（压缩后），通用 APK 会明显变大。

## 升级 obscura

1. 改 `.github/workflows/build-obscura.yml` 里的 `obscura_ref` 默认值（或手动触发时填写新 tag）
2. 重新跑 workflow，下载新 artifact 覆盖两个 `libobscura.so`
3. 跑 `./gradlew assembleGithubDebug`，真机验证 `obscura fetch` 与截图
