package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillMetadata
import me.rerere.rikkahub.data.skills.SkillPaths
import java.io.File

// 与 Agent Skills 规范的 description 上限一致
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1024

/** 这些图片会作为图片内容返回，交给模型内置的识图能力处理。 */
private val SKILL_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    workspaceRepository: WorkspaceRepository? = null,
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }
    if (available.isEmpty()) return emptyList()

    val tools = mutableListOf<Tool>()

    tools += Tool(
        name = "use_skill",
        description = """
            Load and apply a skill to get specialized instructions or capabilities.
            Call this tool when the user's request matches one of the available skills.
            Pass `path` to read a supporting file (text is returned as text, images as image content).
        """.trimIndent(),
        systemPrompt = { _, _ ->
            buildString {
                appendLine("**Skills**")
                appendLine("You have access to the following skills. Use the `use_skill` tool to load a skill's instructions when the user's request matches.")
                appendLine("- Call `use_skill` with `name` to read `SKILL.md`. Pass `path` to read a file referenced by the skill; text files come back as text, image files (png/jpeg/webp/gif) come back as image content so you can look at them directly when the model supports images.")
                appendLine("- Only use paths that appear in the `SKILL.md` content or in the skill's file list. Do not guess or infer paths.")
                if (workspaceRepository != null) {
                    appendLine("- To run a skill's scripts or work with binary files, call `export_skill` to copy the skill into the local workspace (or the app sandbox when no local workspace is declared), then use the local shell and file tools on the exported files.")
                }
                appendLine("<available_skills>")
                available.forEach { skill ->
                    appendLine("  <skill>")
                    // 技能可能来自第三方导入，转义并限长，防止 name/description 闭合标签注入任意系统提示
                    appendLine("    <name>${skill.name.escapeXml()}</name>")
                    appendLine("    <description>${skill.description.take(MAX_SKILL_DESCRIPTION_LENGTH).escapeXml()}</description>")
                    appendLine("  </skill>")
                }
                append("</available_skills>")
                appendLine()
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "The name of the skill to use")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Text files are returned as text; image files (png/jpeg/webp/gif) are returned as image content. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                        )
                    })
                },
                required = listOf("name")
            )
        },
        execute = {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
                ?: error("name is required")
            // 模型可能照抄系统提示中转义后的名称，两种形式都接受
            val skill = available.firstOrNull { skill -> skill.name == name || skill.name.escapeXml() == name }
                ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
            val path = it.jsonObject["path"]?.jsonPrimitive?.content
            if (path.isNullOrBlank()) {
                require(skill.skillFile.exists()) { "Skill '$name' not found" }
                listOf(UIMessagePart.Text(SkillFrontmatterParser.extractBody(skill.skillFile.readText())))
            } else {
                val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                    ?: error("Path '$path' is outside the skill directory")
                require(target.exists()) { "File '$path' not found in skill '$name'" }
                require(target.isFile) { "'$path' is a directory; use export_skill to copy it to the workspace" }
                if (target.extension.lowercase() in SKILL_IMAGE_EXTENSIONS) {
                    // 交给模型的识图能力；是否支持由模型/客户端决定，本地不做判断
                    listOf(UIMessagePart.Image(url = "file://${target.absolutePath}"))
                } else {
                    listOf(UIMessagePart.Text(target.readText()))
                }
            }
        }
    )

    if (workspaceRepository != null) {
        val repository = workspaceRepository
        tools += Tool(
            name = "export_skill",
            description = """
                Copy a skill's files into the local workspace (or the app sandbox when no local workspace is declared) so you can run its scripts or process binary files.
                Defaults to exporting the whole skill to `skills/<skill-name>/`; pass `path` to export only one file or subdirectory, and `destination` to choose another directory inside that workspace.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to export")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative file or directory inside the skill. Omit to export the whole skill."
                            )
                        })
                        put("destination", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional workspace-relative directory. Defaults to `skills/<skill-name>/`. The path must stay inside the workspace."
                            )
                        })
                        put("overwrite", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Replace existing files in the destination. Defaults to false.")
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name || skill.name.escapeXml() == name }
                    ?: error("Skill '$name' is not available. Available skills: ${available.joinToString { it.name }}")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val destination = it.jsonObject["destination"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val overwrite = it.jsonObject["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

                val workspaceRoot = repository.getDeclaredLocal()
                    ?.let { workspace -> repository.workingDirectory(workspace.id) }
                    ?: repository.sandboxDir()

                val source = if (path == null) {
                    skill.skillDir
                } else {
                    SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                }
                require(source.exists()) { "Path '$path' not found in skill '$name'" }

                val destinationRoot = if (destination == null) {
                    workspaceRoot.resolve("skills").resolve(skill.skillDir.name)
                } else {
                    val root = workspaceRoot.canonicalFile
                    val target = root.resolve(destination).canonicalFile
                    require(target == root || target.path.startsWith(root.path + File.separator)) {
                        "destination must stay inside the workspace"
                    }
                    target
                }
                val destinationCanonical = destinationRoot.canonicalFile

                val sourceRoot = if (source.isDirectory) source else source.parentFile ?: skill.skillDir
                val files = if (source.isDirectory) {
                    source.walkTopDown().filter { it.isFile }
                } else {
                    sequenceOf(source)
                }

                var copied = 0
                var skipped = 0
                for (file in files) {
                    val relativePath = file.relativeTo(sourceRoot).path
                    val target = destinationCanonical.resolve(relativePath).canonicalFile
                    require(target.path.startsWith(destinationCanonical.path + File.separator)) {
                        "refusing to write outside the destination directory: $relativePath"
                    }
                    if (target.exists() && !overwrite) {
                        skipped += 1
                        continue
                    }
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                    copied += 1
                }

                val skippedNote = if (skipped > 0) {
                    " $skipped existing file(s) were skipped; pass overwrite=true to replace them."
                } else {
                    ""
                }
                listOf(
                    UIMessagePart.Text(
                        "Exported $copied file(s) from skill '$name' to ${destinationRoot.path}.$skippedNote"
                    )
                )
            }
        )
    }

    return tools
}

private fun String.escapeXml(): String = buildString(length) {
    for (c in this@escapeXml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
