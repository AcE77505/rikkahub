package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.workspace.HostShellRunner
import me.rerere.workspace.SshRunner
import me.rerere.workspace.ToolchainManager
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        TranslationHistoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        MediaCreationRepository(context = get(), database = get(), dao = get(), json = get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, WORKSPACES_DIR),
            // 命令直接跑在 Android 原生环境（/system/bin/sh + toybox），不再经过 proot
            shellRunner = HostShellRunner(),
        )
    }

    single {
        val context: Context = get()
        // 工具链全局只有一份，与工作区无关：files/toolchain/termex-<versionName>/
        ToolchainManager(
            context = context,
            toolchainRoot = File(context.filesDir, TOOLCHAIN_DIR).apply { mkdirs() },
        )
    }

    single {
        val context: Context = get()
        WorkspaceRepository(
            dao = get(),
            manager = get(),
            toolchainManager = get(),
            settingsStore = get(),
            sandboxRoot = File(context.filesDir, SANDBOX_DIR),
            sshRunner = get(),
        )
    }

    single {
        val context: Context = get()
        // 所有 SSH 工作区共用这一对密钥，存在 files/ssh/
        SshRunner(
            toolchainManager = get(),
            sshDir = File(context.filesDir, SSH_DIR).apply { mkdirs() },
            assets = context.assets,
        )
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }
}

private const val WORKSPACES_DIR = "workspaces"
private const val SANDBOX_DIR = "sandbox"
private const val TOOLCHAIN_DIR = "toolchain"
private const val SSH_DIR = "ssh"

/** 与 [FileFolders] 保持一致，供需要从 DI 外部拼接路径的地方复用 */
internal val skillFolders = listOf(FileFolders.SKILLS, FileFolders.BUILTIN_SKILLS, FileFolders.UPLOAD)
