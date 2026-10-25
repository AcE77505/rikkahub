package me.rerere.rikkahub.data.ai.transformers

/**
 * 工作区提示词里可用的占位符。
 *
 * 用户在「设置 → 工具 → 工作区提示词」里编辑模板，发请求时按这些 key 替换成实际值。
 */
object WorkspacePromptPlaceholders {
    const val WORKSPACE_NAME = "workspace_name"
    const val WORKSPACE_DIR = "workspace_dir"
    const val SHELL_ENV = "shell_env"
    const val TOOL_LIST = "tool_list"
    const val SKILLS_DIR = "skills_dir"
    const val BUILTIN_SKILLS_DIR = "builtin_skills_dir"
    const val UPLOAD_DIR = "upload_dir"
    const val CWD_LINE = "cwd_line"

    /** 设置页按这个顺序展示占位符 chip */
    val ordered: List<String> = listOf(
        WORKSPACE_NAME,
        WORKSPACE_DIR,
        SHELL_ENV,
        TOOL_LIST,
        SKILLS_DIR,
        BUILTIN_SKILLS_DIR,
        UPLOAD_DIR,
        CWD_LINE,
    )

    /** 值为空时整行删除的占位符（其余占位符空值只替换成空串） */
    val wholeLine: Set<String> = setOf(CWD_LINE)
}

/** 默认工作区提示词模板：与内置自动生成的内容一致，只是把动态部分换成了占位符 */
val DEFAULT_WORKSPACE_PROMPT: String = """
    <workspace>
    You are working in a workspace named "{{workspace_name}}".
    - The workspace directory is `{{workspace_dir}}`. It persists across turns of this conversation.
    - Paths passed to workspace tools are relative to that directory unless they are absolute.
    {{shell_env}}
    {{tool_list}}
    - Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `edit_file` for targeted edits over rewriting whole files.
    - Skills are stored at `{{skills_dir}}`. Each skill is a subdirectory `{{skills_dir}}/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.
    - Built-in skills shipped with the app are at `{{builtin_skills_dir}}/<skill-name>/` with the same layout. Treat that directory as READ-ONLY: you may read files and run scripts there, but never modify, overwrite, or delete anything. A skill in the skills directory with the same name overrides the built-in one.
    - Files the user uploaded are at `{{upload_dir}}`. Treat it as READ-ONLY: read uploaded files from there, but never modify, overwrite, or delete anything. If you need to change an uploaded file, copy it into the workspace directory first and edit the copy.
    {{cwd_line}}
    </workspace>
""".trimIndent()

/**
 * 把模板里的 `{{key}}` 换成实际值。
 *
 * [WorkspacePromptPlaceholders.wholeLine] 里的占位符值为空时整行删除，
 * 避免留下「- Current working directory: 」这样的空壳。
 */
fun renderWorkspacePrompt(template: String, values: Map<String, String>): String =
    template.lineSequence().mapNotNull { line ->
        val trimmed = line.trim()
        val single = trimmed.takeIf { it.startsWith("{{") && it.endsWith("}}") }
        if (single != null) {
            val key = single.removePrefix("{{").removeSuffix("}}")
            if (key in WorkspacePromptPlaceholders.wholeLine && values[key].isNullOrBlank()) {
                return@mapNotNull null
            }
        }
        var result = line
        values.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        result
    }.joinToString("\n")
