package com.sudokuarena.presentation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.RoomPhase

@Composable
fun ConfirmExitDialog(visible: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Rendirse y salir?") },
        text = { Text("La partida continuará para los demás jugadores y perderás tu progreso actual.") },
        confirmButton = { Button(onClick = onConfirm) { Text("Sí, salir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Seguir jugando") } },
    )
}

/** Barra fija: Salir conserva su slot aun en pantallas angostas. */
@Composable
fun PinnedGameHeader(
    title: String,
    subtitle: String?,
    state: ArenaUiState,
    onTutorial: () -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(color = Color(0xFFF8FAFF), shadowElevation = 7.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
                ExitControl(onExit)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTutorial) { Text("?", fontWeight = FontWeight.Black, fontSize = 20.sp) }
                AudioControls()
                PauseControl(state, onPause)
            }
        }
    }
}

@Composable
fun PauseControl(
    state: ArenaUiState,
    onRequestPause: () -> Unit,
) {
    val active = if (state.isSoloMode) !state.soloCompleted else state.roomState?.phase in setOf(RoomPhase.PLAYING, RoomPhase.SUDDEN_DEATH)
    if (!active) return
    val room = state.roomState
    val pending = !state.isSoloMode && room?.pauseRequesterId != null
    val pulse by rememberInfiniteTransition(label = "pauseBadge").animateFloat(
        initialValue = .82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "pausePulse",
    )
    Box {
        NeonActionButton(
            contentDescription = if (pending) "Revisar solicitud de pausa" else if (state.isSoloMode) "Pausar partida" else "Solicitar pausa",
            onClick = { if (!pending) onRequestPause() },
        ) {
            val barWidth = size.width * .17f
            val barHeight = size.height * .52f
            drawRoundRect(Color(0xFF00D5FF), Offset(size.width * .27f, size.height * .24f), Size(barWidth, barHeight), androidx.compose.ui.geometry.CornerRadius(barWidth * .35f))
            drawRoundRect(Color(0xFF7C3AED), Offset(size.width * .56f, size.height * .24f), Size(barWidth, barHeight), androidx.compose.ui.geometry.CornerRadius(barWidth * .35f))
        }
        if (pending) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .size(12.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
        }
    }
}

@Composable
fun PauseVoteBanner(state: ArenaUiState, onRespond: (Boolean) -> Unit) {
    val room = state.roomState ?: return
    val requesterId = room.pauseRequesterId ?: return
    if (state.isSoloMode || room.phase !in setOf(RoomPhase.PLAYING, RoomPhase.SUDDEN_DEATH)) return
    // Si el id no pertenece a la sala, el evento no es una solicitud válida.
    // Esto también descarta valores heredados como la cadena "null".
    val requester = state.players.firstOrNull { it.id == requesterId }?.name ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF8FAFF),
        border = BorderStroke(2.dp, Color(0xFF7C3AED)),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("$requester solicita pausa", fontWeight = FontWeight.Black, color = Color(0xFF102A56))
                Text("Sí: ${room.pauseVotes}  |  No: ${room.pauseNoVotes}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (requesterId != state.playerId) {
                Surface(shape = CircleShape, color = Color(0xFF00A651)) {
                    IconButton(onClick = { onRespond(true) }, modifier = Modifier.size(40.dp)) { Text("✓", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp) }
                }
                Surface(shape = CircleShape, color = Color(0xFFE53935)) {
                    IconButton(onClick = { onRespond(false) }, modifier = Modifier.size(40.dp)) { Text("×", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp) }
                }
            }
        }
    }
}

@Composable
fun ExitControl(onExit: () -> Unit) {
    NeonActionButton(contentDescription = "Salir de la partida", onClick = onExit) {
        val cyan = Color(0xFF00D5FF)
        drawLine(cyan, Offset(size.width * .28f, size.height * .22f), Offset(size.width * .28f, size.height * .78f), 3.5f, StrokeCap.Round)
        drawLine(cyan, Offset(size.width * .28f, size.height * .22f), Offset(size.width * .55f, size.height * .22f), 3.5f, StrokeCap.Round)
        drawLine(cyan, Offset(size.width * .28f, size.height * .78f), Offset(size.width * .55f, size.height * .78f), 3.5f, StrokeCap.Round)
        drawLine(Color(0xFF7C3AED), Offset(size.width * .45f, size.height * .5f), Offset(size.width * .78f, size.height * .5f), 4f, StrokeCap.Round)
        drawLine(Color(0xFF7C3AED), Offset(size.width * .66f, size.height * .37f), Offset(size.width * .79f, size.height * .5f), 4f, StrokeCap.Round)
        drawLine(Color(0xFF7C3AED), Offset(size.width * .66f, size.height * .63f), Offset(size.width * .79f, size.height * .5f), 4f, StrokeCap.Round)
    }
}

@Composable
private fun NeonActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 3.dp),
        shape = CircleShape,
        color = Color(0xFFF8FAFF),
        border = BorderStroke(1.5.dp, Color(0x6600A8FF)),
        shadowElevation = 5.dp,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Canvas(
                Modifier
                    .size(28.dp)
                    .semantics { this.contentDescription = contentDescription },
                onDraw = glyph,
            )
        }
    }
}

@Composable
fun PauseLayer(
    state: ArenaUiState,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val room = state.roomState
    val paused = state.isLocallyPaused || room?.phase == RoomPhase.PAUSED
    val countdown = ((state.resumeCountdownMs + 999) / 1_000).toInt()
    if (!paused) return

    Box(modifier.fillMaxSize().background(Color(0xB8F3F6FC)), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    countdown > 0 -> {
                        Text("PREPÁRATE", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        Text(countdown.toString(), fontWeight = FontWeight.Black, fontSize = 72.sp, color = ArenaColors.ElectricBlue)
                    }
                    else -> {
                        Text("⏸ PARTIDA EN PAUSA", fontWeight = FontWeight.Black, fontSize = 24.sp)
                        Text("El tablero está difuminado para proteger la partida.")
                        if (state.isSoloMode || room?.pauseRequesterId == state.playerId) {
                            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(if (state.isSoloMode) "Continuar" else "Reanudar para todos") }
                        } else Text("Esperando a que ${state.players.firstOrNull { it.id == room?.pauseRequesterId }?.name ?: "el solicitante"} continúe…")
                    }
                }
            }
        }
    }
}
