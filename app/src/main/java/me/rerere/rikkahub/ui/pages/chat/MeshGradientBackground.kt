package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.hooks.rememberAppLifecycleState
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TWO_PI = 2f * PI.toFloat()

/**
 * Gemini 风格动态渐变背景。
 *
 * 原理:
 *  1. 底层一个线性渐变(顶部偏蓝、底部偏白)。
 *  2. 上面叠几个 radialGradient 光斑(中心有色 → 边缘透明),天生就是柔的。
 *  3. 每个光斑用一个独立周期的动画,沿正弦/余弦轨迹缓慢漂移。
 *
 * 动画只在界面处于前台(RESUMED)时推进，切到后台或失去焦点时自动暂停，避免后台空转。
 * 用 [Animatable] 而不是 rememberInfiniteTransition，才能按生命周期启停。
 * 不依赖 Modifier.blur,全 API 级别可用。
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        // 暗色:顶部深蓝,向下渐隐到接近纯黑
        arrayOf(
            0.0f to Color(0xFF1B2A45),
            0.22f to Color(0xFF15223A),
            0.45f to Color(0xFF0D1626),
            0.65f to Color(0xFF0A0F18),
            1.0f to Color(0xFF080B12),
        )
    } else {
        // 亮色:顶部偏蓝,向下渐隐到白
        arrayOf(
            0.0f to Color(0xFFAFD0F2),
            0.22f to Color(0xFFCBE0F6),
            0.45f to Color(0xFFF1F7FD),
            0.65f to Color(0xFFFFFFFF),
            1.0f to Color(0xFFFFFFFF),
        )
    }

    // 光斑配色(蓝 / 青 / 淡蓝 / 暖色)及浓度,亮暗各一套
    val blobBlue = if (dark) Color(0xFF3E6FB0) else Color(0xFF9EC5F0)
    val blobTeal = if (dark) Color(0xFF2E7D74) else Color(0xFFA8E6E0)
    val blobLightBlue = if (dark) Color(0xFF4A6E96) else Color(0xFFB6D7F2)
    val blobWarm = if (dark) Color(0xFF7C5F9E) else Color(0xFFFFC8D2)
    val alphaBlue = if (dark) 0.56f else 0.72f
    val alphaTeal = if (dark) 0.44f else 0.56f
    val alphaLightBlue = if (dark) 0.48f else 0.62f
    val alphaWarm = if (dark) 0.32f else 0.42f

    // 每个光斑一个独立的相位(弧度)，用 Animatable 推进。
    // 每轮跨度取 2π 的整数倍，保证 sin/cos 在循环衔接处连续，不会跳变。
    val p1 = remember { Animatable(0f) }
    val p2 = remember { Animatable(0f) }
    val p3 = remember { Animatable(0f) }
    val p4 = remember { Animatable(0f) }

    val lifecycleState by rememberAppLifecycleState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(resumed) {
        // 切到后台时整个 effect 被取消，动画停在当前位置；回到前台从这里继续
        if (!resumed) return@LaunchedEffect
        // p1: 20 圈 / 110 秒（cos(1.15x) 在 40π 处也回到整数圈，衔接连续）
        launch {
            while (true) {
                val from = p1.value
                p1.animateTo(from + TWO_PI * 20f, tween(110_000, easing = LinearEasing))
                p1.snapTo(from)
            }
        }
        // p2: 1 圈 / 7 秒
        launch {
            while (true) {
                val from = p2.value
                p2.animateTo(from + TWO_PI, tween(7_000, easing = LinearEasing))
                p2.snapTo(from)
            }
        }
        // p3: 10 圈 / 85 秒（cos(0.9x) 在 20π 处回到整数圈）
        launch {
            while (true) {
                val from = p3.value
                p3.animateTo(from + TWO_PI * 10f, tween(85_000, easing = LinearEasing))
                p3.snapTo(from)
            }
        }
        // p4: 10 圈 / 62 秒（cos(1.1x) 在 20π 处回到整数圈）
        launch {
            while (true) {
                val from = p4.value
                p4.animateTo(from + TWO_PI * 10f, tween(62_000, easing = LinearEasing))
                p4.snapTo(from)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 光斑半径取屏幕较长边的比例,够大才柔
            val r = maxOf(w, h)

            // 光斑全部聚在顶部,向下渐隐,保留下半屏留白
            // 顶部蓝(主色,横向缓慢漂移)
            drawBlob(
                center = Offset(
                    w * 0.48f + sin(p1.value) * w * 0.38f,
                    h * 0.08f + cos(p1.value * 1.15f) * h * 0.18f,
                ),
                radius = r * 0.36f,
                color = blobBlue,
                centerAlpha = alphaBlue,
            )
            // 左上青绿点缀
            drawBlob(
                center = Offset(
                    w * 0.18f + sin(p2.value + PI.toFloat() * 0.55f) * w * 0.30f,
                    h * 0.24f + cos(p2.value) * h * 0.20f,
                ),
                radius = r * 0.28f,
                color = blobTeal,
                centerAlpha = alphaTeal,
            )
            // 右上淡蓝
            drawBlob(
                center = Offset(
                    w * 0.82f + sin(p3.value + PI.toFloat() * 0.9f) * w * -0.34f,
                    h * 0.12f + cos(p3.value * 0.9f) * h * 0.18f,
                ),
                radius = r * 0.30f,
                color = blobLightBlue,
                centerAlpha = alphaLightBlue,
            )
            // 暖色光斑给运动提供更明显的色彩参照
            drawBlob(
                center = Offset(
                    w * 0.58f + sin(p4.value + PI.toFloat() * 1.25f) * w * 0.28f,
                    h * 0.34f + cos(p4.value * 1.1f) * h * 0.16f,
                ),
                radius = r * 0.26f,
                color = blobWarm,
                centerAlpha = alphaWarm,
            )
        }

        content()
    }
}

/** 画一个柔光斑:中心有色,向外渐隐到透明。 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    centerAlpha: Float = 0.75f,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = centerAlpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
