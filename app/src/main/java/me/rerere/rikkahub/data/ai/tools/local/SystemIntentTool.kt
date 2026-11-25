package me.rerere.rikkahub.data.ai.tools.local

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.net.URLEncoder

internal const val SYSTEM_INTENT_TOOL_NAME = "system_intent"

/** 高德、百度的包名；也在 AndroidManifest 的 queries 里声明，否则 Android 11+ 检测不到 */
private const val PKG_AMAP = "com.autonavi.minimap"
private const val PKG_BAIDU = "com.baidu.BaiduMap"

private val NAV_MODES = listOf("drive", "transit", "walk", "ride")

/**
 * 把事务交给系统里能处理它的应用（闹钟、计时器、拨号、短信草稿、分享、导航）。
 *
 * 只负责「唤起」，不负责完成：拨号停在拨号界面、短信停在草稿、闹钟停在新建页，
 * 需要用户在目标应用里自己确认。这与直接写数据的工具（如 calendar_create）不同。
 */
internal fun buildSystemIntentTool(context: Context): Tool = Tool(
    name = SYSTEM_INTENT_TOOL_NAME,
    description = """
        Hand a task to the system app that can handle it. This only opens that app; it does not finish
        the task, and the result does not prove the task was completed.
        kinds and their fields:
        alarm(hour, minute, message?, days?) - days are 1=Monday..7=Sunday, omit for a one-shot alarm;
        timer(seconds, message?);
        dial(number) - opens the dialer with the number filled in, never places the call;
        sms_draft(text, number?) - opens the messaging app with the text, never sends it;
        share(text, app?) - opens the system share sheet, or the target app when 'app' is given;
        navigate(destination or lat+lng, mode?, map?) - opens a map app with the route, never starts navigation.
        Prefer this over describing steps for the user to follow. After calling, tell the user what to
        confirm in the opened screen instead of assuming it is done.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("alarm")
                            add("timer")
                            add("dial")
                            add("sms_draft")
                            add("share")
                            add("navigate")
                        }
                    )
                    put(
                        "description",
                        "alarm / timer / dial / sms_draft / share / navigate"
                    )
                })
                // 闹钟
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "For alarm: hour 0-23")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "For alarm: minute 0-59, defaults to 0")
                })
                put("days", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "integer") })
                    put("description", "For alarm: repeat days, 1=Monday .. 7=Sunday. Omit for one-shot.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "For alarm or timer: its label")
                })
                // 计时器
                put("seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "For timer: duration in seconds, 1-86400")
                })
                // 拨号与短信
                put("number", buildJsonObject {
                    put("type", "string")
                    put("description", "For dial or sms_draft: phone number. Digits, +, -, spaces and parentheses only.")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "For sms_draft or share: the text content")
                })
                put("app", buildJsonObject {
                    put("type", "string")
                    put("description", "For share: target app name or package. Omit to show the share sheet.")
                })
                // 导航
                put("destination", buildJsonObject {
                    put("type", "string")
                    put("description", "For navigate: place name or address")
                })
                put("lat", buildJsonObject {
                    put("type", "number")
                    put("description", "For navigate: latitude, GCJ-02. Must be given together with lng.")
                })
                put("lng", buildJsonObject {
                    put("type", "number")
                    put("description", "For navigate: longitude, GCJ-02. Must be given together with lat.")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray { NAV_MODES.forEach { add(it) } }
                    )
                    put("description", "For navigate: travel mode, defaults to drive")
                })
                put("map", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("amap")
                            add("baidu")
                        }
                    )
                    put("description", "For navigate: prefer this map app. Default tries Amap then Baidu.")
                })
            },
            required = listOf("kind"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val kind = params.str("kind") ?: error("kind is required")
        val result = when (kind) {
            "alarm" -> startAlarm(context, params)
            "timer" -> startTimer(context, params)
            "dial" -> startDial(context, params)
            "sms_draft" -> startSmsDraft(context, params)
            "share" -> startShare(context, params)
            "navigate" -> startNavigate(context, params)
            else -> error("unknown kind: $kind, must be one of alarm/timer/dial/sms_draft/share/navigate")
        }
        listOf(UIMessagePart.Text(result.toString()))
    }
)

private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun kotlinx.serialization.json.JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun kotlinx.serialization.json.JsonObject.double(key: String): Double? =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

/** 号码只接受数字、+、-、空格和括号，拒绝 *# 服务码 */
private fun normalizeNumber(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (!trimmed.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }) return null
    return trimmed
}

private fun startAlarm(context: Context, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val hour = params.int("hour") ?: error("alarm needs hour (0-23)")
    val minute = params.int("minute") ?: 0
    require(hour in 0..23) { "alarm hour must be 0-23, got $hour" }
    require(minute in 0..59) { "alarm minute must be 0-59, got $minute" }
    val days = params["days"]?.let { element ->
        runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull } }
            .getOrElse { emptyList() }
    }.orEmpty()
    require(days.all { it in 1..7 }) { "alarm days must be 1-7 (Monday..Sunday)" }

    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    params.str("message")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    if (days.isNotEmpty()) {
        // Java Calendar 把周日算作 1，而这里 1 表示周一，需要换算
        intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.distinct().sorted().map { it % 7 + 1 }))
    }
    return launch(context, intent, "alarm")
}

private fun startTimer(context: Context, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val seconds = params.int("seconds") ?: error("timer needs seconds (1-86400)")
    require(seconds in 1..86400) { "timer seconds must be 1-86400, got $seconds" }
    val intent = Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    params.str("message")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
    return launch(context, intent, "timer")
}

private fun startDial(context: Context, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val number = normalizeNumber(params.str("number") ?: error("dial needs number"))
        ?: error("number may only contain digits, +, -, spaces and parentheses")
    return launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")), "dial")
}

private fun startSmsDraft(context: Context, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val text = params.str("text") ?: error("sms_draft needs text")
    val number = params.str("number")?.let {
        normalizeNumber(it) ?: error("number may only contain digits, +, -, spaces and parentheses")
    }
    val uri = Uri.parse("smsto:${number?.let { Uri.encode(it) } ?: ""}")
    val intent = Intent(Intent.ACTION_SENDTO, uri).putExtra("sms_body", text)
    return launch(context, intent, "sms_draft")
}

private fun startShare(context: Context, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val text = params.str("text") ?: error("share needs text")
    val base = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    val app = params.str("app")
    if (app != null) {
        val pkg = resolvePackage(context, app)
            ?: return failure("no app matched \"$app\"; call it without 'app' to show the share sheet")
        base.setPackage(pkg)
        return launch(context, base, "share", pkg)
    }
    val chooser = Intent.createChooser(base, null)
    return launch(context, chooser, "share")
}

private fun startNavigate(context: Context, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val destination = params.str("destination")
    val lat = params.double("lat")
    val lng = params.double("lng")
    require((lat == null) == (lng == null)) { "lat and lng must be given together" }
    if (lat != null && lng != null) {
        require(lat in -90.0..90.0) { "lat must be within -90..90, got $lat" }
        require(lng in -180.0..180.0) { "lng must be within -180..180, got $lng" }
    }
    require(destination != null || lat != null) { "navigate needs destination or lat+lng" }
    val mode = params.str("mode") ?: "drive"
    require(mode in NAV_MODES) { "mode must be one of ${NAV_MODES.joinToString()}" }

    val candidates = when (params.str("map")) {
        "amap" -> listOf(PKG_AMAP)
        "baidu" -> listOf(PKG_BAIDU)
        else -> listOf(PKG_AMAP, PKG_BAIDU)
    }
    for (pkg in candidates) {
        if (!isInstalled(context, pkg)) continue
        val uri = if (pkg == PKG_AMAP) {
            amapUri(destination, lat, lng, mode)
        } else {
            baiduUri(destination, lat, lng, mode)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
        if (context.packageManager.resolveActivity(intent, 0) == null) continue
        return launch(context, intent, "navigate", pkg)
    }
    // 两个地图都没装：退回系统 geo: URI，由系统的地图应用或浏览器处理
    val geo = if (lat != null && lng != null) {
        "geo:$lat,$lng?q=$lat,$lng" + (destination?.let { "(${enc(it)})" } ?: "")
    } else {
        "geo:0,0?q=${enc(destination.orEmpty())}"
    }
    return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(geo)), "navigate")
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun amapUri(destination: String?, lat: Double?, lng: Double?, mode: String): String = buildString {
    append("amapuri://route/plan/?sourceApplication=RikkaHub")
    destination?.let { append("&dname=").append(enc(it)) }
    if (lat != null && lng != null) append("&dlat=").append(lat).append("&dlon=").append(lng)
    append("&dev=0&t=").append(
        when (mode) {
            "transit" -> 1
            "walk" -> 2
            "ride" -> 3
            else -> 0
        }
    )
}

private fun baiduUri(destination: String?, lat: Double?, lng: Double?, mode: String): String {
    val dest = if (lat != null && lng != null) {
        "latlng:$lat,$lng" + (destination?.let { "|name:$it" } ?: "")
    } else {
        destination.orEmpty()
    }
    val travel = when (mode) {
        "transit" -> "transit"
        "walk" -> "walking"
        "ride" -> "riding"
        else -> "driving"
    }
    return "baidumap://map/direction?destination=${enc(dest)}&coord_type=gcj02&mode=$travel&src=${enc("me.rerere.rikkahub")}"
}

private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
    context.packageManager.getPackageInfo(pkg, 0)
}.isSuccess

/** 按包名或应用名找目标应用；只用于 share 的 setPackage */
private fun resolvePackage(context: Context, query: String): String? {
    if (isInstalled(context, query)) return query
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcherIntent, 0)
        .firstOrNull { it.loadLabel(pm).toString().contains(query, ignoreCase = true) }
        ?.activityInfo
        ?.packageName
}

/**
 * 启动目标界面。失败时返回可读原因而不是抛异常，模型能据此换做法。
 *
 * 用 NEW_TASK 是因为这里的 Context 是 Application，不是 Activity。
 */
private fun launch(
    context: Context,
    intent: Intent,
    kind: String,
    knownPackage: String? = null,
): kotlinx.serialization.json.JsonObject {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val handler = (knownPackage ?: intent.resolveActivity(context.packageManager)?.packageName)
        ?.let { pkg -> runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() }
    return try {
        context.startActivity(intent)
        buildJsonObject {
            put("success", true)
            put("kind", kind)
            handler?.let { put("opened_app", it) }
            put(
                "note",
                "Opened the app for the user. Nothing is confirmed yet: " +
                    "the user has to finish it in that screen, so do not claim the task is done."
            )
        }
    } catch (_: ActivityNotFoundException) {
        failure("no app on this device can handle $kind")
    } catch (_: SecurityException) {
        failure("the system refused to open $kind; the user may need to allow it")
    }
}

private fun failure(reason: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("success", false)
    put("error", reason)
}

/**
 * 用原始入参重新执行一次系统事务。
 *
 * system_intent 只依赖 Context 调用系统 Intent，不依赖对话状态、不需要模型参与，
 * 因此可以直接重建工具实例执行，比再走一遍生成流程干净。返回是否成功。
 */
suspend fun replaySystemIntent(context: Context, input: String): Boolean = runCatching {
    val tool = buildSystemIntentTool(context)
    val args = Json.parseToJsonElement(input.ifBlank { "{}" })
    val output = tool.execute(args)
    val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    Json.parseToJsonElement(text).jsonObject["success"]?.jsonPrimitive?.booleanOrNull == true
}.getOrDefault(false)
