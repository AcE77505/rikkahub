package me.rerere.workspace

import android.content.res.AssetManager
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 一个 SSH 工作区的连接目标。
 *
 * [remoteRoot] 是远程侧的工作目录（`~` 表示登录用户的 home），相对路径都相对它解析。
 */
data class SshTarget(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
    val remoteRoot: String = DEFAULT_REMOTE_ROOT,
) {
    /** 展示用地址；默认端口时省略端口号 */
    val endpoint: String
        get() = if (port == DEFAULT_PORT) "$username@$host" else "$username@$host:$port"

    val isConfigured: Boolean
        get() = host.isNotBlank() && username.isNotBlank()

    companion object {
        /** Termux 的 sshd 默认监听 8022 */
        const val DEFAULT_PORT = 8022
        const val DEFAULT_REMOTE_ROOT = "~"
    }
}

/** 交互式 SSH 终端的启动参数：二进制 + args，由 Termux 的 TerminalSession 直接执行 */
data class SshTerminalCommand(
    val binary: String,
    val args: List<String>,
)

/** app 自己生成并持有的一对密钥，所有 SSH 工作区共用 */
data class SshKeyPair(
    val privateKey: File,
    val publicKey: String,
)

/** 导出备份用的密钥文本；[privateKey] 是敏感信息，调用方负责提示用户 */
data class SshKeyBackup(
    val privateKey: String,
    val publicKey: String,
)

/** SSH 目标环境：决定复制哪一套配置/启动命令 */
enum class SshEnvironment {
    TERMUX,
    LINUX,
}

/** 远程文件的一次 stat 结果 */
data class SshStat(
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * 通过 SSH 在远端执行命令、读写文件。
 *
 * ssh/scp/ssh-keygen 都取自 `bin.mt.termex` 的工具链（[ToolchainManager]），
 * 因此不新增任何打包体积；没装扩展包时这里会直接报错，由上层提示用户安装。
 *
 * 连接是「一次命令一条 ssh 进程」的形式：不维持长连接，也就没有断线重连、
 * 空闲清理这类状态要管。代价是每次调用有几十毫秒的握手开销。
 */
class SshRunner(
    private val toolchainManager: ToolchainManager,
    private val sshDir: File,
    /** SSH 命令模板从 assets/ssh/ 读取，改命令不用重编译 Kotlin */
    private val assets: AssetManager? = null,
) {
    private val privateKeyFile: File
        get() = File(sshDir, PRIVATE_KEY_NAME)

    private val publicKeyFile: File
        get() = File(sshDir, "$PRIVATE_KEY_NAME.pub")

    private val knownHostsFile: File
        get() = File(sshDir, "known_hosts")

    /** 取工具链环境；SSH 依赖其中的 ssh / ssh-keygen */
    fun environment(): ToolchainEnvironment =
        toolchainManager.resolve() ?: error(TERMEX_REQUIRED_MESSAGE)

    /** 工具链里是否真的能跑 ssh（只查文件存在，不连服务器） */
    fun isAvailable(): Boolean = runCatching {
        val env = environment()
        File(env.binDir, SSH_BINARY).isFile
    }.getOrDefault(false)

    // ---- 密钥 ----

    /** 生成或复用 app 的 ed25519 密钥对（无口令） */
    fun ensureKeyPair(): SshKeyPair {
        sshDir.mkdirs()
        if (!privateKeyFile.isFile || !publicKeyFile.isFile) {
            val env = environment()
            val keygen = File(env.binDir, "ssh-keygen")
            require(keygen.isFile) { "ssh-keygen 不可用，请更新 MT 终端扩展包" }
            // 已存在半套文件时先清掉，避免 ssh-keygen 因文件存在而拒绝生成
            privateKeyFile.delete()
            publicKeyFile.delete()
            val result = exec(
                command = listOf(
                    keygen.absolutePath,
                    "-t", "ed25519",
                    "-N", "",
                    "-C", KEY_COMMENT,
                    "-f", privateKeyFile.absolutePath,
                ),
                env = env,
                stdin = null,
            )
            require(result.exitCode == 0 && privateKeyFile.isFile) {
                "生成 SSH 密钥失败：${result.stderr.ifBlank { result.stdout }}"
            }
            // ssh 对私钥权限的要求很严格，过宽会直接拒绝使用
            privateKeyFile.setReadable(false, false)
            privateKeyFile.setReadable(true, true)
            privateKeyFile.setWritable(false, false)
            privateKeyFile.setWritable(true, true)
        }
        return SshKeyPair(
            privateKey = privateKeyFile,
            publicKey = publicKeyFile.readText().trim(),
        )
    }

    /** 导出当前密钥对（会确保密钥存在）；包含私钥，调用方负责提醒用户妥善保管 */
    fun exportKeyPair(): SshKeyBackup {
        environment()
        ensureKeyPair()
        return SshKeyBackup(
            privateKey = privateKeyFile.readText().trim(),
            publicKey = publicKeyFile.readText().trim(),
        )
    }

    /**
     * 从 OpenSSH 私钥文本恢复密钥对，返回推导出的公钥。
     *
     * 只接受免密私钥：`ssh-keygen -y -P ""` 对带口令私钥会失败，避免连接时卡在口令输入。
     */
    fun importKeyPair(privateKeyText: String): String {
        val env = environment()
        val keygen = File(env.binDir, "ssh-keygen")
        require(keygen.isFile) { "ssh-keygen 不可用，请更新 MT 终端扩展包" }
        val pem = Regex(
            "-----BEGIN OPENSSH PRIVATE KEY-----[\\s\\S]*?-----END OPENSSH PRIVATE KEY-----"
        ).find(privateKeyText)?.value ?: error("备份里没有找到 OpenSSH 私钥")
        sshDir.mkdirs()
        val temp = File(sshDir, "import_id_ed25519.tmp")
        val oldPrivate = privateKeyFile.takeIf { it.isFile }?.readBytes()
        val oldPublic = publicKeyFile.takeIf { it.isFile }?.readBytes()
        try {
            temp.writeText(pem + "\n")
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setWritable(true, true)
            val result = exec(
                command = listOf(keygen.absolutePath, "-y", "-P", "", "-f", temp.absolutePath),
                env = env,
                stdin = null,
            )
            require(result.exitCode == 0) {
                "私钥无效或带口令（当前只支持免密私钥）：${result.stderr.ifBlank { result.stdout }}"
            }
            val publicKey = result.stdout.trim()
            require(publicKey.isNotBlank()) { "无法从私钥推导公钥" }
            temp.copyTo(privateKeyFile, overwrite = true)
            privateKeyFile.setReadable(false, false)
            privateKeyFile.setReadable(true, true)
            privateKeyFile.setWritable(false, false)
            privateKeyFile.setWritable(true, true)
            publicKeyFile.writeText(publicKey + "\n")
            return publicKey
        } catch (e: Throwable) {
            // 导入失败时恢复旧密钥，避免把用户现有的可用密钥写坏
            if (oldPrivate != null) privateKeyFile.writeBytes(oldPrivate) else privateKeyFile.delete()
            if (oldPublic != null) publicKeyFile.writeBytes(oldPublic) else publicKeyFile.delete()
            throw e
        } finally {
            temp.delete()
        }
    }

    /**
     * 首次配置命令：在目标终端里粘一次，完成「装 openssh + 授信 + 启动 sshd」。
     *
     * Termux 与普通 Linux 的装包方式和 sshd 启动方式完全不同，由 [environment] 决定。
     * 末尾回显 `whoami`，用户把用户名填回 app（Termux 的用户名是 `u0_a169` 这类，无法预知）。
     */
    fun setupCommand(
        target: SshTarget,
        keyPair: SshKeyPair,
        environment: SshEnvironment,
    ): String {
        val publicKey = keyPair.publicKey.shellQuote()
        return when (environment) {
            SshEnvironment.TERMUX -> termuxSetupCommand(target.port, publicKey)
            SshEnvironment.LINUX -> linuxSetupCommand(target.port, publicKey)
        }
    }

    /** 通道断开后重开的命令；两种系统启动 sshd 的方式不同 */
    fun reconnectCommand(target: SshTarget, environment: SshEnvironment): String =
        when (environment) {
            SshEnvironment.TERMUX -> termuxReconnectCommand(target.port)
            SshEnvironment.LINUX -> linuxReconnectCommand(target.port)
        }

    /**
     * Termux 初始化命令（模板在 assets/ssh/termux_setup.sh）。
     *
     * 命令保持单行：Termux 终端粘贴多行脚本时，`2>/dev/null` 里的 `>` 很容易被当成续行。
     */
    private fun termuxSetupCommand(port: Int, publicKey: String): String =
        asset("termux_setup.sh")
            .replace("{{start_script}}", termuxStartScript(port))
            .replace("{{public_key}}", publicKey)
            .replace("{{port}}", port.toString())

    /** Termux 启动片段（assets/ssh/termux_start.sh）：优先 runit，失败后退回裸 sshd */
    private fun termuxStartScript(port: Int): String =
        asset("termux_start.sh").replace("{{port}}", port.toString())

    private fun termuxReconnectCommand(port: Int): String =
        asset("termux_reconnect.sh")
            .replace("{{start_script}}", termuxStartScript(port))
            .replace("{{port}}", port.toString())

    /** Linux 初始化命令（assets/ssh/linux_setup.sh） */
    private fun linuxSetupCommand(port: Int, publicKey: String): String =
        asset("linux_setup.sh")
            .replace("{{start_script}}", linuxStartScript(port))
            .replace("{{public_key}}", publicKey)
            .replace("{{port}}", port.toString())

    private fun linuxReconnectCommand(port: Int): String =
        asset("linux_reconnect.sh")
            .replace("{{start_script}}", linuxStartScript(port))
            .replace("{{port}}", port.toString())

    /** Linux 启动片段（assets/ssh/linux_start.sh） */
    private fun linuxStartScript(port: Int): String =
        asset("linux_start.sh").replace("{{port}}", port.toString())

    /** 从 assets/ssh/ 读取命令模板；改命令只需要改 assets，不触发 Kotlin 重编译 */
    private fun asset(name: String): String = runCatching {
        val manager = requireNotNull(assets) { "AssetManager 未初始化" }
        manager.open("ssh/$name").bufferedReader().use { it.readText() }.trim()
    }.getOrElse { error("缺少 SSH 命令模板 assets/ssh/$name：${it.message}") }

    // ---- 连接 ----

    /**
     * 交互式终端命令：把 `ssh -tt` 作为终端进程启动，Termux 的 PTY 直接接到远端 shell。
     *
     * 终端环境（PATH / LD_LIBRARY_PATH）由调用方用 [environment] 构造。
     */
    fun terminalCommand(target: SshTarget): SshTerminalCommand {
        require(target.isConfigured) { "请先填写主机与用户名" }
        val key = ensureKeyPair()
        val environment = environment()
        val args = buildList {
            add("-tt")
            add("-p"); add(target.port.toString())
            add("-i"); add(key.privateKey.absolutePath)
            // 只用密钥登录，避免终端卡在口令输入
            add("-o"); add("BatchMode=yes")
            add("-o"); add("StrictHostKeyChecking=accept-new")
            add("-o"); add("UserKnownHostsFile=${knownHostsFile.absolutePath}")
            add("-o"); add("ConnectTimeout=10")
            add("-o"); add("LogLevel=ERROR")
            add("${target.username}@${target.host}")
            // 远端目录不是 home 时先 cd 再起登录 shell
            val root = target.remoteRoot.trim()
            if (root.isNotEmpty() && root != "~") {
                add("--")
                add("cd ${root.shellQuote()} && exec \"\${SHELL:-sh}\" -l")
            }
        }
        return SshTerminalCommand(
            binary = File(environment.binDir, SSH_BINARY).absolutePath,
            args = args,
        )
    }

    /** 测试连接；成功时返回远端用户名与 home，便于界面上确认连对了机器 */
    fun testConnection(target: SshTarget): Result<String> = runCatching {
        require(target.isConfigured) { "请先填写主机与用户名" }
        ensureKeyPair()
        val result = run(target, "printf '%s\\n%s\\n' \"\$(whoami)\" \"\$HOME\"", overtimeMillis = 20_000L)
        require(result.exitCode == 0) {
            result.stderr.ifBlank { result.stdout }.ifBlank { "连接失败（exit ${result.exitCode}）" }
        }
        result.stdout.trim()
    }

    // ---- 执行 ----

    /**
     * 在远端执行一条命令。
     *
     * 软超时语义与本地一致：超过阈值默认只通知，`autoStopOnOvertime` 为真时杀掉整条 ssh 进程。
     */
    fun run(
        target: SshTarget,
        command: String,
        cwd: String = "",
        overtimeMillis: Long = WorkspaceShellContext.DEFAULT_OVERTIME_MILLIS,
        autoStopOnOvertime: Boolean = false,
        stdin: ByteArray? = null,
        onOvertime: (() -> Unit)? = null,
    ): WorkspaceCommandResult {
        require(target.isConfigured) { "SSH 目标未配置完整" }
        val key = ensureKeyPair()
        val env = environment()
        val remoteCommand = if (cwd.isBlank()) {
            command
        } else {
            // cwd 与文件工具保持同一套语义：相对路径挂在 remoteRoot 下
            "cd ${resolveRemotePath(target, cwd).shellQuote()} && $command"
        }
        val args = buildList {
            add(File(env.binDir, SSH_BINARY).absolutePath)
            add("-p")
            add(target.port.toString())
            add("-i")
            add(key.privateKey.absolutePath)
            // 只用密钥登录：提示输入口令会卡住整个调用
            add("-o")
            add("BatchMode=yes")
            // 首次连接自动记录指纹；之后指纹变化时 ssh 会拒绝，起到防中间人的作用
            add("-o")
            add("StrictHostKeyChecking=accept-new")
            add("-o")
            add("UserKnownHostsFile=${knownHostsFile.absolutePath}")
            add("-o")
            add("ConnectTimeout=10")
            add("-o")
            add("LogLevel=ERROR")
            add("${target.username}@${target.host}")
            add("--")
            add(remoteCommand)
        }
        return exec(
            command = args,
            env = env,
            stdin = stdin,
            context = shellContext(
                command = remoteCommand,
                env = env,
                overtimeMillis = overtimeMillis,
                autoStopOnOvertime = autoStopOnOvertime,
                stdin = stdin,
                onOvertime = onOvertime,
            ),
        )
    }

    // ---- 文件 ----

    fun readText(target: SshTarget, path: String): String =
        run(target, "cat -- ${resolveRemotePath(target, path).shellQuote()}").let { result ->
            require(result.exitCode == 0) {
                result.stderr.ifBlank { "读取失败（exit ${result.exitCode}）" }
            }
            result.stdout
        }

    /** 二进制走 base64 传输，避免终端编码破坏内容 */
    fun readBytes(target: SshTarget, path: String): ByteArray =
        run(target, "base64 -w 0 -- ${resolveRemotePath(target, path).shellQuote()}").let { result ->
            require(result.exitCode == 0) {
                result.stderr.ifBlank { "读取失败（exit ${result.exitCode}）" }
            }
            runCatching { Base64.getMimeDecoder().decode(result.stdout.trim()) }
                .getOrElse { error("远端 base64 输出无法解码：${result.stdout.take(200)}") }
        }

    fun writeText(target: SshTarget, path: String, text: String, overwrite: Boolean): SshStat {
        val remotePath = resolveRemotePath(target, path)
        val quoted = remotePath.shellQuote()
        if (!overwrite) {
            val existing = stat(target, path)
            require(existing == null) { "File already exists: $path" }
        }
        // 内容从 stdin 整段送过去，在远端本地落盘，不经过额外的网络往返
        val result = run(
            target = target,
            command = "mkdir -p -- \"\$(dirname -- $quoted)\" && cat > $quoted",
            stdin = text.toByteArray(),
        )
        require(result.exitCode == 0) {
            result.stderr.ifBlank { "写入失败（exit ${result.exitCode}）" }
        }
        return stat(target, path) ?: SshStat(isDirectory = false, sizeBytes = text.toByteArray().size.toLong())
    }

    fun stat(target: SshTarget, path: String): SshStat? {
        val quoted = resolveRemotePath(target, path).shellQuote()
        val result = run(target, "stat -c '%F|%s' -- $quoted 2>/dev/null || true")
        val line = result.stdout.lineSequence().firstOrNull { it.contains('|') } ?: return null
        val type = line.substringBefore('|')
        val size = line.substringAfter('|').trim().toLongOrNull() ?: 0L
        return SshStat(isDirectory = type.contains("directory"), sizeBytes = size)
    }

    fun delete(target: SshTarget, path: String, recursive: Boolean): Boolean {
        require(path.isNotBlank() && path != "." && path != "~") { "拒绝删除工作区根目录" }
        val quoted = resolveRemotePath(target, path).shellQuote()
        val result = run(target, "rm ${if (recursive) "-rf" else "-f"} -- $quoted")
        return result.exitCode == 0
    }

    // ---- 内部 ----

    /** 相对路径挂到 [SshTarget.remoteRoot] 下；绝对路径原样使用 */
    fun resolveRemotePath(target: SshTarget, path: String): String {
        val normalized = path.replace('\\', '/').trim()
        require(!normalized.contains('\u0000')) { "路径包含非法字符" }
        return when {
            normalized.startsWith("/") -> normalized
            normalized.isEmpty() || normalized == "." -> target.remoteRoot
            else -> "${target.remoteRoot.trimEnd('/')}/$normalized"
        }
    }

    private fun shellContext(
        command: String,
        env: ToolchainEnvironment,
        overtimeMillis: Long,
        autoStopOnOvertime: Boolean,
        stdin: ByteArray?,
        onOvertime: (() -> Unit)?,
    ) = WorkspaceShellContext(
        root = "",
        command = command,
        cwd = "",
        // ssh 进程本身不依赖工作区目录，用 ssh 目录兜底
        workingDir = sshDir,
        homeDir = sshDir,
        tempDir = sshDir,
        overtimeMillis = overtimeMillis,
        autoStopOnOvertime = autoStopOnOvertime,
        extraEnv = env.extraEnv,
        pathPrepend = env.pathPrepend,
        stdin = stdin,
        onOvertime = onOvertime,
    )

    private fun exec(
        command: List<String>,
        env: ToolchainEnvironment,
        stdin: ByteArray?,
        context: WorkspaceShellContext = shellContext(
            command = command.joinToString(" "),
            env = env,
            overtimeMillis = WorkspaceShellContext.DEFAULT_OVERTIME_MILLIS,
            autoStopOnOvertime = false,
            stdin = stdin,
            onOvertime = null,
        ),
    ): WorkspaceCommandResult {
        sshDir.mkdirs()
        val process = ProcessBuilder(command)
            .directory(sshDir)
            .redirectErrorStream(false)
            .apply { environment().putAll(context.environment()) }
            .start()
        return process.readResult(context)
    }

    companion object {
        const val SSH_BINARY = "ssh"
        private const val PRIVATE_KEY_NAME = "id_ed25519"
        private const val KEY_COMMENT = "rikkahub"
        private const val TERMEX_REQUIRED_MESSAGE =
            "需要先安装 MT 终端扩展包（bin.mt.termex）才能使用 SSH 工作区"
    }
}
