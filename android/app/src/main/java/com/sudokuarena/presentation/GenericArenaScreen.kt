package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlin.math.roundToInt

@Composable
fun GenericArenaScreen(
    state: ArenaUiState,
    onCellSelected: (Int, Int) -> Unit,
    onMove: (Any?) -> Unit,
    onMoveAt: (Int, Int, Any?) -> Unit,
    onWordSelection: (CellPosition, CellPosition, String) -> Unit,
    onUseFog: (String) -> Unit,
    onUseReflect: () -> Unit,
    onUseReveal: () -> Unit,
    onFogSwipe: () -> Unit,
    onRematch: () -> Unit,
    onRequestPause: () -> Unit,
    onPauseResponse: (Boolean) -> Unit,
    onResume: () -> Unit,
    onTutorialComplete: () -> Unit,
    onOpenTutorial: () -> Unit,
    onReaction: (String) -> Unit,
    onSecretChat: (String) -> Unit,
    onGlobalChat: (String) -> Unit,
    onNewSoloGame: () -> Unit,
    onExit: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    val generic = state.genericBoard
    val boardPulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(generic?.revision) {
        if ((generic?.revision ?: 0L) > 0L) {
            boardPulse.snapTo(.965f)
            boardPulse.animateTo(1f, tween(190))
        }
    }
    Scaffold(
        topBar = {
            PinnedGameHeader(
                title = gameTitle(state.gameType),
                subtitle = if (state.isSoloMode) {
                    "Solitario · ${formatGenericTime(state.soloElapsedMs)} · ${state.soloErrors} errores"
                } else {
                    "Sala ${state.roomCode.orEmpty()} · ${formatGenericTime(state.matchRemainingMs)}"
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
                Modifier.fillMaxSize()
                    .blur(if (state.isLocallyPaused || state.roomState?.phase == com.sudokuarena.domain.RoomPhase.PAUSED) 18.dp else 0.dp),
                board = {
                BoxWithConstraints(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (generic == null) {
                        Text("Preparando matriz compartida…")
                    } else {
                        val boardRatio = if (state.gameType == GameType.NONOGRAM) {
                            (generic.columns + 3.1f) / (generic.rows + 3.1f)
                        } else generic.columns.toFloat() / generic.rows.toFloat()
                        val boardWidth = minOf(maxWidth, maxHeight * boardRatio)
                        val boardHeight = boardWidth / boardRatio
                        Box(Modifier.size(boardWidth, boardHeight), contentAlignment = Alignment.Center) {
                            GenericPuzzleGrid(
                                state = generic,
                                players = state.players.associateBy(Player::id),
                                selected = state.selected,
                                enabled = state.canInteractGeneric,
                                onCellSelected = onCellSelected,
                                onWordSelection = onWordSelection,
                                onDirectMove = onMoveAt,
                                secretKey = state.secretKey,
                                secretCanGuess = state.secretRole != "CAPTAIN",
                                modifier = Modifier.graphicsLayer {
                                    scaleX = boardPulse.value
                                    scaleY = boardPulse.value
                                    alpha = .78f + boardPulse.value * .22f
                                },
                            )
                        }
                    }
                }
                },
                controls = {
                    if (!state.isSoloMode && state.roomState?.pauseRequesterId != null &&
                        state.roomState.phase in setOf(com.sudokuarena.domain.RoomPhase.PLAYING, com.sudokuarena.domain.RoomPhase.SUDDEN_DEATH)
                    ) PauseVoteBanner(state, onPauseResponse)
                    Scoreboard(state)
                    if (state.roomState?.config?.powersEnabled == true) {
                        PowerPanel(state, onUseFog, onUseReflect, onUseReveal)
                    }
                    if (generic != null) {
                        if (state.gameType == GameType.SECRET_CODE) SecretRoleHeader(state)
                    val waitingTurn = !state.isSoloMode && state.genericTurnPlayerId != null && state.genericTurnPlayerId != state.playerId
                    if (waitingTurn) {
                        Surface(
                            color = Color(0xFFFFF3CD), shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFA000)),
                        ) {
                            Text(
                                "⏳ Esperando tu turno… ${state.players.firstOrNull { it.id == state.genericTurnPlayerId }?.name.orEmpty()}",
                                Modifier.fillMaxWidth().padding(10.dp), fontWeight = FontWeight.Black, color = Color(0xFF6D4C00),
                            )
                        }
                    }
                    PuzzleHints(generic)
                    GenericMoveControls(state, state.canMakeGenericMove, onMove, onMoveAt, onSecretChat)
                    if (!state.isSoloMode) {
                        ReactionMenu(onReaction)
                        GlobalGameChat(state, onGlobalChat)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                }
                }
            )

            if ((state.ownPlayer?.shieldUntil ?: 0L) > state.serverNowMs) ShieldAuraOverlay(Modifier.zIndex(8f))
            if (state.fogSwipesRemaining > 0) {
                FogInkOverlay(state.fogSwipesRemaining, onFogSwipe, Modifier.zIndex(20f))
            }
            if (state.penaltyRemainingMs > 0) PenaltyOverlay(state.penaltyRemainingMs, Modifier.zIndex(15f))
            if (state.explosionRemainingMs > 0) MineExplosionOverlay(Modifier.zIndex(18f))
            if (state.soloCompleted || state.matchResults.isNotEmpty()) {
                MatchResultsOverlay(state, onNewSoloGame = onNewSoloGame, onRematch = onRematch, onExit = onExit, modifier = Modifier.zIndex(30f))
            }
            if (state.isLocallyPaused || state.roomState?.phase == com.sudokuarena.domain.RoomPhase.PAUSED) {
                PauseLayer(state, onResume, Modifier.zIndex(50f))
            }
            if (state.showTutorial) {
                ArenaTutorialOverlay(state.isSoloMode, state.gameType, onTutorialComplete, Modifier.zIndex(60f))
            }
            ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
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
    onDirectMove: (Int, Int, Any?) -> Unit,
    secretKey: List<String> = emptyList(),
    secretCanGuess: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (state.gameType == GameType.CAPITAL_ARENA) {
        CapitalArenaBoard(state, players, modifier)
        return
    }
    if (state.gameType == GameType.NONOGRAM) {
        NonogramPuzzleGrid(state, players, enabled, onDirectMove, modifier)
        return
    }
    val textMeasurer = rememberTextMeasurer()
    var dragStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var dragEnd by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var selectedDot by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var selectedBridgeIsland by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    val illegalWater = remember(state.board, state.gameType) { if (state.gameType == GameType.NURIKABE) illegalWaterCells(state) else emptySet() }
    val warningAlpha by rememberInfiniteTransition(label = "nurikabeWarning").animateFloat(
        initialValue = .35f, targetValue = .9f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "water2x2",
    )
    val gestureModifier = if (state.gameType == GameType.WORD_SEARCH) {
        Modifier.pointerInput(state.board, enabled) {
            fun position(offset: Offset): CellPosition = CellPosition(
                row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1),
                column = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1),
            )
            detectDragGestures(
                onDragStart = { if (enabled) position(it).also { pos -> dragStart = pos; dragEnd = pos } },
                onDrag = { change, _ -> if (enabled) { change.consume(); dragEnd = position(change.position) } },
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
    } else if (state.gameType == GameType.DOTS_AND_BOXES) {
        Modifier
            .pointerInput(state.board, enabled) {
                fun dot(offset: Offset) = CellPosition(
                    (offset.y / (size.height / state.rows)).roundToInt().coerceIn(0, state.rows),
                    (offset.x / (size.width / state.columns)).roundToInt().coerceIn(0, state.columns),
                )
                var start: CellPosition? = null
                var end: CellPosition? = null
                detectDragGestures(
                    onDragStart = { if (enabled) { start = dot(it); end = start } },
                    onDrag = { change, _ -> if (enabled) { change.consume(); end = dot(change.position) } },
                    onDragEnd = {
                        start?.let { a -> end?.let { b -> dotsEdge(a, b, state.rows, state.columns)?.let { onDirectMove(it.row, it.col, it.side) } } }
                        start = null; end = null
                    },
                )
            }
            .pointerInput(state.board, enabled, selectedDot) {
                detectTapGestures { offset ->
                    if (!enabled) return@detectTapGestures
                    val dot = CellPosition(
                        (offset.y / (size.height / state.rows)).roundToInt().coerceIn(0, state.rows),
                        (offset.x / (size.width / state.columns)).roundToInt().coerceIn(0, state.columns),
                    )
                    val first = selectedDot
                    if (first == null) selectedDot = dot else {
                        dotsEdge(first, dot, state.rows, state.columns)?.let { onDirectMove(it.row, it.col, it.side) }
                        selectedDot = null
                    }
                }
            }
    } else if (state.gameType == GameType.BRIDGES) {
        Modifier.pointerInput(state.board, enabled, selectedBridgeIsland) {
            detectTapGestures { offset ->
                if (!enabled) return@detectTapGestures
                val row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1)
                val col = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1)
                if (state.board[row][col].meta["island"] != true) return@detectTapGestures
                val tapped = CellPosition(row, col)
                val first = selectedBridgeIsland
                if (first == null || first == tapped) selectedBridgeIsland = tapped.takeUnless { first == tapped }
                else if ("${tapped.row}:${tapped.column}" in bridgeTargets(state, first)) {
                    onDirectMove((first.row + tapped.row) / 2, (first.column + tapped.column) / 2, "BRIDGE")
                    selectedBridgeIsland = null
                } else selectedBridgeIsland = tapped
            }
        }
    } else if (state.gameType == GameType.SLITHERLINK) {
        fun edgeAt(offset: Offset, canvasSize: androidx.compose.ui.unit.IntSize): Triple<Int, Int, String> {
            val width = canvasSize.width.toFloat() / state.columns
            val height = canvasSize.height.toFloat() / state.rows
            val row = floor(offset.y / height).toInt().coerceIn(0, state.rows - 1)
            val col = floor(offset.x / width).toInt().coerceIn(0, state.columns - 1)
            val localX = offset.x - col * width
            val localY = offset.y - row * height
            val side = listOf("left" to localX, "right" to width - localX, "top" to localY, "bottom" to height - localY)
                .minBy { it.second }.first
            return Triple(row, col, side)
        }
        Modifier
            .pointerInput(state.board, enabled) {
                detectTapGestures { offset ->
                    if (!enabled) return@detectTapGestures
                    val (row, col, side) = edgeAt(offset, size)
                    if (state.board[row][col].meta[side] != true) onDirectMove(row, col, side)
                }
            }
            .pointerInput(state.board, enabled) {
            var lastEdge: String? = null
            fun drawAt(offset: Offset) {
                if (!enabled) return
                val (row, col, side) = edgeAt(offset, size)
                val edge = "$row:$col:$side"
                if (edge != lastEdge && state.board[row][col].meta[side] != true) {
                    lastEdge = edge
                    onDirectMove(row, col, side)
                }
            }
            detectDragGestures(
                onDragStart = { lastEdge = null; drawAt(it) },
                onDrag = { change, _ -> if (enabled) change.consume(); drawAt(change.position) },
                onDragEnd = { lastEdge = null },
            )
        }
    } else {
        Modifier.pointerInput(state.board, enabled) {
            detectTapGestures { offset ->
                if (!enabled) return@detectTapGestures
                val row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1)
                val col = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1)
                when (state.gameType) {
                    GameType.MINESWEEPER -> onDirectMove(row, col, "REVEAL")
                    GameType.NONOGRAM -> onDirectMove(row, col, "FILL")
                    GameType.HITORI -> onDirectMove(row, col, "BLOCK")
                    GameType.NURIKABE -> {
                        val next = when (state.board[row][col].value?.toString()) {
                            "RIVER" -> "ISLAND"
                            "ISLAND" -> "CLEAR"
                            else -> "RIVER"
                        }
                        onDirectMove(row, col, next)
                    }
                    GameType.DOTS_AND_BOXES -> {
                        val localX = offset.x - col * (size.width / state.columns)
                        val localY = offset.y - row * (size.height / state.rows)
                        val width = size.width / state.columns
                        val height = size.height / state.rows
                        val side = listOf("left" to localX, "right" to width - localX, "top" to localY, "bottom" to height - localY).minBy { it.second }.first
                        onDirectMove(row, col, side)
                    }
                    GameType.SECRET_CODE -> if (secretCanGuess) onDirectMove(row, col, mapOf("action" to "GUESS")) else onCellSelected(row, col)
                    GameType.NEXUS_ZERO -> {
                        val first = selected
                        if (first == null || first == CellPosition(row, col)) onCellSelected(row, col)
                        else onDirectMove(first.row, first.column, mapOf("targetRow" to row, "targetCol" to col))
                    }
                    else -> onCellSelected(row, col)
                }
            }
        }
    }

    // Cachea geometría/colores fuera del DrawScope: evita parsear colores y crear
    // mapas por cada celda en cada frame de Timbiriche, Bridges y Slitherlink.
    val identityColors = remember(players) {
        players.mapValues { (_, player) -> parseGenericColor(player.colorHex) }
    }
    val validBridgeTargets = remember(state.board, selectedBridgeIsland) {
        selectedBridgeIsland?.let { bridgeTargets(state, it) }.orEmpty()
    }
    val completedBridgeIslands = remember(state.board, state.gameType) {
        if (state.gameType != GameType.BRIDGES) emptySet() else buildSet {
            state.board.forEachIndexed { row, cells ->
                cells.forEachIndexed { col, cell ->
                    if (cell.meta["island"] == true && bridgeSatisfied(state, row, col)) add("$row:$col")
                }
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
                val ownerColor = cell.ownerId?.let(identityColors::get)
                val bridgeValid = state.gameType == GameType.BRIDGES && "${row}:${col}" in validBridgeTargets
                val fill = when {
                    CellPosition(row, col) in illegalWater -> Color(0xFFFF1744).copy(alpha = warningAlpha)
                    state.gameType == GameType.NURIKABE && cell.value == "RIVER" -> Color(0xFF2196F3).copy(alpha = .78f)
                    state.gameType == GameType.NURIKABE && cell.value == "ISLAND" -> Color(0xFF66BB6A).copy(alpha = .72f)
                    bridgeValid -> Color(0xFF00C853).copy(alpha = .32f)
                    state.gameType == GameType.SECRET_CODE && cell.meta["revealedColor"] != null -> secretColor(cell.meta["revealedColor"].toString()).copy(alpha = .82f)
                    state.gameType == GameType.SECRET_CODE && secretKey.size == 25 -> secretColor(secretKey[row * 5 + col]).copy(alpha = .42f)
                    state.gameType == GameType.BRIDGES && selectedBridgeIsland == CellPosition(row, col) -> Color(0xFF00A8FF).copy(alpha = .28f)
                    cell.meta["given"] == true -> Color(0xFFFFD54F).copy(alpha = .62f)
                    selected == CellPosition(row, col) -> Color(0xFFFFF59D)
                    ownerColor != null -> ownerColor.copy(alpha = 0.25f)
                    cell.isBlocked -> Color(0xFF263238)
                    else -> Color.Transparent
                }
                drawRect(fill, origin, Size(cellWidth, cellHeight))
                val renderMeta = if (state.gameType == GameType.BRIDGES) {
                    cell.meta + mapOf(
                        "_row" to row,
                        "_col" to col,
                        "completed" to ("$row:$col" in completedBridgeIslands),
                    )
                } else cell.meta
                renderGenericCell(state.gameType, cell.value, renderMeta, cell.isBlocked, origin, cellWidth, cellHeight, textMeasurer)
                drawRect(Color(0xFFB0BEC5), origin, Size(cellWidth, cellHeight), style = Stroke(1.2f))
                if (state.gameType == GameType.CRYPTARITHM && cell.meta["given"] == true) {
                    drawRect(Color(0x6600E5FF), origin - Offset(2f, 2f), Size(cellWidth + 4f, cellHeight + 4f), style = Stroke(5f))
                    drawRect(Color(0xFF00A8FF), origin, Size(cellWidth, cellHeight), style = Stroke(2.5f))
                }
                if (ownerColor != null) drawRect(ownerColor, origin, Size(cellWidth, cellHeight), style = Stroke(2.4f))
            }
        }
    }
}

@Composable
private fun NonogramPuzzleGrid(
    state: GenericBoardState,
    players: Map<String, Player>,
    enabled: Boolean,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val rowClues = nestedIntClues(state.meta["rowClues"], state.rows)
    val columnClues = nestedIntClues(state.meta["columnClues"], state.columns)
    val leftUnits = (rowClues.maxOfOrNull(List<Int>::size) ?: 1).coerceAtLeast(1) * .72f + .35f
    val topUnits = (columnClues.maxOfOrNull(List<Int>::size) ?: 1).coerceAtLeast(1) * .72f + .35f
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio((state.columns + leftUnits) / (state.rows + topUnits))
            .background(Color(0xFFF8FAFF), RoundedCornerShape(10.dp))
            .pointerInput(state.board, enabled, leftUnits, topUnits) {
                detectTapGestures(
                    onTap = { offset ->
                        if (!enabled) return@detectTapGestures
                        val cell = min(size.width / (state.columns + leftUnits), size.height / (state.rows + topUnits))
                        val col = floor((offset.x - leftUnits * cell) / cell).toInt()
                        val row = floor((offset.y - topUnits * cell) / cell).toInt()
                        if (row in 0 until state.rows && col in 0 until state.columns) onDirectMove(row, col, "FILL")
                    },
                    onLongPress = { offset ->
                        if (!enabled) return@detectTapGestures
                        val cell = min(size.width / (state.columns + leftUnits), size.height / (state.rows + topUnits))
                        val col = floor((offset.x - leftUnits * cell) / cell).toInt()
                        val row = floor((offset.y - topUnits * cell) / cell).toInt()
                        if (row in 0 until state.rows && col in 0 until state.columns) onDirectMove(row, col, "EMPTY")
                    },
                )
            },
    ) {
        val cellSize = min(size.width / (state.columns + leftUnits), size.height / (state.rows + topUnits))
        val gridX = leftUnits * cellSize
        val gridY = topUnits * cellSize
        rowClues.forEachIndexed { row, clues ->
            clues.forEachIndexed { index, clue ->
                val center = Offset(gridX - (clues.size - index - .5f) * cellSize * .72f, gridY + (row + .5f) * cellSize)
                drawCenteredText(clue, center, cellSize, textMeasurer, Color(0xFF102A56))
            }
        }
        columnClues.forEachIndexed { col, clues ->
            clues.forEachIndexed { index, clue ->
                val center = Offset(gridX + (col + .5f) * cellSize, gridY - (clues.size - index - .5f) * cellSize * .72f)
                drawCenteredText(clue, center, cellSize, textMeasurer, Color(0xFF102A56))
            }
        }
        state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
            val origin = Offset(gridX + col * cellSize, gridY + row * cellSize)
            val ownerColor = cell.ownerId?.let { players[it]?.colorHex }?.let(::parseGenericColor)
            if (cell.value == true) drawRect((ownerColor ?: Color(0xFF243B6B)).copy(alpha = .88f), origin + Offset(2f, 2f), Size(cellSize - 4f, cellSize - 4f))
            if (cell.value == false) {
                drawLine(Color(0xFF78909C), origin + Offset(cellSize * .25f, cellSize * .25f), origin + Offset(cellSize * .75f, cellSize * .75f), 2.5f)
                drawLine(Color(0xFF78909C), origin + Offset(cellSize * .75f, cellSize * .25f), origin + Offset(cellSize * .25f, cellSize * .75f), 2.5f)
            }
            drawRect(Color(0xFF90A4AE), origin, Size(cellSize, cellSize), style = Stroke(if ((row + 1) % 5 == 0 || (col + 1) % 5 == 0) 2.2f else 1f))
        } }
        for (col in 5 until state.columns step 5) {
            val x = gridX + col * cellSize
            drawLine(Color(0xFF102A56), Offset(x, gridY), Offset(x, gridY + state.rows * cellSize), 3.5f)
        }
        for (row in 5 until state.rows step 5) {
            val y = gridY + row * cellSize
            drawLine(Color(0xFF102A56), Offset(gridX, y), Offset(gridX + state.columns * cellSize, y), 3.5f)
        }
        drawRect(Color(0xFF102A56), Offset(gridX, gridY), Size(state.columns * cellSize, state.rows * cellSize), style = Stroke(2.5f))
    }
}

private fun nestedIntClues(raw: Any?, expected: Int): List<List<Int>> {
    val outer = raw as? List<*> ?: return List(expected) { listOf(0) }
    return List(expected) { index ->
        (outer.getOrNull(index) as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.ifEmpty { listOf(0) } ?: listOf(0)
    }
}

@Composable
private fun PuzzleHints(state: GenericBoardState) {
    val instructions = state.meta["instructions"]?.toString()
    if (!instructions.isNullOrBlank()) Text(instructions, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    when (state.gameType) {
        GameType.WORD_SEARCH -> Text("Palabras: ${(state.meta["words"] as? List<*>)?.joinToString(" · ").orEmpty()}")
        GameType.CROSSWORD -> Column(Modifier.fillMaxWidth()) {
            (state.meta["clues"] as? List<*>)?.forEachIndexed { index, clue -> Text("${index + 1}. $clue", fontSize = 12.sp) }
        }
        GameType.NONOGRAM -> {
            Text("Filas: ${(state.meta["rowClues"] as? List<*>)?.joinToString(" | ").orEmpty()}", fontSize = 11.sp)
            Text("Columnas: ${(state.meta["columnClues"] as? List<*>)?.joinToString(" | ").orEmpty()}", fontSize = 11.sp)
        }
        GameType.MINESWEEPER -> Text("✦ Toca directamente una casilla para revelarla · ${state.meta["mineCount"] ?: "?"} minas")
        GameType.DOTS_AND_BOXES -> Text("Toca cerca del borde que quieres trazar.")
        GameType.HITORI -> Text("Toca el número duplicado que quieras apagar.")
        GameType.NURIKABE -> Text("Toca las casillas de río; conserva blancas las islas numeradas.")
        GameType.BRIDGES -> Text("Toca los segmentos entre islas para construir la red.")
        GameType.SLITHERLINK -> Text("Toca cerca de un borde para añadirlo al lazo.")
        GameType.CRYPTARITHM -> Text(state.meta["equation"]?.toString().orEmpty(), fontWeight = FontWeight.Black)
        GameType.NEXUS_ZERO -> Text("Toca una carga y después una vecina opuesta. Deben sumar exactamente cero.", fontWeight = FontWeight.Black)
        GameType.CROSS_LETTERS -> {
            val active = state.meta["activePlayerId"]?.toString()
            Text("Turno: ${active?.take(8) ?: "preparando…"} · selecciona la casilla inicial", fontWeight = FontWeight.Bold)
        }
        GameType.SECRET_CODE -> {
            val clue = state.meta["clue"] as? Map<*, *>
            Text("Turno del equipo ${state.meta["currentTeam"] ?: "ROJO"} · Pista: ${clue?.get("word") ?: "esperando"} ${clue?.get("remaining") ?: ""}", fontWeight = FontWeight.Black)
        }
        else -> Unit
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
            drawCenteredText(value ?: "?", center, width, textMeasurer, tileColor)
            meta["meldType"]?.toString()?.let { type ->
                val label = if (type == "RUN") "ESCALERA" else "GRUPO"
                val layout = textMeasurer.measure(label, TextStyle(color = Color(0xFF263238), fontSize = 6.sp, fontWeight = FontWeight.Bold))
                drawText(layout, topLeft = origin + Offset(2f, 1f))
            }
        }
        GameType.NURIKABE -> if (meta["islandClue"] == true) {
            drawCenteredText(meta["islandSize"], center, width, textMeasurer, Color.White)
        }
        GameType.BRIDGES -> if (meta["island"] == true) {
            val complete = meta["completed"] == true
            drawCircle(if (complete) Color(0xFFFFD54F) else Color(0xFFFFF4C2), min(width, height) * .34f, center)
            drawCircle(if (complete) Color(0xFFFF8F00) else Color(0xFF7A4E00), min(width, height) * .34f, center, style = Stroke(if (complete) 4f else 2f))
            drawCenteredText(meta["bridgeCount"], center, width, textMeasurer, if (complete) Color(0xFF5D3A00) else Color(0xFF4A3200))
        } else if (value == true) {
            if (((meta["_row"] as? Number)?.toInt() ?: 0) % 2 == 1) drawLine(Color(0xFF7A4E00), Offset(center.x, origin.y), Offset(center.x, origin.y + height), 5f)
            else drawLine(Color(0xFF7A4E00), Offset(origin.x, center.y), Offset(origin.x + width, center.y), 5f)
        }
        GameType.SLITHERLINK -> {
            val clue = (meta["clue"] as? Number)?.toInt() ?: -1
            val edgeCount = listOf("top", "right", "bottom", "left").count { meta[it] == true }
            if (clue >= 0) drawCenteredText(clue, center, width, textMeasurer, if (edgeCount == clue) Color(0xFF00A651) else Color(0xFF102A56))
            if (meta["top"] == true) drawLine(Color(0xFF7C3AED), origin, origin + Offset(width, 0f), 4f)
            if (meta["right"] == true) drawLine(Color(0xFF7C3AED), origin + Offset(width, 0f), origin + Offset(width, height), 4f)
            if (meta["bottom"] == true) drawLine(Color(0xFF7C3AED), origin + Offset(0f, height), origin + Offset(width, height), 4f)
            if (meta["left"] == true) drawLine(Color(0xFF7C3AED), origin, origin + Offset(0f, height), 4f)
        }
        GameType.CRYPTARITHM -> {
            val letter = meta["cryptLetter"]?.toString().orEmpty()
            drawCenteredText(if (value?.toString() == letter) letter else "$letter=$value", center, width, textMeasurer, Color(0xFF102A56))
        }
        GameType.NEXUS_ZERO -> {
            val charge = (value as? Number)?.toInt() ?: 0
            val color = if (charge >= 0) Color(0xFF00A8FF) else Color(0xFFE91E63)
            drawCircle(color.copy(alpha = .20f), min(width, height) * .39f, center)
            drawCircle(color, min(width, height) * .35f, center, style = Stroke(2.5f))
            drawCenteredText(if (charge > 0) "+$charge" else charge, center, width, textMeasurer, color)
        }
        GameType.CROSS_LETTERS -> {
            val bonus = meta["bonus"]?.toString().orEmpty()
            val bonusColor = when (bonus) { "TW" -> Color(0xFFE53935); "DW" -> Color(0xFFFF8A80); "TL" -> Color(0xFF1565C0); "DL" -> Color(0xFF81D4FA); else -> Color.Transparent }
            if (bonusColor != Color.Transparent && value == null) drawRect(bonusColor.copy(alpha = .38f), origin + Offset(1f, 1f), Size(width - 2f, height - 2f))
            if (value == null && bonus != "NONE") drawCenteredText(bonus, center, width * .8f, textMeasurer, Color(0xFF102A56))
            else drawCenteredText(value, center, width, textMeasurer, Color(0xFF102A56))
            if (meta["center"] == true && value == null) drawCircle(Color(0xFFFFC107), min(width, height) * .11f, center)
        }
        GameType.SECRET_CODE -> drawFittedCellText(value, center, width, textMeasurer, Color(0xFF102A56))
        GameType.HITORI -> {
            drawCenteredText(value, center, width, textMeasurer, if (isBlocked) Color.White else Color(0xFF102A56))
        }
        GameType.KAKURO -> if (meta["clueCell"] == true) {
            drawLine(Color.White.copy(alpha = .92f), origin, origin + Offset(width, height), 2f)
            (meta["rightSum"] as? Number)?.toInt()?.takeIf { it > 0 }?.let { sum ->
                val layout = textMeasurer.measure(sum.toString(), TextStyle(color = Color.White, fontSize = (width * .22f).coerceIn(8f, 15f).sp, fontWeight = FontWeight.Black))
                drawText(layout, topLeft = Offset(origin.x + width - layout.size.width - 3f, origin.y + 2f))
            }
            (meta["downSum"] as? Number)?.toInt()?.takeIf { it > 0 }?.let { sum ->
                val layout = textMeasurer.measure(sum.toString(), TextStyle(color = Color.White, fontSize = (width * .22f).coerceIn(8f, 15f).sp, fontWeight = FontWeight.Black))
                drawText(layout, topLeft = Offset(origin.x + 3f, origin.y + height - layout.size.height - 2f))
            }
        } else drawCenteredText(value, center, width, textMeasurer, Color(0xFF102A56))
        GameType.MATHDOKU -> {
            drawCenteredText(value, center, width, textMeasurer, Color(0xFF102A56))
            val label = meta["cageLabel"]?.toString().orEmpty()
            if (label.isNotEmpty()) {
                val layout = textMeasurer.measure(label, TextStyle(color = Color(0xFF7C3AED), fontSize = 9.sp, fontWeight = FontWeight.Black))
                drawText(layout, topLeft = origin + Offset(3f, 1f))
            }
            if (meta["cageStart"] == true) drawLine(Color(0xFF7C3AED), origin, Offset(origin.x, origin.y + height), 3f)
            if (meta["cageEnd"] == true) drawLine(Color(0xFF7C3AED), Offset(origin.x + width, origin.y), Offset(origin.x + width, origin.y + height), 3f)
        }
        GameType.CROSSWORD -> {
            drawCenteredText(value, center, width, textMeasurer, Color(0xFF102A56))
            val clue = (meta["clue"] as? Number)?.toInt() ?: 0
            if (clue > 0) {
                val layout = textMeasurer.measure(clue.toString(), TextStyle(color = Color(0xFF0057D9), fontSize = 8.sp, fontWeight = FontWeight.Bold))
                drawText(layout, topLeft = origin + Offset(2f, 1f))
            }
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFittedCellText(
    value: Any?, center: Offset, width: Float, textMeasurer: androidx.compose.ui.text.TextMeasurer, color: Color,
) {
    val original = value?.toString().orEmpty()
    if (original.isEmpty()) return
    var display = original
    var sizeSp = (width * .23f).coerceIn(7f, 15f)
    var layout = textMeasurer.measure(display, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Black))
    while (layout.size.width > width - 8f && sizeSp > 6f) {
        sizeSp -= 1f
        layout = textMeasurer.measure(display, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Black))
    }
    if (layout.size.width > width - 6f) {
        display = original.take(6) + "…"
        layout = textMeasurer.measure(display, TextStyle(color = color, fontSize = 6.sp, fontWeight = FontWeight.Black))
    }
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

@Composable
private fun SecretRoleHeader(state: ArenaUiState) {
    val captain = state.secretRole == "CAPTAIN"
    val teamColor = if (state.secretTeam == "RED") Color(0xFFE53935) else Color(0xFF1565C0)
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = teamColor.copy(alpha = .13f), border = androidx.compose.foundation.BorderStroke(2.dp, teamColor),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (state.secretRole) {
                    "CAPTAIN" -> "🕵️ ERES EL CAPITÁN"
                    "OPERATIVE" -> "👥 ERES OPERATIVO"
                    else -> "🔐 ASIGNANDO TU ROL…"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Black, color = teamColor,
            )
            Text(
                when (state.secretRole) {
                    "CAPTAIN" -> "Da una pista de una palabra y un número"
                    "OPERATIVE" -> "Adivina las palabras cuando llegue tu turno"
                    else -> "El servidor está preparando los equipos"
                },
                fontWeight = FontWeight.Bold, color = Color(0xFF263238),
            )
        }
    }
}

@Composable
private fun GenericMoveControls(
    state: ArenaUiState,
    enabled: Boolean,
    onMove: (Any?) -> Unit,
    onMoveAt: (Int, Int, Any?) -> Unit,
    onSecretChat: (String) -> Unit,
) {
    val gameType = state.gameType
    var text by remember(gameType) { mutableStateOf("") }
    when (gameType) {
        GameType.MINESWEEPER, GameType.NONOGRAM, GameType.HITORI, GameType.DOTS_AND_BOXES,
        GameType.NURIKABE, GameType.BRIDGES, GameType.SLITHERLINK, GameType.NEXUS_ZERO -> Unit
        GameType.WORD_SEARCH -> Text("Arrastra desde la primera hasta la última letra de una palabra.")
        GameType.CROSSWORD -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.uppercase().take(1) }, label = { Text("Letra") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(4.dp))
            Button({ onMove(text); text = "" }, enabled = enabled && text.isNotBlank()) { Text("Colocar") }
        }
        GameType.CROSS_LETTERS -> {
            var vertical by remember { mutableStateOf(false) }
            val normalized = text.uppercase().filter { it in "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ" }
            val available = state.letterRack.groupingBy { it }.eachCount().toMutableMap()
            val usesRack = normalized.all { letter ->
                val count = available[letter.toString()] ?: 0
                if (count <= 0) false else { available[letter.toString()] = count - 1; true }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.letterRack.forEach { letter ->
                        Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFFFFF4C2), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7A4E00))) {
                            Text(letter, Modifier.padding(horizontal = 9.dp, vertical = 7.dp), fontWeight = FontWeight.Black)
                        }
                    }
                }
                OutlinedTextField(normalized, { text = it.take(15) }, label = { Text("Palabra del atril") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ vertical = false }, modifier = Modifier.weight(1f)) { Text(if (!vertical) "✓ Horizontal" else "Horizontal") }
                    OutlinedButton({ vertical = true }, modifier = Modifier.weight(1f)) { Text(if (vertical) "✓ Vertical" else "Vertical") }
                    Button(
                        onClick = { onMove(mapOf("word" to normalized, "direction" to if (vertical) "V" else "H")); text = "" },
                        enabled = enabled && normalized.length >= 2 && usesRack,
                        modifier = Modifier.weight(1f),
                    ) { Text("Jugar") }
                }
            }
        }
        GameType.SECRET_CODE -> {
            var clue by remember { mutableStateOf("") }
            var count by remember { mutableStateOf("1") }
            var chat by remember { mutableStateOf("") }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Equipo ${state.secretTeam ?: "…"} · ${if (state.secretRole == "CAPTAIN") "Capitán" else "Operativo"}", fontWeight = FontWeight.Black)
                if (state.secretRole == "CAPTAIN") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedTextField(clue, { clue = it.uppercase().take(20) }, label = { Text("Pista") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(1) }, label = { Text("Nº") }, modifier = Modifier.weight(.38f))
                    Button({ onMove(mapOf("action" to "CLUE", "clue" to clue, "count" to (count.toIntOrNull() ?: 1))) }, enabled = enabled && clue.length >= 2) { Text("Dar") }
                }
                state.secretChat.forEach { Text("${state.players.firstOrNull { p -> p.id == it.playerId }?.name ?: "Jugador"}: ${it.message}", fontSize = 12.sp, color = if (it.penalized) Color.Red else Color(0xFF263238)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedTextField(chat, { chat = it.take(120) }, label = { Text("Chat de equipo") }, modifier = Modifier.weight(1f))
                    Button({ onSecretChat(chat); chat = "" }, enabled = chat.isNotBlank() && state.serverNowMs >= state.secretChatBlockedUntil) { Text("Enviar") }
                }
            }
        }
        GameType.CAPITAL_ARENA -> CapitalArenaControls(
            state = state,
            enabled = state.canInteractGeneric,
            onAction = { action -> onMoveAt(10, 10, action) },
        )
        GameType.RUMMIKUB -> {
            var tileColor by remember { mutableStateOf("RED") }
            val colors = listOf(
                "RED" to Color(0xFFE53935),
                "BLUE" to Color(0xFF1565C0),
                "GREEN" to Color(0xFF00875A),
                "ORANGE" to Color(0xFFFF8F00),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Elige color y número para completar la combinación", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEach { (id, color) ->
                        Surface(
                            modifier = Modifier.weight(1f).height(34.dp).clickable(enabled) { tileColor = id },
                            shape = RoundedCornerShape(9.dp),
                            color = color,
                            border = androidx.compose.foundation.BorderStroke(
                                if (tileColor == id) 3.dp else 1.dp,
                                if (tileColor == id) Color.White else color,
                            ),
                        ) {}
                    }
                }
                (1..13).chunked(7).forEach { numbers ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        numbers.forEach { number ->
                            Button(
                                onClick = { onMove(mapOf("tile" to number, "color" to tileColor)) },
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            ) { Text(number.toString(), maxLines = 1) }
                        }
                        repeat(7 - numbers.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        else -> {
            val values = when (gameType) {
                GameType.CRYPTARITHM -> 0..9
                GameType.MATHDOKU -> 1..7
                else -> 1..9
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                values.chunked(5).forEach { numbers ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        numbers.forEach { number -> Button({ onMove(number) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(number.toString()) } }
                        repeat(5 - numbers.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private fun secretColor(value: String): Color = when (value) {
    "RED" -> Color(0xFFE53935); "BLUE" -> Color(0xFF1E88E5); "ASSASSIN" -> Color(0xFF111827); else -> Color(0xFFB0BEC5)
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

private fun bridgeTargets(state: GenericBoardState, island: CellPosition): Set<String> =
    (state.board.getOrNull(island.row)?.getOrNull(island.column)?.meta?.get("validTargets") as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?.toSet()
        .orEmpty()

private fun bridgeSatisfied(state: GenericBoardState, row: Int, col: Int): Boolean {
    val required = (state.board[row][col].meta["bridgeCount"] as? Number)?.toInt() ?: return false
    if (required == 0) return true
    val built = listOf(row - 1 to col, row + 1 to col, row to col - 1, row to col + 1)
        .count { (y, x) -> state.board.getOrNull(y)?.getOrNull(x)?.value == true }
    return built >= required
}

private fun illegalWaterCells(state: GenericBoardState): Set<CellPosition> {
    val illegal = mutableSetOf<CellPosition>()
    for (row in 0 until state.rows - 1) for (col in 0 until state.columns - 1) {
        val block = listOf(CellPosition(row, col), CellPosition(row + 1, col), CellPosition(row, col + 1), CellPosition(row + 1, col + 1))
        if (block.all { state.board[it.row][it.column].value == "RIVER" }) illegal += block
    }
    return illegal
}

fun gameTitle(type: GameType): String = when (type) {
    GameType.SUDOKU -> "Multi Arena · Sudoku"; GameType.MINESWEEPER -> "Multi Arena · Buscaminas"; GameType.WORD_SEARCH -> "Multi Arena · Sopa de Letras"
    GameType.CROSSWORD -> "Multi Arena · Crucigramas"; GameType.NONOGRAM -> "Multi Arena · Nonogram"; GameType.DOTS_AND_BOXES -> "Multi Arena · Timbiriche"
    GameType.KAKURO -> "Multi Arena · Kakuro"; GameType.MATHDOKU -> "Multi Arena · Mathdoku"; GameType.HITORI -> "Multi Arena · Hitori"; GameType.RUMMIKUB -> "Multi Arena · Rummikub"
    GameType.NURIKABE -> "Multi Arena · Nurikabe"; GameType.BRIDGES -> "Multi Arena · Bridges"
    GameType.SLITHERLINK -> "Multi Arena · Slitherlink"; GameType.CRYPTARITHM -> "Multi Arena · Criptogramas"
    GameType.CROSS_LETTERS -> "Multi Arena · Letras Cruzadas"
    GameType.SECRET_CODE -> "Multi Arena · Código Secreto"
    GameType.CAPITAL_ARENA -> "Multi Arena · Capital Arena"
    GameType.NEXUS_ZERO -> "Multi Arena · Nexo Cero"
    GameType.ABYSS_ARENA -> "Multi Arena · Abismo Arena"
}

private fun formatGenericTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

private data class EdgeMove(val row: Int, val col: Int, val side: String)

private fun dotsEdge(start: CellPosition, end: CellPosition, rows: Int, columns: Int): EdgeMove? {
    val rowDelta = end.row - start.row
    val colDelta = end.column - start.column
    if (kotlin.math.abs(rowDelta) + kotlin.math.abs(colDelta) != 1) return null
    return if (rowDelta == 0) {
        val left = min(start.column, end.column)
        if (start.row < rows) EdgeMove(start.row, left.coerceAtMost(columns - 1), "top")
        else EdgeMove(rows - 1, left.coerceAtMost(columns - 1), "bottom")
    } else {
        val top = min(start.row, end.row)
        if (start.column < columns) EdgeMove(top.coerceAtMost(rows - 1), start.column, "left")
        else EdgeMove(top.coerceAtMost(rows - 1), columns - 1, "right")
    }
}
