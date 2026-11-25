package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.coroutines.resume

internal const val LOCATION_TOOL_NAME = "get_current_location"

/** 请求新一次定位的等待上限；超时后回退到缓存位置 */
private const val FRESH_FIX_TIMEOUT_MS = 15_000L

/** 三套坐标系各自的用途，写在工具结果里供模型选字段 */
private const val LOCATION_NOTE =
    "Coordinates come in three systems. Use wgs84 for international maps and records; " +
        "gcj02 for Amap, Tencent and most Chinese maps; bd09 for Baidu Maps. " +
        "They can differ by several hundred meters in mainland China, so pass the matching pair to a map tool. " +
        "These are raw coordinates, not a place name: geocoding is not done here."

/**
 * 读取设备当前位置。
 *
 * 只返回原始坐标与精度等数值，**不做逆地理编码**：`Geocoder` 依赖 Google Play 服务或厂商实现，
 * 国内设备上大多返回空，模型拿到「解析失败」反而不如拿坐标自己去查。
 *
 * 返回值里用 `fresh_fix` 区分这次是新测到的还是系统缓存的位置，模型可以据此判断可信度。
 */
internal fun buildLocationTool(context: Context): Tool = Tool(
    name = LOCATION_TOOL_NAME,
    description = """
        Read the device's current location. Returns the position in three coordinate systems:
        wgs84 (the system's raw value), gcj02 (Amap, Tencent, most Chinese maps) and bd09 (Baidu Maps).
        In mainland China they differ by several hundred meters, so use the pair that matches the map
        you are about to hand the coordinates to.
        Also returns accuracy and, when the provider supplies them, altitude, bearing and speed.
        Requires the location permission; if it is not granted, an error is returned and the permission
        request is triggered from the settings page.
        Set prefer_fresh to false to accept a cached position immediately instead of waiting for a fix.
        The result is raw coordinates, not a place name: look up or describe the place yourself.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prefer_fresh", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "When true (default) wait for a new fix; when false return the cached position if available."
                    )
                })
            }
        )
    },
    execute = { args ->
        val preferFresh = args.jsonObject["prefer_fresh"]?.jsonPrimitive?.booleanOrNull ?: true
        listOf(UIMessagePart.Text(readLocation(context, preferFresh).toString()))
    }
)

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

private suspend fun readLocation(context: Context, preferFresh: Boolean): kotlinx.serialization.json.JsonObject {
    if (!hasLocationPermission(context)) {
        return locationFailure(
            "Location permission is not granted. Ask the user to enable it in the app's tool settings."
        )
    }
    val manager = runCatching {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }.getOrNull() ?: return locationFailure("This device has no location service.")

    val providers = runCatching { manager.getProviders(true) }.getOrElse { emptyList() }
        .filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
    if (providers.isEmpty()) {
        return locationFailure(
            "Location is turned off, or no provider is enabled. Ask the user to turn on location in system settings."
        )
    }

    // 新定位优先；拿不到（超时、provider 不响应）再退回系统缓存的位置
    var fresh = false
    var location: Location? = null
    if (preferFresh) {
        location = awaitFreshFix(manager, providers)
        fresh = location != null
    }
    if (location == null) {
        location = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }
    if (location == null) {
        return locationFailure(
            "No location is available yet. Move somewhere with a clear view of the sky and try again."
        )
    }
    return locationState(location, fresh)
}

private suspend fun awaitFreshFix(manager: LocationManager, providers: List<String>): Location? =
    withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                // 以下三个在 API 29+ 有默认实现，minSdk 24 需要显式实现
                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            providers.forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }

private fun locationState(location: Location, fresh: Boolean): kotlinx.serialization.json.JsonObject {
    val lat = location.latitude
    val lng = location.longitude
    // 系统给的是 WGS-84；国内下游（高德/腾讯/百度）要 GCJ-02 或 BD-09，相差几百米
    val (gLat, gLng) = wgs84ToGcj02(lat, lng)
    val (bLat, bLng) = gcj02ToBd09(gLat, gLng)
    return buildJsonObject {
        put("success", true)
        // 系统原始值，用于国际地图、坐标记录等
        put("wgs84", coordinate(lat, lng))
        // 高德 / 腾讯 / 国内多数地图
        put("gcj02", coordinate(gLat, gLng))
        // 百度地图
        put("bd09", coordinate(bLat, bLng))
        put("accuracy_meters", location.accuracy.toDouble())
        put("provider", location.provider.orEmpty())
        put("time_ms", location.time)
        // 新测到的位置比缓存可靠；模型可以据此决定要不要提醒用户移动位置
        put("fresh_fix", fresh)
        if (location.hasAltitude()) {
            put("altitude_meters", location.altitude)
        }
        if (location.hasBearing()) {
            put("bearing_degrees", location.bearing.toDouble())
        }
        if (location.hasSpeed()) {
            put("speed_mps", location.speed.toDouble())
        }
        put("note", LOCATION_NOTE)
    }
}

private fun coordinate(lat: Double, lng: Double): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("lat", lat)
    put("lng", lng)
}

private fun locationFailure(reason: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("success", false)
    put("error", reason)
}
