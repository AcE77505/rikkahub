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
    - Workspace directory: `{{workspace_dir}}` (persistent across turns; relative tool paths resolve against it, absolute paths are used as-is).
    {{shell_env}}
    {{tool_list}}
    - Skills live in `{{skills_dir}}` (`<skill-name>/SKILL.md`); built-in skills are in `{{builtin_skills_dir}}` (read-only; the same name in `{{skills_dir}}` overrides). Read a skill's `SKILL.md` before using it.
    - Uploads are in `{{upload_dir}}` (read-only; copy a file into the workspace before editing it).
    {{cwd_line}}
    </workspace>
""".trimIndent()

/**
 * 把模板里的 `{{key}}` 换成实际值。
 *
 * [WorkspacePromptPlaceholders.wholeLine] 里的占位符值为空时整行删除，
 * 避免留下「- Current working directory: 」这样的空壳。
 *
 * [dropLinesWith] 里的占位符出现在某一行时整行删除：远程工作区读不到手机上的
 * skills / uploads，渲染远程段时用它把这几行去掉。
 */
fun renderWorkspacePrompt(
    template: String,
    values: Map<String, String>,
    dropLinesWith: Set<String> = emptySet(),
): String =
    template.lineSequence().mapNotNull { line ->
        if (dropLinesWith.any { key -> line.contains("{{$key}}") }) return@mapNotNull null
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
