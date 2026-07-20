package com.sudokuarena.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.GameType
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class GameFxKind { SUCCESS, ERROR, IMPACT, EXPLOSION, FROST, MAGIC, COMBO, WAVE }

data class GameFxEvent(
    val id: Long,
    val kind: GameFxKind,
    val x: Float,
    val y: Float,
    val text: String? = null,
    val color: Color,
    val intensity: Float,
    val createdAt: Long,
    val durationMs: Long,
)

@Stable
class GameFxController internal constructor() {
    internal val events = mutableStateListOf<GameFxEvent>()
    internal var shakeNonce by mutableIntStateOf(0)
        private set
    private var sequence = 0L

    fun emit(
        kind: GameFxKind,
        x: Float = .5f,
        y: Float = .5f,
        text: String? = null,
        color: Color = defaultFxColor(kind),
        intensity: Float = 1f,
        durationMs: Long = if (kind == GameFxKind.WAVE) 1_250 else 760,
        shake: Boolean = kind == GameFxKind.ERROR || kind == GameFxKind.EXPLOSION,
    ) {
        val now = System.currentTimeMillis()
        sequence += 1
        events += GameFxEvent(
            id = sequence, kind = kind, x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f),
            text = text, color = color, intensity = intensity.coerceIn(.25f, 2.5f),
            createdAt = now, durationMs = durationMs,
        )
        if (shake) shakeNonce += 1
    }

    internal fun remove(id: Long) {
        events.removeAll { it.id == id }
    }
}

@Composable
fun rememberGameFxController(): GameFxController = remember { GameFxController() }

/**
 * Host visual común. Sacude solamente el contenido de juego y mantiene el HUD
 * estable; la capa de partículas no consume eventos táctiles.
 */
@Composable
fun GameFxHost(
    controller: GameFxController,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shakeX = remember { Animatable(0f) }
    val shakeY = remember { Animatable(0f) }
    LaunchedEffect(controller.shakeNonce) {
        if (controller.shakeNonce == 0) return@LaunchedEffect
        val offsets = listOf(-10f to 4f, 8f to -5f, -6f to 3f, 4f to -2f, 0f to 0f)
        offsets.forEach { (x, y) ->
            shakeX.animateTo(x, tween(42))
            shakeY.animateTo(y, tween(42))
        }
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = shakeX.value
                translationY = shakeY.value
            },
            content = content,
        )
        GameFxOverlay(controller, Modifier.fillMaxSize())
    }
}

@Composable
fun GameFxOverlay(controller: GameFxController, modifier: Modifier = Modifier) {
    val snapshot = controller.events.toList()
    if (snapshot.isEmpty()) return
    val frame by rememberInfiniteTransition(label = "gameFxClock").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart), label = "gameFxFrame",
    )
    val textMeasurer = rememberTextMeasurer()
    snapshot.forEach { event ->
        LaunchedEffect(event.id) {
            delay(event.durationMs + 80)
            controller.remove(event.id)
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val redrawClock = frame
        val now = System.currentTimeMillis()
        snapshot.forEach { event ->
            val progress = ((now - event.createdAt).toFloat() / event.durationMs).coerceIn(0f, 1f)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val center = Offset(size.width * event.x, size.height * event.y)
            val burstCount = when (event.kind) {
                GameFxKind.EXPLOSION -> 22
                GameFxKind.IMPACT, GameFxKind.FROST -> 12
                GameFxKind.COMBO, GameFxKind.SUCCESS -> 16
                else -> 8
            }
            repeat(burstCount) { index ->
                val seed = (event.id * 31 + index * 17).toFloat()
                val angle = (index.toFloat() / burstCount * PI.toFloat() * 2f) + (seed % 9f) * .07f
                val distance = size.minDimension * (.035f + .14f * progress) * event.intensity * (if (index % 3 == 0) 1.35f else 1f)
                val particle = center + Offset(cos(angle), sin(angle)) * distance
                val particleColor = when {
                    event.kind == GameFxKind.FROST && index % 2 == 0 -> Color.White
                    event.kind == GameFxKind.EXPLOSION && index % 3 == 0 -> Color(0xFFFFD54F)
                    else -> event.color
                }
                if (index % 2 == 0) {
                    drawCircle(particleColor.copy(alpha = alpha), (2.2f + index % 4) * event.intensity * alpha.coerceAtLeast(.25f), particle)
                } else {
                    val side = (3f + index % 5) * event.intensity
                    drawRect(particleColor.copy(alpha = alpha), particle - Offset(side / 2, side / 2), Size(side, side))
                }
            }
            val ringRadius = size.minDimension * (.018f + progress * .10f) * event.intensity
            drawCircle(event.color.copy(alpha = alpha * .72f), ringRadius, center, style = Stroke((5f * alpha).coerceAtLeast(1f)))
            if (event.kind == GameFxKind.ERROR || event.kind == GameFxKind.EXPLOSION) {
                drawRect(event.color.copy(alpha = alpha * .10f * event.intensity))
            }
            event.text?.let { label ->
                val scale = 1f + sin(progress * PI.toFloat()) * .24f
                val style = TextStyle(
                    color = Color.White.copy(alpha = alpha),
                    fontSize = (15f * scale * event.intensity.coerceAtMost(1.45f)).sp,
                    fontWeight = FontWeight.Black,
                    shadow = androidx.compose.ui.graphics.Shadow(event.color, Offset.Zero, 10f),
                )
                val layout = textMeasurer.measure(label, style)
                drawText(
                    layout,
                    topLeft = Offset(center.x - layout.size.width / 2f, center.y - progress * size.height * .10f - layout.size.height / 2f),
                )
            }
        }
    }
}

private fun defaultFxColor(kind: GameFxKind): Color = when (kind) {
    GameFxKind.SUCCESS -> Color(0xFF22C55E)
    GameFxKind.ERROR -> Color(0xFFEF4444)
    GameFxKind.IMPACT -> Color(0xFF60A5FA)
    GameFxKind.EXPLOSION -> Color(0xFFF97316)
    GameFxKind.FROST -> Color(0xFF22D3EE)
    GameFxKind.MAGIC -> Color(0xFFC084FC)
    GameFxKind.COMBO -> Color(0xFFFACC15)
    GameFxKind.WAVE -> Color(0xFFE11D48)
}

/** Fondo procedural ligero compartido por las arenas de lógica. */
@Composable
fun GameAtmosphere(gameType: GameType, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "atmosphere-$gameType").animateFloat(
        0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart), label = "atmospherePhase",
    )
    Canvas(modifier) {
        val primary = when (gameType.ordinal % 5) {
            0 -> Color(0xFF0EA5E9)
            1 -> Color(0xFF8B5CF6)
            2 -> Color(0xFF14B8A6)
            3 -> Color(0xFFF97316)
            else -> Color(0xFFEC4899)
        }
        drawRect(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFFF8FAFC), primary.copy(alpha = .10f), Color(0xFFEFF6FF)),
                start = Offset(size.width * phase, 0f),
                end = Offset(size.width * (1f - phase), size.height),
            ),
        )
        repeat(18) { index ->
            val depth = .35f + (index % 5) * .13f
            val x = ((index * 61 % 103) / 102f * size.width + phase * size.width * .12f * depth).mod(size.width)
            val baseY = (index * 43 % 101) / 100f * size.height
            val y = baseY + sin((phase + index * .11f) * PI.toFloat() * 2f) * 18f * depth
            val radius = (2f + index % 4) * depth
            drawCircle(primary.copy(alpha = .08f + depth * .07f), radius, Offset(x, y))
            if (index % 4 == 0) {
                drawLine(primary.copy(alpha = .08f), Offset(x - radius * 2, y), Offset(x + radius * 2, y), 1.5f)
                drawLine(primary.copy(alpha = .08f), Offset(x, y - radius * 2), Offset(x, y + radius * 2), 1.5f)
            }
        }
    }
}
