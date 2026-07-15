package com.sudokuarena.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sudokuarena.domain.RoomPhase

@Composable
fun PauseControl(state: ArenaUiState, onRequestPause: () -> Unit) {
    val active = if (state.isSoloMode) !state.soloCompleted else state.roomState?.phase in setOf(RoomPhase.PLAYING, RoomPhase.SUDDEN_DEATH)
    if (active && state.roomState?.pauseRequesterId == null) {
        OutlinedButton(onClick = onRequestPause) { Text(if (state.isSoloMode) "Pausar" else "Pedir pausa") }
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
    val pendingForMe = !state.isSoloMode && room?.pauseRequesterId != null && room.phase != RoomPhase.PAUSED && room.pauseRequesterId != state.playerId
    val paused = state.isLocallyPaused || room?.phase == RoomPhase.PAUSED
    val countdown = ((state.resumeCountdownMs + 999) / 1_000).toInt()
    if (!pendingForMe && !paused) return

    Box(modifier.fillMaxSize().background(Color(0xB8F3F6FC)), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    pendingForMe -> {
                        Text("⏸ SOLICITUD DE PAUSA", fontWeight = FontWeight.Black, fontSize = 22.sp)
                        val requester = state.players.firstOrNull { it.id == room?.pauseRequesterId }?.name ?: "Un jugador"
                        Text("$requester solicita detener temporalmente la partida.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton({ onRespond(false) }, Modifier.weight(1f)) { Text("Rechazar") }
                            Button({ onRespond(true) }, Modifier.weight(1f)) { Text("Aceptar") }
                        }
                    }
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
