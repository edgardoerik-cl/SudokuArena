package com.sudokuarena.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sudokuarena.domain.TeamMode
import com.sudokuarena.domain.TileType
import com.sudokuarena.domain.BotDifficulty

@Composable
fun RoomLobbyScreen(
    state: ArenaUiState,
    onPowersChanged: (Boolean) -> Unit,
    onTeamModeChanged: (TeamMode) -> Unit,
    onTileTypeChanged: (TileType) -> Unit,
    onBotDifficultyChanged: (BotDifficulty) -> Unit,
    onLoadoutPower: (String) -> Unit,
    onFillWithAi: () -> Unit,
    onStart: () -> Unit,
    onExit: () -> Unit,
) {
    val room = state.roomState
    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArenaLogo(Modifier.size(105.dp))
            Text("Sala ${room?.roomCode ?: "----"}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (room == null) {
                CircularProgressIndicator()
                Text(state.message ?: "Conectando con la arena…")
                OutlinedButton(onClick = onExit) { Text("Volver") }
                return@Column
            }

            val isHost = room.hostPlayerId == state.playerId
            Text(if (isHost) "Eres el Host" else "Esperando configuración del Host")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("JUGADORES ${state.players.size}/4", style = MaterialTheme.typography.labelLarge)
                    state.players.forEach { player ->
                        Text("• ${if (player.isBot) "🤖 " else ""}${player.name}${if (player.id == room.hostPlayerId) "  👑" else ""}")
                    }
                }
            }

            if (room.config.powersEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("EQUIPAMIENTO · ELIGE 2", fontWeight = FontWeight.Black)
                        Text("Toca otro poder para reemplazar el más antiguo.", style = MaterialTheme.typography.bodySmall)
                        val equipped = state.ownPlayer?.powerLoadout.orEmpty()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("FOG" to "Niebla", "REFLECT" to "Escudo", "REVEAL" to "Ojo").forEach { (id, label) ->
                                OutlinedButton(
                                    onClick = { onLoadoutPower(id) },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (id in equipped) "✓ $label" else label) }
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Poderes de sabotaje", fontWeight = FontWeight.Bold)
                            Text("Energía y ataques de Niebla", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = room.config.powersEnabled,
                            onCheckedChange = onPowersChanged,
                            enabled = isHost,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TileTypeSelector(
                        selected = room.config.tileType,
                        enabled = isHost,
                        onSelected = onTileTypeChanged,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("DIFICULTAD DE IA", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BotDifficulty.entries.forEach { difficulty ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = room.config.botDifficulty == difficulty,
                                    onClick = { onBotDifficultyChanged(difficulty) },
                                    enabled = isHost,
                                )
                                Text(botDifficultyLabel(difficulty))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("MODO DE EQUIPO", style = MaterialTheme.typography.labelLarge)
                    TeamMode.entries.forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = room.config.teamMode == mode,
                                onClick = { onTeamModeChanged(mode) },
                                enabled = isHost,
                            )
                            Text(teamModeLabel(mode))
                        }
                    }
                }
            }

            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (isHost) {
                OutlinedButton(
                    onClick = onFillWithAi,
                    enabled = state.players.size < 4,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🤖 Llenar con IA") }
                val validCount = when (room.config.teamMode) {
                    TeamMode.FFA -> state.players.size >= 2
                    TeamMode.TWO_V_TWO, TeamMode.THREE_V_ONE -> state.players.size == 4
                }
                Button(onClick = onStart, enabled = validCount, modifier = Modifier.fillMaxWidth()) {
                    Text(if (validCount) "Iniciar partida" else playerRequirement(room.config.teamMode))
                }
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Salir de la sala") }
        }
    }
}

private fun teamModeLabel(mode: TeamMode): String = when (mode) {
    TeamMode.FFA -> "Todos contra todos (1v1v1v1)"
    TeamMode.TWO_V_TWO -> "2 vs 2 · puntaje compartido"
    TeamMode.THREE_V_ONE -> "3 vs 1 · Host como Jefe"
}

private fun playerRequirement(mode: TeamMode): String = when (mode) {
    TeamMode.FFA -> "Esperando al menos 2 jugadores"
    TeamMode.TWO_V_TWO -> "Se requieren 4 jugadores"
    TeamMode.THREE_V_ONE -> "Se requieren 4 jugadores"
}

private fun botDifficultyLabel(difficulty: BotDifficulty): String = when (difficulty) {
    BotDifficulty.EASY -> "Fácil"
    BotDifficulty.MEDIUM -> "Media"
    BotDifficulty.HARD -> "Difícil"
}
