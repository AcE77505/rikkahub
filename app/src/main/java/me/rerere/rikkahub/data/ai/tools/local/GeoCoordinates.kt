package me.rerere.rikkahub.data.ai.tools.local

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 中国大陆常用坐标系的互相转换。
 *
 * 系统定位给的是 **WGS-84**（国际标准），而高德 / 腾讯 / 百度用的是 **GCJ-02**（「火星坐标」，
 * 国家加偏），百度另用 **BD-09**。在国内 WGS-84 与 GCJ-02 相差几百米，直接混用会让导航目标
 * 偏出好几条街。
 *
 * 国内范围之外 GCJ-02 与 WGS-84 相同，这里的函数会原样返回，调用方不需要先判断地域。
 */

/** 国内判定用的经纬度矩形；超出这个范围的坐标不加偏 */
private fun outOfChina(lat: Double, lng: Double): Boolean =
    lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

private const val A = 6378245.0          // 克拉索夫斯基椭球长半轴
private const val EE = 0.00669342162296594323 // 偏心率平方

private fun transformLat(x: Double, y: Double): Double {
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return ret
}

private fun transformLng(x: Double, y: Double): Double {
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return ret
}

/** WGS-84 → GCJ-02；境外原样返回 */
fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
    if (outOfChina(lat, lng)) return lat to lng
    var dLat = transformLat(lng - 105.0, lat - 35.0)
    var dLng = transformLng(lng - 105.0, lat - 35.0)
    val radLat = lat / 180.0 * Math.PI
    var magic = sin(radLat)
    magic = 1 - EE * magic * magic
    val sqrtMagic = sqrt(magic)
    dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
    dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
    return (lat + dLat) to (lng + dLng)
}

/** GCJ-02 → WGS-84；境外原样返回。用一次正向偏移迭代逼近，误差在米级 */
fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
    if (outOfChina(lat, lng)) return lat to lng
    val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
    return (lat * 2 - gcjLat) to (lng * 2 - gcjLng)
}

private const val X_PI = Math.PI * 3000.0 / 180.0

/** GCJ-02 → BD-09 */
fun gcj02ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
    val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
    val theta = kotlin.math.atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
    return (z * sin(theta) + 0.006) to (z * cos(theta) + 0.0065)
}

/** BD-09 → GCJ-02 */
fun bd09ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
    val x = lng - 0.0065
    val y = lat - 0.006
    val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
    val theta = kotlin.math.atan2(y, x) - 0.000003 * cos(x * X_PI)
    return (z * sin(theta)) to (z * cos(theta))
}
