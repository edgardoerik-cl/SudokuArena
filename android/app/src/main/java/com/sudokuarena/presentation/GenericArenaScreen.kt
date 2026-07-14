package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.Player
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Composable
fun GenericArenaScreen(
    state: ArenaUiState,
    onCellSelected: (Int, Int) -> Unit,
    onMove: (Any?) -> Unit,
    onWordSelection: (CellPosition, CellPosition, String) -> Unit,
    onUseFog: (String) -> Unit,
    onUseReflect: () -> Unit,
    onUseReveal: () -> Unit,
    onFogSwipe: () -> Unit,
    onRematch: () -> Unit,
    onExit: () -> Unit,
) {
    val generic = state.genericBoard
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(gameTitle(state.gameType), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text("Sala ${state.roomCode.orEmpty()} · ${formatGenericTime(state.matchRemainingMs)}")
                    }
                    TextButton(onClick = onExit) { Text("Salir") }
                }
                Scoreboard(state)
                if (state.roomState?.config?.powersEnabled == true) {
                    PowerPanel(state, onUseFog, onUseReflect, onUseReveal)
                }
                if (generic == null) {
                    Text("Preparando matriz compartida…")
                } else {
                    GenericPuzzleGrid(
                        state = generic,
                        players = state.players.associateBy(Player::id),
                        selected = state.selected,
                        enabled = state.penaltyRemainingMs == 0L && state.fogSwipesRemaining == 0,
                        onCellSelected = onCellSelected,
                        onWordSelection = onWordSelection,
                    )
                    GenericMoveControls(state.gameType, state.canMakeGenericMove, onMove)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                }
            }

            if ((state.ownPlayer?.shieldUntil ?: 0L) > state.serverNowMs) ShieldAuraOverlay(Modifier.zIndex(8f))
            if (state.fogSwipesRemaining > 0) {
                FogInkOverlay(state.fogSwipesRemaining, onFogSwipe, Modifier.zIndex(20f))
            }
            if (state.penaltyRemainingMs > 0) PenaltyOverlay(state.penaltyRemainingMs, Modifier.zIndex(15f))
            if (state.explosionRemainingMs > 0) MineExplosionOverlay(Modifier.zIndex(18f))
            if (state.matchResults.isNotEmpty()) {
                MatchResultsOverlay(state, onNewSoloGame = {}, onRematch = onRematch, onExit = onExit, modifier = Modifier.zIndex(30f))
            }
        }
    }
}

@Composable
fun GenericPuzzleGrid(
    state: GenericBoardState,
    players: Map<String, Player>,
    selected: CellPosition?,
    enabled: Boolean,
    onCellSelected: (Int, Int) -> Unit,
    onWordSelection: (CellPosition, CellPosition, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var dragStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var dragEnd by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    val gestureModifier = if (state.gameType == GameType.WORD_SEARCH) {
        Modifier.pointerInput(state.board, enabled) {
            fun position(offset: Offset): CellPosition = CellPosition(
                row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1),
                column = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1),
            )
            detectDragGestures(
                onDragStart = { if (enabled) position(it).also { pos -> dragStart = pos; dragEnd = pos } },
                onDrag = { change, _ -> if (enabled) dragEnd = position(change.position) },
                onDragEnd = {
                    val start = dragStart
                    val end = dragEnd
                    if (start != null && end != null) {
                        val word = wordAlong(state, start, end)
                        if (word.isNotBlank()) onWordSelection(start, end, word)
                    }
                    dragStart = null
                    dragEnd = null
                },
            )
        }
    } else {
        Modifier.pointerInput(state.board, enabled) {
            detectTapGestures { offset ->
                if (!enabled) return@detectTapGestures
                val row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1)
                val col = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1)
                onCellSelected(row, col)
            }
        }
    }

    Canvas(
        modifier.then(gestureModifier)
            .fillMaxWidth()
            .aspectRatio(state.columns.toFloat() / state.rows.toFloat())
            .background(Color.White, RoundedCornerShape(8.dp)),
    ) {
        val cellWidth = size.width / state.columns
        val cellHeight = size.height / state.rows
        state.board.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, cell ->
                val origin = Offset(col * cellWidth, row * cellHeight)
                val ownerColor = cell.ownerId?.let { players[it]?.colorHex }?.let(::parseGenericColor)
                val fill = when {
                    selected == CellPosition(row, col) -> Color(0xFFFFF59D)
                    ownerColor != null -> ownerColor.copy(alpha = 0.25f)
                    cell.isBlocked -> Color(0xFF263238)
                    else -> Color.Transparent
                }
                drawRect(fill, origin, Size(cellWidth, cellHeight))
                renderGenericCell(state.gameType, cell.value, cell.meta, cell.isBlocked, origin, cellWidth, cellHeight, textMeasurer)
                drawRect(Color(0xFFB0BEC5), origin, Size(cellWidth, cellHeight), style = Stroke(1.2f))
                if (ownerColor != null) drawRect(ownerColor, origin, Size(cellWidth, cellHeight), style = Stroke(2.4f))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderGenericCell(
    gameType: GameType,
    value: Any?,
    meta: Map<String, Any?>,
    isBlocked: Boolean,
    origin: Offset,
    width: Float,
    height: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val center = Offset(origin.x + width / 2f, origin.y + height / 2f)
    when (gameType) {
        GameType.NONOGRAM -> if (value == true) drawRect(Color(0xFF243B6B), origin + Offset(width * .16f, height * .16f), Size(width * .68f, height * .68f))
        GameType.DOTS_AND_BOXES -> {
            drawCircle(Color(0xFF102A56), min(width, height) * .08f, Offset(origin.x, origin.y))
            if (meta["top"] == true) drawLine(Color(0xFF00A8FF), origin, Offset(origin.x + width, origin.y), 5f)
            if (meta["left"] == true) drawLine(Color(0xFF00A8FF), origin, Offset(origin.x, origin.y + height), 5f)
            if (meta["right"] == true) drawLine(Color(0xFF00A8FF), Offset(origin.x + width, origin.y), Offset(origin.x + width, origin.y + height), 5f)
            if (meta["bottom"] == true) drawLine(Color(0xFF00A8FF), Offset(origin.x, origin.y + height), Offset(origin.x + width, origin.y + height), 5f)
        }
        GameType.RUMMIKUB -> {
            val tileColor = when (meta["tileColor"]) {
                "RED" -> Color(0xFFE53935); "BLUE" -> Color(0xFF1E88E5); "GREEN" -> Color(0xFF00A651); else -> Color(0xFFFF8F00)
            }
            drawRoundRect(tileColor.copy(alpha = .18f), origin + Offset(2f, 2f), Size(width - 4f, height - 4f))
            drawCenteredText(value, center, width, textMeasurer, tileColor)
        }
        GameType.HITORI -> {
            drawCenteredText(value, center, width, textMeasurer, if (isBlocked) Color.White else Color(0xFF102A56))
        }
        else -> drawCenteredText(if (value == "MINE") "✹" else value, center, width, textMeasurer, if (value == "MINE") Color.Red else Color(0xFF102A56))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenteredText(
    value: Any?, center: Offset, width: Float, textMeasurer: androidx.compose.ui.text.TextMeasurer, color: Color,
) {
    if (value == null) return
    val layout = textMeasurer.measure(value.toString(), TextStyle(color = color, fontSize = (width * .35f).coerceIn(10f, 24f).sp, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

@Composable
private fun GenericMoveControls(gameType: GameType, enabled: Boolean, onMove: (Any?) -> Unit) {
    var text by remember(gameType) { mutableStateOf("") }
    when (gameType) {
        GameType.MINESWEEPER -> Button(onClick = { onMove("REVEAL") }, enabled = enabled) { Text("Revelar casilla") }
        GameType.NONOGRAM -> Button(onClick = { onMove("FILL") }, enabled = enabled) { Text("Pintar píxel") }
        GameType.HITORI -> Button(onClick = { onMove("BLOCK") }, enabled = enabled) { Text("Apagar número") }
        GameType.DOTS_AND_BOXES -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("top" to "Arriba", "right" to "Derecha", "bottom" to "Abajo", "left" to "Izquierda").forEach { (id, label) ->
                OutlinedButton({ onMove(id) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label, fontSize = 10.sp) }
            }
        }
        GameType.WORD_SEARCH -> Text("Arrastra desde la primera hasta la última letra de una palabra.")
        GameType.CROSSWORD -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.uppercase().take(1) }, label = { Text("Letra") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(4.dp))
            Button({ onMove(text); text = "" }, enabled = enabled && text.isNotBlank()) { Text("Colocar") }
        }
        else -> {
            val maximum = if (gameType == GameType.RUMMIKUB) 13 else 9
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..maximum).chunked(if (maximum > 9) 7 else 5).forEach { numbers ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        numbers.forEach { number -> Button({ onMove(number) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(number.toString()) } }
                        repeat((if (maximum > 9) 7 else 5) - numbers.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MineExplosionOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().background(Color(0x55FF3D00))) {
        repeat(18) { index ->
            val angle = index * 6.283f / 18f
            val direction = Offset(kotlin.math.cos(angle), kotlin.math.sin(angle))
            drawCircle(if (index % 2 == 0) Color(0xFFFF3D00) else Color(0xFFFFC107), 14f, center + direction * (45f + index * 6f))
        }
        drawCircle(Color(0xFF212121), 42f, center)
    }
}

private fun wordAlong(state: GenericBoardState, start: CellPosition, end: CellPosition): String {
    val rowStep = (end.row - start.row).coerceIn(-1, 1)
    val colStep = (end.column - start.column).coerceIn(-1, 1)
    val distance = max(kotlin.math.abs(end.row - start.row), kotlin.math.abs(end.column - start.column))
    if (rowStep != 0 && colStep != 0 && kotlin.math.abs(end.row - start.row) != kotlin.math.abs(end.column - start.column)) return ""
    return (0..distance).joinToString("") { index ->
        state.board[start.row + rowStep * index][start.column + colStep * index].value?.toString().orEmpty()
    }
}

private fun parseGenericColor(hex: String): Color = runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(Color.Gray)

fun gameTitle(type: GameType): String = when (type) {
    GameType.SUDOKU -> "Sudoku Arena"; GameType.MINESWEEPER -> "Buscaminas Arena"; GameType.WORD_SEARCH -> "Sopa de Letras Arena"
    GameType.CROSSWORD -> "Crucigramas Arena"; GameType.NONOGRAM -> "Nonogram Arena"; GameType.DOTS_AND_BOXES -> "Timbiriche Arena"
    GameType.KAKURO -> "Kakuro Arena"; GameType.MATHDOKU -> "Mathdoku Arena"; GameType.HITORI -> "Hitori Arena"; GameType.RUMMIKUB -> "Rummikub Arena"
}

private fun formatGenericTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
