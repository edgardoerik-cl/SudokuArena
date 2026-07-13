package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

@Composable
fun ArenaRoute(viewModel: ArenaViewModel) {
    val state by viewModel.state.collectAsState()
    ArenaScreen(
        state = state,
        onCellSelected = viewModel::select,
        onNumber = viewModel::place,
    )
}

@Composable
fun ArenaScreen(
    state: ArenaUiState,
    onCellSelected: (row: Int, column: Int) -> Unit,
    onNumber: (Int) -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Sudoku Arena", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (state.connected) "En línea · partida ${state.revision}" else "Conectando…",
                color = if (state.connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Scoreboard(state)
            Spacer(Modifier.height(12.dp))
            SudokuBoard(
                board = state.board,
                players = state.players.associateBy { it.id },
                selected = state.selected,
                enabled = state.penaltyRemainingMs == 0L,
                onCellSelected = onCellSelected,
            )
            Spacer(Modifier.height(14.dp))
            state.conquestMessage?.let {
                Text(it, color = Color(0xFF1565C0), style = MaterialTheme.typography.titleSmall)
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.penaltyRemainingMs > 0) {
                Text(
                    "Bloqueado ${(state.penaltyRemainingMs + 999) / 1000} s",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(state.selected?.let { "Casilla ${it.row + 1}, ${it.column + 1}" } ?: "Selecciona una casilla")
            }
            Spacer(Modifier.weight(1f))
            NumberPad(enabled = state.canPlay, onNumber = onNumber)
        }
    }
}

@Composable
private fun Scoreboard(state: ArenaUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.players.forEach { player ->
            val color = parseColor(player.colorHex)
            Card(modifier = Modifier.weight(1f)) {
                Column(
                    Modifier
                        .background(color.copy(alpha = if (player.id == state.playerId) 0.28f else 0.13f))
                        .padding(8.dp),
                ) {
                    Text(player.name, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    Text(player.score.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun SudokuBoard(
    board: List<List<com.sudokuarena.domain.BoardCell>>,
    players: Map<String, com.sudokuarena.domain.Player>,
    selected: CellPosition?,
    enabled: Boolean,
    onCellSelected: (Int, Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(4.dp))
            .pointerInput(board, enabled) {
                detectTapGestures { offset ->
                    if (!enabled) return@detectTapGestures
                    val column = floor(offset.x / (size.width / 9f)).toInt().coerceIn(0, 8)
                    val row = floor(offset.y / (size.height / 9f)).toInt().coerceIn(0, 8)
                    onCellSelected(row, column)
                }
            },
    ) {
        val cellSize = size.width / 9f
        for (row in 0..8) for (column in 0..8) {
            val cell = board[row][column]
            val topLeft = Offset(column * cellSize, row * cellSize)
            val ownerColor = cell.ownerId?.let { players[it]?.colorHex }?.let(::parseColor)
            val background = when {
                selected == CellPosition(row, column) -> Color(0xFFFFF59D)
                ownerColor != null -> ownerColor.copy(alpha = if (cell.clearing) 0.55f else 0.30f)
                cell.clearing -> Color.LightGray
                else -> Color.Transparent
            }
            drawRect(background, topLeft, Size(cellSize, cellSize))

            cell.value?.let { value ->
                val layout = textMeasurer.measure(value.toString(), TextStyle(color = Color.Black, fontSize = 22.sp))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        topLeft.x + (cellSize - layout.size.width) / 2f,
                        topLeft.y + (cellSize - layout.size.height) / 2f,
                    ),
                )
            }
        }

        for (index in 0..9) {
            val width = if (index % 3 == 0) 4f else 1f
            val position = index * cellSize
            drawLine(Color.Black, Offset(position, 0f), Offset(position, size.height), width)
            drawLine(Color.Black, Offset(0f, position), Offset(size.width, position), width)
        }
        drawRect(Color.Black, style = Stroke(width = 4f))
    }
}

@Composable
private fun NumberPad(enabled: Boolean, onNumber: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..3).forEach { column ->
                    val number = row * 3 + column
                    Button(
                        onClick = { onNumber(number) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(number.toString()) }
                }
            }
        }
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(Color.Gray)
