package com.sudokuarena.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sudokuarena.domain.MatchResultEntry
import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.TeamMode
import kotlinx.coroutines.delay

@Composable
fun MatchResultsOverlay(
    state: ArenaUiState,
    onNewSoloGame: () -> Unit,
    onRematch: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = if (state.isSoloMode) {
        listOf(
            MatchResultEntry(
                rank = 1,
                playerId = "solo",
                name = if (state.soloNewRecord) "¡Nuevo récord!" else "${gameName(state.gameType)} completado",
                score = state.ownPlayer?.score ?: 0,
                teamId = "solo",
                teamScore = state.ownPlayer?.score ?: 0,
                role = "PLAYER",
            ),
        )
    } else state.matchResults

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xEE090B1C)),
    ) {
        ConfettiCanvas(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (state.isSoloMode) "${gameName(state.gameType).uppercase()} COMPLETADO" else "RESULTADOS DE MULTI ARENA",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            if (state.isSoloMode) {
                Text(
                    "Tiempo ${formatResultDuration(state.soloElapsedMs)} · Récord ${formatResultDuration(state.soloBestMs)} · ${state.soloErrors} errores",
                    color = Color(0xFFFFCA28),
                )
            }
            Text("Nivel ${state.level} · ${state.totalXp} XP", color = Color.White)
            if (state.isColorMode) {
                Text("MODO COLORES", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SudokuTilePalette.colors.forEach { color -> ColorTile(color, Modifier.size(18.dp)) }
                }
            }
            Spacer(Modifier.height(18.dp))
            results.forEachIndexed { index, result ->
                AnimatedResultCard(
                    result = result,
                    index = index,
                    showTeamScore = !state.isSoloMode && state.roomState?.config?.teamMode !in setOf(TeamMode.FFA, TeamMode.DUEL),
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (state.isSoloMode) {
                Button(onClick = onNewSoloGame, modifier = Modifier.fillMaxWidth()) { Text("Jugar otro") }
            } else {
                Button(
                    onClick = onRematch,
                    enabled = !state.rematchRequested,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.rematchRequested) "Esperando votos de revancha…" else "Revancha")
                }
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Volver al inicio", color = Color.White) }
        }
    }
}

@Composable
private fun AnimatedResultCard(result: MatchResultEntry, index: Int, showTeamScore: Boolean) {
    var visible by remember(result.playerId) { mutableStateOf(false) }
    LaunchedEffect(result.playerId) {
        delay(index * 180L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(420)) { it } + scaleIn(initialScale = 0.78f, animationSpec = tween(420)),
    ) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(rankMedal(result.rank), style = MaterialTheme.typography.headlineMedium)
                Column(Modifier.weight(1f)) {
                    Text("${if (result.isBot) "🤖 " else ""}${result.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (result.role == "BOSS") Text("JEFE", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    else if (result.role == "RAIDER") Text("EQUIPO ASALTANTE", color = Color(0xFF1565C0))
                    if (result.maxCombo > 1) Text("Mejor combo: ${result.maxCombo}", color = Color(0xFFE65100))
                }
                Text(
                    if (showTeamScore) "${result.teamScore} equipo" else "${result.score} pts",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ConfettiCanvas(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "confetti")
    val fall by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing), RepeatMode.Restart),
        label = "confettiFall",
    )
    val colors = listOf(Color(0xFFFFCA28), Color(0xFF42A5F5), Color(0xFFEF5350), Color(0xFF66BB6A), Color.White)
    Canvas(modifier) {
        repeat(58) { index ->
            val lane = ((index * 73) % 101) / 101f
            val speed = 0.75f + (index % 5) * 0.11f
            val start = ((index * 37) % 100) / 100f
            val y = ((start + fall * speed) % 1f) * size.height
            val x = lane * size.width
            val piece = 7f + index % 4 * 2f
            drawRect(colors[index % colors.size], Offset(x, y), Size(piece, piece * 1.7f))
        }
    }
}

private fun rankMedal(rank: Int): String = when (rank) {
    1 -> "🥇"
    2 -> "🥈"
    3 -> "🥉"
    else -> "#$rank"
}

private fun formatResultDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

private fun gameName(gameType: GameType): String = when (gameType) {
    GameType.SUDOKU -> "Sudoku"
    GameType.MINESWEEPER -> "Buscaminas"
    GameType.WORD_SEARCH -> "Sopa de Letras"
    GameType.CROSSWORD -> "Crucigrama"
    GameType.NONOGRAM -> "Nonogram"
    GameType.DOTS_AND_BOXES -> "Timbiriche"
    GameType.KAKURO -> "Kakuro"
    GameType.MATHDOKU -> "Mathdoku"
    GameType.HITORI -> "Hitori"
    GameType.RUMMIKUB -> "Rummikub"
}
