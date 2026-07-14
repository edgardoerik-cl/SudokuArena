package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.BoardEventType
import com.sudokuarena.domain.Player
import kotlin.math.floor

@Composable
fun ArenaRoute(viewModel: ArenaViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val haptics = remember(context) { HapticFeedbackController(context) }
    LaunchedEffect(viewModel, haptics) {
        viewModel.haptics.collect(haptics::play)
    }

    ArenaScreen(
        state = state,
        onCellSelected = viewModel::select,
        onNumber = viewModel::place,
        onUseFog = viewModel::useFog,
        onReaction = viewModel::sendReaction,
        onFogSwipe = viewModel::cleanFogSwipe,
    )
}

@Composable
fun ArenaScreen(
    state: ArenaUiState,
    onCellSelected: (row: Int, column: Int) -> Unit,
    onNumber: (Int) -> Unit,
    onUseFog: (String) -> Unit,
    onReaction: (String) -> Unit,
    onFogSwipe: () -> Unit,
) {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sudoku Arena", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (state.connected) "En línea · partida ${state.revision}" else "Conectando…",
                    color = if (state.connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
                BoardEventBanner(state)
                Spacer(Modifier.height(8.dp))
                Scoreboard(state)
                Spacer(Modifier.height(8.dp))
                PowerPanel(state, onUseFog)
                Spacer(Modifier.height(8.dp))
                SudokuBoard(
                    board = state.board,
                    players = state.players.associateBy { it.id },
                    selected = state.selected,
                    enabled = state.penaltyRemainingMs == 0L && state.fogSwipesRemaining == 0,
                    onCellSelected = onCellSelected,
                )
                Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))
                ReactionMenu(onReaction)
                Spacer(Modifier.height(8.dp))
                NumberPad(enabled = state.canPlay, onNumber = onNumber)
            }

            if (state.fogSwipesRemaining > 0) {
                FogInkOverlay(
                    swipesRemaining = state.fogSwipesRemaining,
                    onValidSwipe = onFogSwipe,
                    modifier = Modifier.zIndex(20f),
                )
            }
        }
    }
}

@Composable
private fun BoardEventBanner(state: ArenaUiState) {
    val event = state.boardEvent ?: return
    val seconds = ((state.boardEventRemainingMs + 999) / 1_000).coerceAtLeast(0)
    val (title, color) = when (event.type) {
        BoardEventType.MIRROR_HOUR -> "🪞 HORA ESPEJO · x2 puntos · $seconds s" to Color(0xFF6A1B9A)
        BoardEventType.GOLDEN_CELLS -> "✨ CASILLAS DE ORO · $seconds s" to Color(0xFFF57F17)
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Text(title, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
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
            Box(modifier = Modifier.weight(1f)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .background(color.copy(alpha = if (player.id == state.playerId) 0.28f else 0.13f))
                            .padding(7.dp),
                    ) {
                        Text(player.name, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        Text("${player.score} pts", style = MaterialTheme.typography.titleMedium)
                        Text("⚡ ${player.energy}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
                state.reactions[player.id]?.let { reaction ->
                    FloatingReaction(
                        reaction = reaction,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .zIndex(2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingReaction(reaction: ReactionUi, modifier: Modifier = Modifier) {
    val progress = remember(reaction.reactionId) { Animatable(0f) }
    LaunchedEffect(reaction.reactionId) {
        progress.animateTo(1f, tween(2_000, easing = FastOutSlowInEasing))
    }
    Text(
        text = emojiFor(reaction.emojiId),
        fontSize = 32.sp,
        modifier = modifier.graphicsLayer {
            alpha = (1f - progress.value).coerceIn(0f, 1f)
            translationY = -90f * progress.value
            scaleX = 1f + progress.value * 0.25f
            scaleY = scaleX
        },
    )
}

@Composable
private fun PowerPanel(state: ArenaUiState, onUseFog: (String) -> Unit) {
    val ownPlayer = state.ownPlayer ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Energía ${ownPlayer.energy}%", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { ownPlayer.energy / 100f },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp),
            )
        }
        if (ownPlayer.energy >= 100) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.players.filter { it.id != state.playerId }.forEach { target ->
                    Button(
                        onClick = { onUseFog(target.id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("🌫 ${target.name}", maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SudokuBoard(
    board: List<List<BoardCell>>,
    players: Map<String, Player>,
    selected: CellPosition?,
    enabled: Boolean,
    onCellSelected: (Int, Int) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val hasClearingCells = board.any { row -> row.any(BoardCell::clearing) }
    val burstScale by animateFloatAsState(
        targetValue = if (hasClearingCells) 1.45f else 1f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "sectionBurst",
    )

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
                cell.golden -> Color(0xFFFFD54F)
                ownerColor != null -> ownerColor.copy(alpha = if (cell.clearing) 0.55f else 0.30f)
                cell.clearing -> Color.LightGray
                else -> Color.Transparent
            }
            drawRect(background, topLeft, Size(cellSize, cellSize))
            if (cell.golden) {
                drawRect(Color(0xFFFFA000), topLeft, Size(cellSize, cellSize), style = Stroke(width = 4f))
            }

            cell.value?.let { value ->
                val scale = if (cell.clearing) burstScale else 1f
                val layout = textMeasurer.measure(
                    value.toString(),
                    TextStyle(
                        color = Color.Black.copy(alpha = if (cell.clearing) 0.72f else 1f),
                        fontSize = 22.sp * scale,
                        fontWeight = if (cell.golden) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
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
private fun ReactionMenu(onReaction: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            REACTIONS.forEach { (id, emoji) ->
                TextButton(onClick = {
                    onReaction(id)
                    expanded = false
                }) { Text(emoji, fontSize = 25.sp) }
            }
        }
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(48.dp),
        ) { Text("💬", fontSize = 22.sp) }
    }
}

@Composable
private fun FogInkOverlay(
    swipesRemaining: Int,
    onValidSwipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val opacity = (0.35f + swipesRemaining / 6f * 0.55f).coerceIn(0.35f, 0.9f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(swipesRemaining) {
                var distance = 0f
                var startedAt = 0L
                detectDragGestures(
                    onDragStart = {
                        distance = 0f
                        startedAt = SystemClock.elapsedRealtime()
                    },
                    onDragEnd = {
                        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
                        if (distance >= 100f && distance / elapsed >= 0.35f) onValidSwipe()
                    },
                    onDragCancel = { distance = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    distance += dragAmount.getDistance()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = opacity))
            val ink = Color.Black.copy(alpha = (opacity + 0.08f).coerceAtMost(1f))
            drawCircle(ink, radius = size.minDimension * 0.34f, center = Offset(size.width * 0.22f, size.height * 0.2f))
            drawCircle(ink, radius = size.minDimension * 0.42f, center = Offset(size.width * 0.78f, size.height * 0.48f))
            drawCircle(ink, radius = size.minDimension * 0.36f, center = Offset(size.width * 0.28f, size.height * 0.8f))
        }
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🌫 ATAQUE DE NIEBLA", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Desliza rápido para limpiar", color = Color.White)
                Text("Faltan $swipesRemaining barridos", color = Color(0xFFFFD54F))
            }
        }
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

private val REACTIONS = listOf(
    "LAUGH" to "😂",
    "CRY" to "😢",
    "ANGRY" to "😡",
    "SURPRISED" to "😮",
)

private fun emojiFor(id: String): String = REACTIONS.firstOrNull { it.first == id }?.second ?: "❓"

private fun parseColor(hex: String): Color =
    runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(Color.Gray)
