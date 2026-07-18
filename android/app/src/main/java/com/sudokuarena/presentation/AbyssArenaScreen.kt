package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.GameType
import kotlinx.coroutines.isActive
import kotlin.math.hypot

/**
 * Render 60 FPS con interpolación hacia snapshots autoritativos de 20 Hz.
 * Los controles sólo envían intención; el servidor conserva física, daño y colisiones.
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
                    displayPositions[actor.id] = current + (target - current) * .28f
                }
            }
        }
    }

    Scaffold(
        topBar = {
            PinnedGameHeader(
                title = "Multi Arena · Abismo Arena",
                subtitle = snapshot?.let { "Nivel ${it.level}/${it.maxLevel}${if (it.bossLevel) " · JEFE" else ""}" },
                state = state,
                onTutorial = onOpenTutorial,
                onPause = onRequestPause,
                onExit = { confirmExit = true },
            )
        },
    ) { padding ->
        Row(
            Modifier.fillMaxSize().padding(padding).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AbyssRoom(
                state = state,
                displayPositions = displayPositions,
                onInput = onInput,
                modifier = Modifier.weight(.76f).fillMaxHeight(),
            )
            Column(
                Modifier.weight(.24f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PauseVoteBanner(state, onPauseResponse)
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF101B3B)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(if (snapshot?.bossLevel == true) "☠ CÁMARA DEL JEFE" else "ABISMO COOPERATIVO", color = Color(0xFF00E5FF))
                        snapshot?.actors?.filter { it.kind == "PLAYER" }?.forEach {
                            Text("${it.name ?: "Jugador"} · ${it.hp.toInt()}/${it.maxHp.toInt()} HP", color = parseAbyssColor(it.colorHex), fontSize = 12.sp)
                        }
                        Text("Izquierda: mover · Derecha: apuntar y disparar", color = Color.White, fontSize = 10.sp)
                    }
                }
                GlobalGameChat(state, onGlobalChat)
            }
        }
        ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
        if (state.showTutorial) {
            ArenaTutorialOverlay(state.isSoloMode, GameType.ABYSS_ARENA, onTutorialComplete)
        }
        if (state.roomState?.phase == com.sudokuarena.domain.RoomPhase.PAUSED) {
            PauseLayer(state, onResume)
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
    var shooting by remember { mutableStateOf(false) }
    fun send() = onInput(move.x, move.y, aim.x, aim.y, shooting)

    Box(modifier.background(Color(0xFF080B1A), RoundedCornerShape(16.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            snapshot?.obstacles?.forEach { wall ->
                drawRoundRect(
                    Color(0xFF263A60),
                    Offset(wall.x * size.width, wall.y * size.height),
                    Size(wall.width * size.width, wall.height * size.height),
                )
            }
            snapshot?.items?.forEach { item ->
                val color = when (item.type) { "DAMAGE" -> Color(0xFFFF1744); "FIRE_RATE" -> Color(0xFF00E5FF); else -> Color(0xFF00E676) }
                drawCircle(color, 9f, Offset(item.x * size.width, item.y * size.height))
            }
            snapshot?.projectiles?.forEach { shot ->
                drawCircle(Color(0xFFB8F8FF), 6f, Offset(shot.x * size.width, shot.y * size.height))
            }
            snapshot?.actors?.forEach { actor ->
                val point = displayPositions[actor.id] ?: Offset(actor.x, actor.y)
                val center = Offset(point.x * size.width, point.y * size.height)
                val color = if (actor.kind == "PLAYER") parseAbyssColor(actor.colorHex)
                else if (actor.kind == "BOSS") Color(0xFFFF1744) else Color(0xFF7C3AED)
                val radius = if (actor.kind == "BOSS") 31f else 17f
                drawCircle(color.copy(alpha = .23f), radius + 9f, center)
                drawCircle(color, radius, center)
                drawCircle(Color.White, radius, center, style = Stroke(2f))
                val hpRatio = (actor.hp / actor.maxHp).coerceIn(0f, 1f)
                drawRect(Color(0xFF182238), Offset(center.x - radius, center.y - radius - 9f), Size(radius * 2f, 4f))
                drawRect(Color(0xFF00E676), Offset(center.x - radius, center.y - radius - 9f), Size(radius * 2f * hpRatio, 4f))
            }
            drawCircle(Color.White.copy(alpha = .12f), 54f, Offset(72f, size.height - 72f), style = Stroke(3f))
            drawCircle(Color(0xFF00E5FF).copy(alpha = .35f), 54f, Offset(size.width - 72f, size.height - 72f), style = Stroke(3f))
        }
        Row(Modifier.fillMaxSize()) {
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
                    )
                },
            )
            Box(
                Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { shooting = true; send() },
                        onDrag = { change, delta ->
                            change.consume()
                            val length = hypot(delta.x, delta.y).coerceAtLeast(1f)
                            aim = Offset(delta.x / length, delta.y / length)
                            shooting = true
                            send()
                        },
                        onDragEnd = { shooting = false; send() },
                    )
                },
            )
        }
    }
}

private fun parseAbyssColor(hex: String?): Color = runCatching {
    Color(AndroidColor.parseColor(hex ?: "#00E5FF"))
}.getOrDefault(Color(0xFF00E5FF))
