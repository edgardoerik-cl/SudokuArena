package com.sudokuarena.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.RoomPhase

@Composable
fun PauseControl(state: ArenaUiState, onRequestPause: () -> Unit) {
    val active = if (state.isSoloMode) !state.soloCompleted else state.roomState?.phase in setOf(RoomPhase.PLAYING, RoomPhase.SUDDEN_DEATH)
    if (active && state.roomState?.pauseRequesterId == null) {
        NeonActionButton(
            contentDescription = if (state.isSoloMode) "Pausar partida" else "Solicitar pausa",
            onClick = onRequestPause,
        ) {
            val barWidth = size.width * .17f
            val barHeight = size.height * .52f
            drawRoundRect(
                color = Color(0xFF00D5FF),
                topLeft = Offset(size.width * .27f, size.height * .24f),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * .35f),
            )
            drawRoundRect(
                color = Color(0xFF7C3AED),
                topLeft = Offset(size.width * .56f, size.height * .24f),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * .35f),
            )
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
    onRespond: (Boolean) -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val room = state.roomState
    val pending = !state.isSoloMode && room?.pauseRequesterId != null && room.phase != RoomPhase.PAUSED
    val pendingForMe = pending && room?.pauseRequesterId != state.playerId
    val paused = state.isLocallyPaused || room?.phase == RoomPhase.PAUSED
    val countdown = ((state.resumeCountdownMs + 999) / 1_000).toInt()
    if (!pending && !paused) return

    if (pending) {
        Box(modifier.fillMaxSize().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(.94f)) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val requester = state.players.firstOrNull { it.id == room?.pauseRequesterId }?.name ?: "Un jugador"
                        Column(Modifier.weight(1f)) {
                            Text("⏸ Solicitud de pausa", fontWeight = FontWeight.Black)
                            Text(if (pendingForMe) "$requester quiere pausar la partida." else "Esperando la respuesta de los demás…", fontSize = 13.sp)
                        }
                        if (pendingForMe) {
                            OutlinedButton({ onRespond(false) }) { Text("Rechazar") }
                            Button({ onRespond(true) }) { Text("Aceptar") }
                        }
                    }
                }
            }
        }
        return
    }

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
