package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExampleUnitTest {
    /**
     * 命令与绝对路径语义都基于 POSIX 环境（Android / Linux CI）。
     * 在 Windows 宿主机上跑 JVM 单测时没有 `/bin/sh`，这些用例只能在真机或 Linux 上验证。
     */
    private fun assumePosix() {
        org.junit.Assume.assumeTrue(File.separatorChar == '/' && File("/bin/sh").exists())
    }

    @Test
    fun fileOperationsWorkInsideWorkspaceRoot() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        fileSystem.writeText(root, "src/main.txt", "hello\nworkspace")

        assertEquals("hello\nworkspace", fileSystem.readText(root, "src/main.txt"))
        assertEquals(listOf("src"), fileSystem.list(root).map { it.path })
        assertEquals(listOf("src/main.txt"), fileSystem.glob(root, "**/*.txt").map { it.path })
        assertEquals(
            listOf(WorkspaceSearchMatch(path = "src/main.txt", line = 2, text = "workspace")),
            fileSystem.grep(root, "workspace"),
        )
    }

    @Test
    fun pathEscapeIsRejected() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        var rejected = false
        try {
            fileSystem.writeText(root, "../escape.txt", "nope")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun workspaceKeepsHomeAndTempPerWorkspace() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        assertTrue(manager.filesDir(root).isDirectory)
        assertTrue(manager.homeDir(root).isDirectory)
        assertTrue(manager.tempDir(root).isDirectory)
        // HOME / TMPDIR 必须是工作区私有目录，不能与其他工作区共用
        assertTrue(manager.homeDir(root).path.contains(root))
    }

    @Test
    fun customDirectoryOverridesDefaultFilesDir() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        val custom = Files.createTempDirectory("workspace-custom").toFile()

        assertEquals(custom.canonicalFile, manager.targetDir(root, custom.absolutePath).canonicalFile)
        // 非绝对路径一律回落到默认目录
        assertEquals(manager.filesDir(root).canonicalFile, manager.targetDir(root, "relative/dir").canonicalFile)
        assertEquals(manager.filesDir(root).canonicalFile, manager.targetDir(root, null).canonicalFile)
    }

    @Test
    fun commandRunsInsideWorkspaceDirectory() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-command-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "printf hello > command.txt && cat command.txt")

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("hello", File(manager.filesDir(root), "command.txt").readText())
    }

    @Test
    fun commandReceivesStdin() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-stdin-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root = root,
            command = "cat > stdin.txt",
            stdin = "hello\nstdin".toByteArray(),
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello\nstdin", File(manager.filesDir(root), "stdin.txt").readText())
    }

    @Test
    fun commandWithoutStdinGetsImmediateEof() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-stdin-eof-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        // 不传 stdin 时子进程的 stdin 应立即 EOF; cat 会直接退出而不是等管道输入
        val result = manager.executeCommand(
            root = root,
            command = "cat",
            overtimeMillis = 10_000,
        )

        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
    }

    @Test
    fun longOutputSpillsToFileInsteadOfBeingDropped() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-truncate-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root = root,
            command = "awk 'BEGIN { for (i = 0; i < 300000; i++) printf \"a\" }'",
            overflowFileName = "shell-test.log",
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
        // 溢出部分不丢：完整内容落进工作区 TMPDIR
        val overflow = result.fullOutputPath?.let(::File)
        assertTrue(overflow != null && overflow.isFile)
        assertEquals(300_000L, overflow!!.length())
    }

    @Test
    fun commandIsNotStoppedWhenOvertimeIsSoft() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-overtime-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        var notified = false

        val result = manager.executeCommand(
            root = root,
            command = "sleep 1 && echo done",
            overtimeMillis = 200,
            autoStopOnOvertime = false,
            onOvertime = { notified = true },
        )

        // 软阈值：只通知，命令照常跑完
        assertTrue(notified)
        assertFalse(result.timedOut)
        assertEquals("done", result.stdout)
    }

    @Test
    fun commandIsStoppedWhenAutoStopIsEnabled() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-autostop-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root = root,
            command = "sleep 30",
            overtimeMillis = 200,
            autoStopOnOvertime = true,
        )

        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun unlimitedOvertimeNeverNotifies() {
        assumePosix()
        val baseDir = Files.createTempDirectory("workspace-unlimited-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        var notified = false

        val result = manager.executeCommand(
            root = root,
            command = "echo ok",
            overtimeMillis = 0,
            onOvertime = { notified = true },
        )

        assertFalse(notified)
        assertEquals("ok", result.stdout)
    }
}
