package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.R
import com.sudokuarena.domain.AbyssActor
import com.sudokuarena.domain.GameType
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Side-scroller cooperativo con render interpolado. Gravedad, plataformas,
 * proyectiles, daño y reapariciones son autoritativos en Node.js.
 */
@Composable
fun AbyssArenaScreen(
    state: ArenaUiState,
    onInput: (Float, Float, Float, Float, Boolean) -> Unit,
    onGlobalChat: (String) -> Unit,
    onRequestPause: () -> Unit,
    onPauseResponse: (Boolean) -> Unit,
    onResume: () -> Unit,
    onTutorialComplete: () -> Unit,
    onOpenTutorial: () -> Unit,
    onExit: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    val snapshot = state.abyssState
    val displayPositions = remember { mutableStateMapOf<String, Offset>() }

    LaunchedEffect(snapshot?.tick) {
        while (isActive && snapshot != null) {
            withFrameNanos {
                snapshot.actors.forEach { actor ->
                    val target = Offset(actor.x, actor.y)
                    val current = displayPositions[actor.id] ?: target
                    displayPositions[actor.id] = current + (target - current) * .30f
                }
            }
        }
    }

    Scaffold(
        topBar = {
            PinnedGameHeader(
                title = "Multi Arena · Abismo cooperativo",
                subtitle = snapshot?.let {
                    val boss = it.actors.firstOrNull { actor -> actor.kind == "BOSS" }
                    "Jefe ${boss?.hp?.toInt() ?: 0}/${boss?.maxHp?.toInt() ?: 0} · ${formatAbyssTime(it.remainingMs)}"
                },
                state = state,
                onTutorial = onOpenTutorial,
                onPause = onRequestPause,
                onExit = { confirmExit = true },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AdaptiveArenaLayout(
                modifier = Modifier.fillMaxSize(),
                board = {
                    AbyssRoom(
                        state = state,
                        displayPositions = displayPositions,
                        onInput = onInput,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                controls = {
                    PauseVoteBanner(state, onPauseResponse)
                    Text(
                        "ESCUADRÓN",
                        color = Color(0xFF102A56),
                        fontWeight = FontWeight.Black,
                    )
                    snapshot?.actors
                        ?.filter { it.kind == "PLAYER" }
                        ?.sortedByDescending(AbyssActor::kills)
                        ?.forEachIndexed { index, actor ->
                            Surface(
                                color = parseAbyssColor(actor.colorHex).copy(alpha = .14f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${index + 1}. ${actor.name ?: "Jugador"}  ✦ ${actor.kills} daño  · ${actor.hp.toInt()}♥",
                                    color = Color(0xFF102A56),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                )
                            }
                        }
                    Text(
                        "Izquierda: corre y desliza ↑ para saltar · Derecha: dispara",
                        color = Color(0xFF526078),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    GlobalGameChat(state, onGlobalChat)
                },
            )
            ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
            if (state.showTutorial) {
                ArenaTutorialOverlay(state.isSoloMode, GameType.ABYSS_ARENA, onTutorialComplete)
            }
            if (state.roomState?.phase == com.sudokuarena.domain.RoomPhase.PAUSED) {
                PauseLayer(state, onResume)
            }
        }
    }
}

@Composable
private fun AbyssRoom(
    state: ArenaUiState,
    displayPositions: Map<String, Offset>,
    onInput: (Float, Float, Float, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.abyssState
    var move by remember { mutableStateOf(Offset.Zero) }
    var aim by remember { mutableStateOf(Offset(0f, -1f)) }
    var attacking by remember { mutableStateOf(false) }
    val animation by rememberInfiniteTransition(label = "abyssSprites").animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Restart),
        label = "abyssWalkCycle",
    )
    fun send() = onInput(move.x, move.y, aim.x, aim.y, attacking)

    Box(
        modifier
            .background(Color(0xFF080B1A), RoundedCornerShape(16.dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.abyss_pvp_floor),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = .18f },
        )
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color(0x77030818)),
                    center = center,
                    radius = size.maxDimension * .68f,
                ),
            )
            // Los rectángulos recibidos son plataformas, no muros top-down.
            snapshot?.obstacles?.forEach { wall ->
                val topLeft = Offset(wall.x * size.width, wall.y * size.height)
                val wallSize = Size(wall.width * size.width, wall.height * size.height)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFF67E8F9), Color(0xFF172542), Color(0xFF091226))),
                    topLeft,
                    wallSize,
                )
                drawRoundRect(
                    Color(0xFF00C9FF).copy(alpha = .45f),
                    topLeft,
                    wallSize,
                    style = Stroke(2.2f),
                )
            }
            snapshot?.items?.forEach { item ->
                drawWeaponPickup(
                    type = item.type,
                    center = Offset(item.x * size.width, item.y * size.height),
                    pulse = .72f + sin(animation * 2f) * .12f,
                )
            }
            snapshot?.projectiles?.forEach { shot ->
                val point = Offset(shot.x * size.width, shot.y * size.height)
                drawCircle(Color(0xFFECFEFF).copy(alpha = .30f), 11f, point)
                drawCircle(Color(0xFF67E8F9), 4.8f, point)
            }
            snapshot?.actors?.forEach { actor ->
                val position = displayPositions[actor.id] ?: Offset(actor.x, actor.y)
                val point = Offset(position.x * size.width, position.y * size.height)
                if (actor.kind == "BOSS") drawAbyssBoss(actor, point, animation)
                else drawStickman(
                    actor = actor,
                    center = point,
                    color = parseAbyssColor(actor.colorHex),
                    phase = animation,
                    now = snapshot.serverTime,
                    isLocal = actor.id == state.playerId,
                )
            }
            // Zonas de control discretas para que el tablero siga siendo legible.
            drawCircle(Color.White.copy(alpha = .09f), 49f, Offset(62f, size.height - 62f), style = Stroke(2.5f))
            drawLine(Color.White.copy(alpha = .55f), Offset(62f, size.height - 82f), Offset(62f, size.height - 43f), 3f)
            drawLine(Color.White.copy(alpha = .55f), Offset(48f, size.height - 57f), Offset(62f, size.height - 43f), 3f)
            drawLine(Color.White.copy(alpha = .55f), Offset(76f, size.height - 57f), Offset(62f, size.height - 43f), 3f)
            drawCircle(Color(0xFF22D3EE).copy(alpha = .18f), 49f, Offset(size.width - 62f, size.height - 62f), style = Stroke(2.5f))
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { move = Offset.Zero; send() },
                        onDrag = { change, delta ->
                            change.consume()
                            val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
                            move = Offset(delta.x / length, delta.y / length)
                            send()
                        },
                        onDragEnd = { move = Offset.Zero; send() },
                        onDragCancel = { move = Offset.Zero; send() },
                    )
                },
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { attacking = true; send() },
                        onDrag = { change, delta ->
                            change.consume()
                            val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
                            aim = Offset(delta.x / length, delta.y / length)
                            attacking = true
                            send()
                        },
                        onDragEnd = { attacking = false; send() },
                        onDragCancel = { attacking = false; send() },
                    )
                },
            )
        }
    }
}

private fun DrawScope.drawAbyssBoss(actor: AbyssActor, center: Offset, phase: Float) {
    val pulse = .92f + kotlin.math.sin(phase * .7f) * .08f
    val radius = 38f * pulse
    drawCircle(Color(0x33FF1744), radius * 1.35f, center)
    drawCircle(Color(0xFF2B102F), radius, center)
    drawCircle(Color(0xFFFF3D71), radius, center, style = Stroke(5f))
    val horn = Path().apply {
        moveTo(center.x - radius * .65f, center.y - radius * .55f)
        lineTo(center.x - radius * 1.05f, center.y - radius * 1.15f)
        lineTo(center.x - radius * .20f, center.y - radius * .80f)
        moveTo(center.x + radius * .65f, center.y - radius * .55f)
        lineTo(center.x + radius * 1.05f, center.y - radius * 1.15f)
        lineTo(center.x + radius * .20f, center.y - radius * .80f)
    }
    drawPath(horn, Color(0xFFFFB4C3), style = Stroke(5f, cap = StrokeCap.Round))
    drawCircle(Color(0xFFFFEB3B), 5f, center + Offset(-13f, -6f))
    drawCircle(Color(0xFFFFEB3B), 5f, center + Offset(13f, -6f))
    val ratio = (actor.hp / actor.maxHp).coerceIn(0f, 1f)
    drawRoundRect(Color(0xCC090B16), center + Offset(-52f, -58f), Size(104f, 9f))
    drawRoundRect(Color(0xFFFF3D71), center + Offset(-52f, -58f), Size(104f * ratio, 9f))
}

private fun DrawScope.drawStickman(
    actor: AbyssActor,
    center: Offset,
    color: Color,
    phase: Float,
    now: Long,
    isLocal: Boolean,
) {
    if (actor.hp <= 0f) {
        val remaining = ((actor.respawnAt - now).coerceAtLeast(0) / 1_000L) + 1
        drawCircle(color.copy(alpha = .18f), 22f, center, style = Stroke(4f))
        return
    }
    val moving = hypot(actor.vx.toDouble(), actor.vy.toDouble()) > .02
    val stride = if (moving) sin(phase) * 6f else 0f
    val bob = kotlin.math.abs(sin(phase)) * 1.8f
    val bodyCenter = center + Offset(0f, -bob)
    val facingAngle = atan2(actor.facingY, actor.facingX)
    val glow = if (isLocal) 12f else 7f

    drawOval(Color.Black.copy(alpha = .38f), bodyCenter + Offset(-15f, 24f), Size(30f, 9f))
    drawCircle(color.copy(alpha = .18f), 25f + glow, bodyCenter)
    drawCircle(Color(0xFF101827), 8.5f, bodyCenter + Offset(0f, -17f))
    drawCircle(color, 7f, bodyCenter + Offset(0f, -17f))
    drawLine(color, bodyCenter + Offset(0f, -9f), bodyCenter + Offset(0f, 10f), 5.5f, StrokeCap.Round)
    drawLine(color, bodyCenter + Offset(0f, -3f), bodyCenter + Offset(-10f - stride * .3f, 5f), 4.2f, StrokeCap.Round)
    drawLine(color, bodyCenter + Offset(0f, -3f), bodyCenter + Offset(10f + stride * .3f, 4f), 4.2f, StrokeCap.Round)
    drawLine(color, bodyCenter + Offset(0f, 9f), bodyCenter + Offset(-8f + stride, 23f), 4.5f, StrokeCap.Round)
    drawLine(color, bodyCenter + Offset(0f, 9f), bodyCenter + Offset(8f - stride, 23f), 4.5f, StrokeCap.Round)

    val weaponLength = when (actor.weapon) {
        "SPEAR" -> 34f
        "BOW" -> 27f
        "HAMMER" -> 25f
        else -> 28f
    }
    val attackOffset = if (actor.attacking) sin(phase * 2f) * .45f else 0f
    val angle = facingAngle + attackOffset
    val hand = bodyCenter + Offset(cos(facingAngle) * 8f, sin(facingAngle) * 8f)
    val tip = hand + Offset(cos(angle) * weaponLength, sin(angle) * weaponLength)
    val weaponColor = when (actor.weapon) {
        "SPEAR" -> Color(0xFFFFB74D)
        "BOW" -> Color(0xFF67E8F9)
        "HAMMER" -> Color(0xFFE879F9)
        else -> Color(0xFFF8FAFC)
    }
    drawLine(weaponColor, hand, tip, if (actor.weapon == "HAMMER") 6f else 3.5f, StrokeCap.Round)
    if (actor.weapon == "HAMMER") drawCircle(weaponColor, 6f, tip)
    if (actor.weapon == "BOW") drawArc(weaponColor, -70f, 140f, false, tip - Offset(8f, 8f), Size(16f, 16f), style = Stroke(2.5f))
    if (actor.attacking && actor.weapon != "BOW") {
        drawArc(color.copy(alpha = .55f), -55f, 110f, false, bodyCenter - Offset(34f, 34f), Size(68f, 68f), style = Stroke(4f))
    }

    val hpRatio = (actor.hp / actor.maxHp).coerceIn(0f, 1f)
    drawRoundRect(Color(0xAA08101F), bodyCenter + Offset(-20f, -34f), Size(40f, 5f))
    drawRoundRect(Color(0xFF22C55E), bodyCenter + Offset(-20f, -34f), Size(40f * hpRatio, 5f))
    if (isLocal) drawCircle(Color.White.copy(alpha = .8f), 31f, bodyCenter, style = Stroke(2f))
}

private fun DrawScope.drawWeaponPickup(type: String, center: Offset, pulse: Float) {
    val color = when (type) {
        "SPEAR" -> Color(0xFFFFB74D)
        "BOW" -> Color(0xFF22D3EE)
        "HAMMER" -> Color(0xFFE879F9)
        else -> Color(0xFF4ADE80)
    }
    drawCircle(color.copy(alpha = .16f), 18f * pulse, center)
    drawCircle(color.copy(alpha = .72f), 11f * pulse, center, style = Stroke(2.5f))
    when (type) {
        "HEAL" -> {
            drawLine(color, center + Offset(-6f, 0f), center + Offset(6f, 0f), 4f)
            drawLine(color, center + Offset(0f, -6f), center + Offset(0f, 6f), 4f)
        }
        "HAMMER" -> {
            drawLine(color, center + Offset(-6f, 7f), center + Offset(5f, -5f), 3f)
            drawRoundRect(color, center + Offset(0f, -9f), Size(11f, 7f))
        }
        "BOW" -> drawArc(color, -70f, 140f, false, center - Offset(8f, 8f), Size(16f, 16f), style = Stroke(3f))
        else -> drawLine(color, center + Offset(-8f, 8f), center + Offset(8f, -8f), 3f)
    }
}

private fun weaponLabel(value: String): String = when (value) {
    "SPEAR" -> "Lanza"
    "BOW" -> "Arco"
    "HAMMER" -> "Martillo"
    else -> "Espada"
}

private fun formatAbyssTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1_000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun parseAbyssColor(hex: String?): Color = runCatching {
    Color(AndroidColor.parseColor(hex ?: "#00E5FF"))
}.getOrDefault(Color(0xFF00E5FF))
