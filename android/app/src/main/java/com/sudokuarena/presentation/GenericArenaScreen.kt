package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlin.math.sin
import kotlinx.coroutines.launch

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
    val screenFx = rememberGameFxController()
    var previousScore by remember(state.playerId, state.gameType) { mutableStateOf(state.ownPlayer?.score ?: 0) }
    var penaltyWasActive by remember(state.playerId, state.gameType) { mutableStateOf(false) }
    LaunchedEffect(state.ownPlayer?.score, generic?.revision) {
        val score = state.ownPlayer?.score ?: previousScore
        val gained = score - previousScore
        if (gained > 0) {
            screenFx.emit(
                if (gained >= 40) GameFxKind.COMBO else GameFxKind.SUCCESS,
                x = .5f, y = .38f, text = "+$gained", intensity = if (gained >= 40) 1.35f else .85f,
            )
        }
        previousScore = score
    }
    LaunchedEffect(state.penaltyRemainingMs) {
        val active = state.penaltyRemainingMs > 0
        if (active && !penaltyWasActive) screenFx.emit(GameFxKind.ERROR, text = "BLOQUEADO", intensity = 1.35f)
        penaltyWasActive = active
    }
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
            GameAtmosphere(state.gameType, Modifier.fillMaxSize())
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
                        val boardRatio = when (generic.gameType) {
                            GameType.HANGMAN -> 1.65f
                            GameType.ARROWS_ESCAPE -> 1.15f
                            else -> generic.columns.toFloat() / generic.rows.toFloat()
                        }
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
                                localPlayerId = state.playerId,
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
                    PuzzleProgressSummary(generic)
                    PuzzleHints(generic)
                    if (state.gameType == GameType.ARROWS_ESCAPE) ArrowRaceProgress(state)
                    // Algunos controles (teclado del Ahorcado, pistas y acciones
                    // globales) envían una coordenada propia y no necesitan que
                    // el usuario seleccione antes una casilla del tablero.
                    val controlsEnabled = if (state.gameType in setOf(
                            GameType.HANGMAN, GameType.HITORI, GameType.MERGE_2048,
                            GameType.TOWER_DEFENSE,
                        )
                    ) state.canInteractGeneric else state.canMakeGenericMove
                    GenericMoveControls(state, controlsEnabled, onMove, onMoveAt, onSecretChat)
                    if (!state.isSoloMode) {
                        ReactionMenu(onReaction)
                        GlobalGameChat(state, onGlobalChat)
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                }
                }
            )

            GameFxOverlay(screenFx, Modifier.fillMaxSize().zIndex(9f))

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
private fun PuzzleProgressSummary(state: GenericBoardState) {
    if (state.meta["actionMode"] == true || state.gameType in setOf(
            GameType.CAPITAL_ARENA, GameType.CHESS_TACTICS, GameType.CHECKERS,
            GameType.TIC_TAC_TOE, GameType.DOTS_AND_BOXES,
        )
    ) return
    val playable = state.board.flatten().filter { !it.isBlocked && it.meta["given"] != true }
    if (playable.isEmpty()) return
    val conquered = playable.count { it.ownerId != null }
    val progress by animateFloatAsState(
        targetValue = (conquered.toFloat() / playable.size).coerceIn(0f, 1f),
        animationSpec = tween(360),
        label = "puzzleProgress",
    )
    Surface(
        color = Color.White.copy(alpha = .94f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC7D2FE)),
    ) {
        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PROGRESO", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF312E81))
                Text("$conquered / ${playable.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color(0xFF6366F1),
                trackColor = Color(0xFFE2E8F0),
            )
        }
    }
}

@Composable
private fun ArrowRaceProgress(state: ArenaUiState) {
    val generic = state.genericBoard ?: return
    val total = (generic.meta["totalBlocks"] as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 1f
    val progress = generic.meta["progress"] as? Map<*, *> ?: emptyMap<Any, Any>()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("CARRERA DE ESCAPE", fontWeight = FontWeight.Black, fontSize = 11.sp)
        state.players.forEach { player ->
            val removed = (progress[player.id] as? Number)?.toFloat() ?: 0f
            Text("${player.name}: ${removed.toInt()}/${total.toInt()}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = { (removed / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = parseGenericColor(player.colorHex),
            )
        }
    }
}

@Composable
private fun AnimatedHangmanBoard(
    state: GenericBoardState,
    localPlayerId: String?,
    modifier: Modifier = Modifier,
) {
    val word = state.board.firstOrNull().orEmpty()
    val signature = word.joinToString("") { it.value?.toString() ?: "_" }
    val reveal = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(signature) {
        reveal.snapTo(.25f)
        reveal.animateTo(1f, spring(dampingRatio = .58f, stiffness = 420f))
    }
    val errors = state.meta["errors"] as? Map<*, *>
    val ownErrors = (errors?.get(localPlayerId) as? Number)?.toInt()
        ?: (state.meta["mistakesMade"] as? Number)?.toInt()
        ?: 0
    val animatedErrors by animateFloatAsState(
        targetValue = ownErrors.coerceIn(0, 6).toFloat(),
        animationSpec = tween(520),
        label = "hangmanBody",
    )
    val pulse by rememberInfiniteTransition(label = "hangmanRope").animateFloat(
        initialValue = .72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(780), RepeatMode.Reverse),
        label = "ropePulse",
    )
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFFF8FAFF),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF7C3AED).copy(alpha = .55f)),
        shadowElevation = 10.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val landscape = maxWidth > maxHeight * 1.25f
            val content: @Composable (Modifier, Modifier) -> Unit = { gallowsModifier, wordModifier ->
                HangmanGallows(animatedErrors, pulse, gallowsModifier)
                Column(
                    wordModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "VIDAS ${6 - ownErrors.coerceIn(0, 6)}",
                        color = if (ownErrors >= 5) Color(0xFFD50000) else Color(0xFF102A56),
                        fontWeight = FontWeight.Black,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    ) {
                        word.forEachIndexed { index, cell ->
                            val visible = cell.value != null
                            Surface(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .width(34.dp)
                                    .height(48.dp)
                                    .graphicsLayer {
                                        val delay = (index % 4) * .06f
                                        val progress = ((reveal.value - delay) / (1f - delay)).coerceIn(0f, 1f)
                                        scaleX = if (visible) progress else 1f
                                        scaleY = .88f + progress * .12f
                                        rotationY = if (visible) (1f - progress) * 90f else 0f
                                    },
                                shape = RoundedCornerShape(9.dp),
                                color = if (visible) Color(0xFFEDE9FE) else Color.White,
                                border = androidx.compose.foundation.BorderStroke(
                                    2.dp,
                                    if (visible) Color(0xFF7C3AED) else Color(0xFFB8C7E8),
                                ),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        cell.value?.toString() ?: "_",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF102A56),
                                    )
                                }
                            }
                        }
                    }
                    val used = (state.meta["guessedLetters"] as? List<*>)
                        ?.joinToString("  ") { it.toString() }.orEmpty()
                    if (used.isNotBlank()) {
                        Text("USADAS · $used", Modifier.padding(top = 10.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    val wrong = (state.meta["wrongGuesses"] as? List<*>)
                        ?.joinToString("  ") { it.toString() }.orEmpty()
                    if (wrong.isNotBlank()) {
                        Text("FALLOS · $wrong", color = Color(0xFFD50000), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    content(Modifier.weight(.42f).fillMaxHeight(), Modifier.weight(.58f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    content(Modifier.weight(.55f).fillMaxWidth(), Modifier.weight(.45f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun HangmanGallows(errors: Float, pulse: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.padding(8.dp)) {
        val ink = Color(0xFF102A56)
        val danger = Color(0xFFE53935)
        val stroke = maxOf(4f, size.minDimension * .025f)
        val baseY = size.height * .88f
        val poleX = size.width * .22f
        val beamY = size.height * .10f
        val bodyX = size.width * .64f
        drawLine(ink, Offset(size.width * .08f, baseY), Offset(size.width * .46f, baseY), stroke)
        drawLine(ink, Offset(poleX, baseY), Offset(poleX, beamY), stroke)
        drawLine(ink, Offset(poleX, beamY), Offset(bodyX, beamY), stroke)
        drawLine(
            danger.copy(alpha = pulse),
            Offset(bodyX, beamY),
            Offset(bodyX, size.height * .24f),
            stroke * .75f,
        )
        fun segment(index: Int): Float = (errors - index).coerceIn(0f, 1f)
        val headProgress = segment(0)
        if (headProgress > 0f) {
            drawCircle(
                danger.copy(alpha = headProgress),
                size.minDimension * .095f * headProgress,
                Offset(bodyX, size.height * .33f),
                style = Stroke(stroke),
            )
        }
        val neck = Offset(bodyX, size.height * .425f)
        val hip = Offset(bodyX, size.height * .64f)
        if (segment(1) > 0f) drawLine(danger, neck, Offset(bodyX, neck.y + (hip.y - neck.y) * segment(1)), stroke)
        if (segment(2) > 0f) drawLine(danger, Offset(bodyX, size.height * .48f), Offset(bodyX - size.width * .16f * segment(2), size.height * .57f), stroke)
        if (segment(3) > 0f) drawLine(danger, Offset(bodyX, size.height * .48f), Offset(bodyX + size.width * .16f * segment(3), size.height * .57f), stroke)
        if (segment(4) > 0f) drawLine(danger, hip, Offset(bodyX - size.width * .15f * segment(4), size.height * .80f), stroke)
        if (segment(5) > 0f) drawLine(danger, hip, Offset(bodyX + size.width * .15f * segment(5), size.height * .80f), stroke)
    }
}

private data class SerpentineRoute(
    val id: String,
    val points: List<Offset>,
    val direction: String,
    val exitVector: Offset,
    val thickness: Float,
    val blockType: String,
    val memberKeys: List<String>,
)

private fun parseSerpentineRoutes(state: GenericBoardState): List<SerpentineRoute> {
    if (state.meta["pathModel"] != "SERPENTINE_V2") return emptyList()
    return (state.meta["shapes"] as? List<*>)?.mapNotNull { raw ->
        val route = raw as? Map<*, *> ?: return@mapNotNull null
        val points = (route["points"] as? List<*>)?.mapNotNull { pointRaw ->
            val point = pointRaw as? Map<*, *> ?: return@mapNotNull null
            Offset(
                (point["x"] as? Number)?.toFloat() ?: return@mapNotNull null,
                (point["y"] as? Number)?.toFloat() ?: return@mapNotNull null,
            )
        }.orEmpty()
        if (points.size < 2) return@mapNotNull null
        val vector = route["exitVector"] as? Map<*, *>
        SerpentineRoute(
            id = route["id"]?.toString() ?: return@mapNotNull null,
            points = points,
            direction = route["direction"]?.toString() ?: "UP",
            exitVector = Offset(
                (vector?.get("x") as? Number)?.toFloat() ?: 0f,
                (vector?.get("y") as? Number)?.toFloat() ?: -1f,
            ),
            thickness = (route["thickness"] as? Number)?.toFloat() ?: .014f,
            blockType = route["blockType"]?.toString() ?: "NORMAL",
            memberKeys = (route["memberKeys"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
        )
    }.orEmpty()
}

private fun pointToRouteDistance(point: Offset, route: SerpentineRoute): Float =
    route.points.zipWithNext().minOfOrNull { (start, end) ->
        val delta = end - start
        val square = delta.x * delta.x + delta.y * delta.y
        if (square <= .000001f) (point - start).getDistance() else {
            val t = (((point - start).x * delta.x + (point - start).y * delta.y) / square).coerceIn(0f, 1f)
            (point - (start + delta * t)).getDistance()
        }
    } ?: Float.MAX_VALUE

private fun canSerpentineEscape(route: SerpentineRoute, routes: List<SerpentineRoute>, removed: Set<String>): Boolean {
    val head = route.points.last()
    val vector = route.exitVector
    val distance = listOfNotNull(
        if (vector.x > 0) (1.08f - head.x) / vector.x else null,
        if (vector.x < 0) (-.08f - head.x) / vector.x else null,
        if (vector.y > 0) (1.08f - head.y) / vector.y else null,
        if (vector.y < 0) (-.08f - head.y) / vector.y else null,
    ).filter { it > 0 }.minOrNull() ?: return false
    return routes.none { obstacle ->
        obstacle.id != route.id && !obstacle.memberKeys.all(removed::contains) && (1..36).any { step ->
            val sample = head + vector * (distance * step / 36f)
            pointToRouteDistance(sample, obstacle) <= route.thickness + obstacle.thickness + .008f
        }
    }
}

private fun routeLength(points: List<Offset>): Float = points.zipWithNext().sumOf { (a, b) ->
    (b - a).getDistance().toDouble()
}.toFloat()

private fun routePointAt(route: SerpentineRoute, distance: Float): Offset {
    var remaining = distance
    route.points.zipWithNext().forEach { (start, end) ->
        val length = (end - start).getDistance()
        if (remaining <= length) return start + (end - start) * (remaining / length.coerceAtLeast(.0001f))
        remaining -= length
    }
    return route.points.last() + route.exitVector * remaining
}

private fun shiftedRoute(route: SerpentineRoute, progress: Float): List<Offset> {
    val length = routeLength(route.points)
    val travel = progress * (length + 1.35f)
    return (0..24).map { step -> routePointAt(route, travel + length * step / 24f) }
}

@Composable
private fun SerpentineArrowsBoard(
    state: GenericBoardState,
    players: Map<String, Player>,
    enabled: Boolean,
    localPlayerId: String?,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val routes = remember(state.meta) { parseSerpentineRoutes(state) }
    val removed = remember(state.meta, localPlayerId) {
        ((state.meta["removedByPlayer"] as? Map<*, *>)?.get(localPlayerId) as? List<*>)
            ?.mapNotNull { it?.toString() }?.toSet().orEmpty()
    }
    var previousRemoved by remember(state.gameId, localPlayerId) { mutableStateOf(removed) }
    var exiting by remember(state.gameId, localPlayerId) { mutableStateOf(emptySet<String>()) }
    val flight = remember(state.gameId, localPlayerId) { androidx.compose.animation.core.Animatable(1f) }
    var blockedId by remember(state.gameId) { mutableStateOf<String?>(null) }
    val bump = remember(state.gameId) { androidx.compose.animation.core.Animatable(0f) }
    var action by remember(state.gameId) { mutableStateOf("ESCAPE") }
    var zoom by remember(state.gameId) { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(removed) {
        val fresh = removed - previousRemoved
        previousRemoved = removed
        if (fresh.isNotEmpty()) {
            exiting = routes.filter { route -> route.memberKeys.any(fresh::contains) }.map { it.id }.toSet()
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            flight.snapTo(0f)
            flight.animateTo(1f, tween(720, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            exiting = emptySet()
        }
    }
    val palette = remember(players) {
        players.values.map { parseGenericColor(it.colorHex) }.ifEmpty {
            listOf(Color(0xFF00B8D4), Color(0xFF7C3AED), Color(0xFFFF2D8D), Color(0xFFFF8A00))
        }
    }
    fun screenPoint(point: Offset): Offset = Offset(
        (point.x - .5f) * zoom + .5f,
        (point.y - .5f) * zoom + .5f,
    )
    Box(modifier.fillMaxWidth().aspectRatio(1.34f)) {
        Canvas(
            Modifier.fillMaxSize()
                .background(Color(0xFF07152F), RoundedCornerShape(18.dp))
                .pointerInput(routes, removed, enabled, action) {
                    detectTapGestures { tap ->
                        if (!enabled) return@detectTapGestures
                        val screen = Offset(tap.x / size.width, tap.y / size.height)
                        val normalized = Offset((screen.x - .5f) / zoom + .5f, (screen.y - .5f) / zoom + .5f)
                        val route = routes.filterNot { it.memberKeys.all(removed::contains) }
                            .minByOrNull { candidate -> minOf(pointToRouteDistance(normalized, candidate), (normalized - candidate.points.last()).getDistance() * .75f) }
                            ?.takeIf { candidate -> pointToRouteDistance(normalized, candidate) <= .055f || (normalized - candidate.points.last()).getDistance() <= .075f }
                            ?: return@detectTapGestures
                        val member = route.memberKeys.first().split(":").map(String::toInt)
                        if (action != "ESCAPE" || canSerpentineEscape(route, routes, removed)) {
                            onDirectMove(member[0], member[1], mapOf("action" to action))
                            action = "ESCAPE"
                        } else {
                            blockedId = route.id
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                bump.snapTo(0f); bump.animateTo(1f, tween(360)); blockedId = null
                            }
                        }
                    }
                },
        ) {
            val gridColor = Color(0xFF38BDF8).copy(alpha = .08f)
            repeat(12) { index ->
                val x = size.width * index / 12f; val y = size.height * index / 12f
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            routes.forEachIndexed { index, route ->
                val isRemoved = route.memberKeys.all(removed::contains)
                val isExiting = route.id in exiting
                if (isRemoved && !isExiting) return@forEachIndexed
                val normalizedPoints = if (isExiting) shiftedRoute(route, flight.value) else route.points
                val wiggle = if (blockedId == route.id) sin(bump.value * Math.PI.toFloat() * 8f) * .014f else 0f
                val points = normalizedPoints.map { raw ->
                    val point = screenPoint(Offset(raw.x + wiggle, raw.y))
                    Offset(point.x * size.width, point.y * size.height)
                }
                if (points.size < 2) return@forEachIndexed
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (pointIndex in 1 until points.lastIndex) {
                        val bend = points[pointIndex]
                        val next = points[pointIndex + 1]
                        val midpoint = (bend + next) * .5f
                        quadraticTo(bend.x, bend.y, midpoint.x, midpoint.y)
                    }
                    lineTo(points.last().x, points.last().y)
                }
                val color = palette[index % palette.size]
                val alpha = if (isExiting) 1f - flight.value * .75f else 1f
                val stroke = maxOf(7f, route.thickness * size.minDimension)
                drawPath(path, color.copy(alpha = .16f * alpha), style = Stroke(stroke * 3.1f))
                drawPath(path, color.copy(alpha = alpha), style = Stroke(stroke))
                drawPath(path, Color.White.copy(alpha = .58f * alpha), style = Stroke(maxOf(1.5f, stroke * .18f)))
                val head = points.last(); val before = points[points.lastIndex - 1]
                val tangent = (head - before).let { delta -> delta / delta.getDistance().coerceAtLeast(.001f) }
                val side = Offset(-tangent.y, tangent.x)
                val headLength = stroke * 2.2f
                val arrowHead = Path().apply {
                    moveTo(head.x, head.y)
                    lineTo(head.x - tangent.x * headLength + side.x * headLength * .62f, head.y - tangent.y * headLength + side.y * headLength * .62f)
                    lineTo(head.x - tangent.x * headLength - side.x * headLength * .62f, head.y - tangent.y * headLength - side.y * headLength * .62f)
                    close()
                }
                drawPath(arrowHead, color.copy(alpha = alpha))
                if (route.blockType == "BOMB") drawCircle(Color(0xFFFFD600), stroke * .8f, points.first())
            }
        }
        val rotateUsed = ((state.meta["rotateUses"] as? Map<*, *>)?.get(localPlayerId) as? Number)?.toInt() ?: 0
        val missileUsed = ((state.meta["missileUses"] as? Map<*, *>)?.get(localPlayerId) as? Number)?.toInt() ?: 0
        Row(
            Modifier.align(Alignment.BottomCenter).padding(8.dp).zIndex(4f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                { action = "ROTATE" }, enabled = enabled && rotateUsed < 2,
                colors = ButtonDefaults.buttonColors(containerColor = if (action == "ROTATE") Color(0xFFFF2D8D) else Color(0xFF6D28D9)),
            ) { Text("↻ Girar ${2 - rotateUsed}", fontWeight = FontWeight.Black, fontSize = 11.sp) }
            Button(
                { action = "MISSILE" }, enabled = enabled && missileUsed < 1,
                colors = ButtonDefaults.buttonColors(containerColor = if (action == "MISSILE") Color(0xFFFF6D00) else Color(0xFF075985)),
            ) { Text("➤ Misil ${1 - missileUsed}", fontWeight = FontWeight.Black, fontSize = 11.sp) }
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(10.dp).zIndex(5f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledTonalButton(onClick = { zoom = (zoom + .35f).coerceAtMost(2.8f) }, contentPadding = PaddingValues(8.dp)) { Text("＋", fontSize = 18.sp) }
            FilledTonalButton(onClick = { zoom = (zoom - .35f).coerceAtLeast(1f) }, contentPadding = PaddingValues(8.dp)) { Text("－", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun AnimatedArrowsGrid(
    state: GenericBoardState,
    players: Map<String, Player>,
    enabled: Boolean,
    localPlayerId: String?,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val serpentineRoutes = remember(state.meta) { parseSerpentineRoutes(state) }
    if (serpentineRoutes.isNotEmpty()) {
        SerpentineArrowsBoard(state, players, enabled, localPlayerId, onDirectMove, modifier)
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val spatialShapes = remember(state.meta) { parseSpatialArrowShapes(state) }
    if (spatialShapes.isEmpty()) {
        if (state.meta["freeSpace"] == true) ArrowGeometryLoading(modifier)
        else LegacyArrowGrid(state, enabled, onDirectMove, modifier)
        return
    }
    val removed = remember(state.meta, localPlayerId) {
        ((state.meta["removedByPlayer"] as? Map<*, *>)?.get(localPlayerId) as? List<*>)
            ?.mapNotNull { it?.toString() }?.toSet().orEmpty()
    }
    var previousRemoved by remember(state.gameId, localPlayerId) { mutableStateOf(removed) }
    var flying by remember(state.gameId, localPlayerId) { mutableStateOf(emptySet<String>()) }
    val flight = remember(state.gameId, localPlayerId) { androidx.compose.animation.core.Animatable(1f) }
    var blockedShapeId by remember(state.gameId) { mutableStateOf<String?>(null) }
    val shake = remember(state.gameId) { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var arrowAction by remember(state.gameId) { mutableStateOf("ESCAPE") }
    val blockedUntil = ((state.meta["blockedUntil"] as? Map<*, *>)?.get(localPlayerId) as? Number)?.toLong() ?: 0L
    var timerBlocked by remember(state.gameId, localPlayerId) { mutableStateOf(false) }
    LaunchedEffect(blockedUntil) {
        timerBlocked = blockedUntil > System.currentTimeMillis()
        if (timerBlocked) {
            kotlinx.coroutines.delay((blockedUntil - System.currentTimeMillis()).coerceAtLeast(0L))
            timerBlocked = false
        }
    }
    var orbitYaw by remember(state.gameId) { mutableStateOf(0f) }
    var orbitPitch by remember(state.gameId) { mutableStateOf(0f) }
    var cameraZoom by remember(state.gameId) { mutableStateOf(1f) }
    fun project(shape: SpatialArrowShape): Triple<Float, Float, Float> {
        val dx = shape.x + shape.width / 2f - .5f
        val dz = shape.z - .5f
        val rotatedX = dx * kotlin.math.cos(orbitYaw) + dz * kotlin.math.sin(orbitYaw)
        val rotatedZ = -dx * kotlin.math.sin(orbitYaw) + dz * kotlin.math.cos(orbitYaw)
        val dy = shape.y + shape.height / 2f - .5f
        val projectedY = dy * kotlin.math.cos(orbitPitch) - rotatedZ * kotlin.math.sin(orbitPitch)
        val depthScale = (1.08f - rotatedZ * .42f).coerceIn(.62f, 1.42f) * cameraZoom
        return Triple(.5f + rotatedX * cameraZoom, .5f + projectedY * cameraZoom, depthScale)
    }
    LaunchedEffect(removed) {
        val newlyRemoved = removed - previousRemoved
        previousRemoved = removed
        if (newlyRemoved.isNotEmpty()) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            flying = newlyRemoved
            flight.snapTo(0f)
            flight.animateTo(1f, tween(560, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            flying = emptySet()
        }
    }
    val colors = remember(players) { players.values.map { parseGenericColor(it.colorHex) } }
    Box(modifier.fillMaxWidth().aspectRatio(1.15f)) {
    Canvas(
        Modifier.fillMaxSize()
            .background(Color(0xFFF8FAFF), RoundedCornerShape(14.dp))
            .pointerInput(enabled, timerBlocked) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (!enabled || timerBlocked) return@detectTransformGestures
                    orbitYaw += pan.x / size.width * 3.2f
                    orbitPitch = (orbitPitch + pan.y / size.height * 2.2f).coerceIn(-1.05f, 1.05f)
                    cameraZoom = (cameraZoom * zoom).coerceIn(.65f, 2.2f)
                }
            }
            .pointerInput(spatialShapes, removed, enabled, timerBlocked) {
                detectTapGestures { offset ->
                    if (!enabled || timerBlocked) return@detectTapGestures
                    val worldX = offset.x / size.width; val worldY = offset.y / size.height
                    val shape = spatialShapes.sortedBy { it.z }.lastOrNull { candidate ->
                        val (x, y, scale) = project(candidate)
                        worldX in (x - candidate.width * scale / 2f)..(x + candidate.width * scale / 2f)
                            && worldY in (y - candidate.height * scale / 2f)..(y + candidate.height * scale / 2f)
                            && !candidate.memberKeys.all(removed::contains)
                    } ?: return@detectTapGestures
                    val member = shape.memberKeys.first().split(":").map(String::toInt)
                    if (canArrowEscapeClient(state, member[0], member[1], removed)) {
                        onDirectMove(member[0], member[1], mapOf("action" to arrowAction))
                        arrowAction = "ESCAPE"
                    } else {
                        if (arrowAction == "MISSILE" || arrowAction == "ROTATE") {
                            onDirectMove(member[0], member[1], mapOf("action" to arrowAction))
                            arrowAction = "ESCAPE"
                            return@detectTapGestures
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        blockedShapeId = shape.id
                        scope.launch {
                            shake.snapTo(0f)
                            shake.animateTo(1f, tween(340))
                            blockedShapeId = null
                        }
                    }
                }
            },
    ) {
        spatialShapes.forEach { shape ->
                val isRemoved = shape.memberKeys.all(removed::contains)
                val isFlying = shape.memberKeys.any(flying::contains)
                if (isRemoved && !isFlying) return@forEach
                val direction = shape.direction
                val worldVector = when (direction) {
                    "UP" -> Triple(0f, -1f, 0f)
                    "RIGHT" -> Triple(1f, 0f, 0f)
                    "DOWN" -> Triple(0f, 1f, 0f)
                    "LEFT" -> Triple(-1f, 0f, 0f)
                    "FRONT" -> Triple(0f, 0f, -1f)
                    else -> Triple(0f, 0f, 1f)
                }
                val screenX = worldVector.first * kotlin.math.cos(orbitYaw) + worldVector.third * kotlin.math.sin(orbitYaw)
                val screenDepth = -worldVector.first * kotlin.math.sin(orbitYaw) + worldVector.third * kotlin.math.cos(orbitYaw)
                val screenY = worldVector.second * kotlin.math.cos(orbitPitch) - screenDepth * kotlin.math.sin(orbitPitch)
                val vectorLength = kotlin.math.sqrt(screenX * screenX + screenY * screenY).coerceAtLeast(.08f)
                val vector = Offset(screenX / vectorLength, screenY / vectorLength)
                val escape = if (isFlying) flight.value else 0f
                val curveSign = if (shape.pathType == "CURVE_LEFT") -1f else if (shape.pathType == "CURVE_RIGHT") 1f else 0f
                val curve = curveSign * sin(escape * Math.PI.toFloat()) * size.minDimension * .11f
                val perpendicular = Offset(-vector.y, vector.x)
                val distance = max(size.width, size.height) * 1.15f * escape
                val blockedOffset = if (blockedShapeId == shape.id) {
                    sin(shake.value * Math.PI.toFloat() * 8f) * size.width * .012f
                } else 0f
                val (projectedX, projectedY, depthScale) = project(shape)
                val shapeWidth = shape.width * size.width * depthScale
                val shapeHeight = shape.height * size.height * depthScale
                val origin = Offset(
                    projectedX * size.width - shapeWidth / 2f + vector.x * distance + perpendicular.x * curve + blockedOffset,
                    projectedY * size.height - shapeHeight / 2f + vector.y * distance + perpendicular.y * curve,
                )
                val color = colors.getOrNull(kotlin.math.abs(shape.id.hashCode()) % maxOf(1, colors.size))
                    ?: listOf(Color(0xFF00A8FF), Color(0xFF7C3AED), Color(0xFFE91E63))[kotlin.math.abs(shape.id.hashCode()) % 3]
                val alpha = 1f - escape
                val maxOffsetX = shape.offsets.maxOfOrNull { it.first }?.coerceAtLeast(0) ?: 0
                val maxOffsetY = shape.offsets.maxOfOrNull { it.second }?.coerceAtLeast(0) ?: 0
                val segmentWidth = shapeWidth / (maxOffsetX + 1)
                val segmentHeight = shapeHeight / (maxOffsetY + 1)
                val extrusion = min(shapeWidth, shapeHeight) * (.10f + shape.depth * 1.6f)
                val topFace = Path().apply {
                    moveTo(origin.x, origin.y)
                    lineTo(origin.x + extrusion, origin.y - extrusion)
                    lineTo(origin.x + shapeWidth + extrusion, origin.y - extrusion)
                    lineTo(origin.x + shapeWidth, origin.y)
                    close()
                }
                val sideFace = Path().apply {
                    moveTo(origin.x + shapeWidth, origin.y)
                    lineTo(origin.x + shapeWidth + extrusion, origin.y - extrusion)
                    lineTo(origin.x + shapeWidth + extrusion, origin.y + shapeHeight - extrusion)
                    lineTo(origin.x + shapeWidth, origin.y + shapeHeight)
                    close()
                }
                drawPath(topFace, color.copy(alpha = .48f * alpha))
                drawPath(sideFace, Color.Black.copy(alpha = .24f * alpha))
                shape.offsets.forEach { (x, y) ->
                    val segmentOrigin = origin + Offset(x * segmentWidth, y * segmentHeight)
                    val inset = min(segmentWidth, segmentHeight) * .06f
                    drawRoundRect(
                        color.copy(alpha = .28f * alpha),
                        segmentOrigin + Offset(inset, inset),
                        Size(segmentWidth - inset * 2, segmentHeight - inset * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(inset * 1.8f),
                    )
                    drawRoundRect(
                        color.copy(alpha = alpha),
                        segmentOrigin + Offset(inset, inset),
                        Size(segmentWidth - inset * 2, segmentHeight - inset * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(inset * 1.8f),
                        style = Stroke(maxOf(2f, inset * .65f)),
                    )
                }
                drawCenteredText(
                    when (direction) {
                        "UP" -> "↑"; "RIGHT" -> "→"; "DOWN" -> "↓"; "LEFT" -> "←"
                        "FRONT" -> "⊙"; else -> "⊗"
                    },
                    origin + Offset(shapeWidth / 2f, shapeHeight / 2f),
                    max(shapeWidth, shapeHeight) * .72f,
                    textMeasurer,
                    color.copy(alpha = alpha),
                )
                if (shape.blockType == "BOMB") {
                    drawCenteredText("✹", origin + Offset(shapeWidth * .22f, shapeHeight * .25f), shapeWidth * .35f, textMeasurer, Color(0xFFFF3D00))
                } else if (shape.blockType == "BIDIRECTIONAL") {
                    drawCenteredText("↔", origin + Offset(shapeWidth * .22f, shapeHeight * .25f), shapeWidth * .35f, textMeasurer, Color(0xFF7C3AED))
                } else if (shape.blockType == "TIMER") {
                    drawCenteredText("⏱", origin + Offset(shapeWidth * .22f, shapeHeight * .25f), shapeWidth * .35f, textMeasurer, Color(0xFFD50000))
                }
        }
    }
    val rotateUsed = ((state.meta["rotateUses"] as? Map<*, *>)?.get(localPlayerId) as? Number)?.toInt() ?: 0
    val missileUsed = ((state.meta["missileUses"] as? Map<*, *>)?.get(localPlayerId) as? Number)?.toInt() ?: 0
    val combo = ((state.meta["combos"] as? Map<*, *>)?.get(localPlayerId) as? Number)?.toInt() ?: 1
    if (combo > 1) {
        Text(
            "COMBO ×$combo",
            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).zIndex(5f),
            color = Color(0xFFD500F9),
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
        )
    }
    if (timerBlocked) {
        Surface(
            modifier = Modifier.align(Alignment.Center).zIndex(8f),
            color = Color(0xEED50000),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                "⏱ BLOQUEO TEMPORAL",
                Modifier.padding(18.dp),
                color = Color.White,
                fontWeight = FontWeight.Black,
            )
        }
    }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(8.dp).zIndex(4f),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Button(
            { arrowAction = "ROTATE" },
            enabled = enabled && !timerBlocked && rotateUsed < 2,
            colors = ButtonDefaults.buttonColors(containerColor = if (arrowAction == "ROTATE") Color(0xFFEC4899) else Color(0xFF7C3AED)),
        ) { Text("↻ Rotar ${2 - rotateUsed}", fontWeight = FontWeight.Black, fontSize = 11.sp) }
        Button(
            { arrowAction = "MISSILE" },
            enabled = enabled && !timerBlocked && missileUsed < 1,
            colors = ButtonDefaults.buttonColors(containerColor = if (arrowAction == "MISSILE") Color(0xFFFF3D00) else Color(0xFF7C3AED)),
        ) { Text("➤ Misil ${1 - missileUsed}", fontWeight = FontWeight.Black, fontSize = 11.sp) }
    }
    }
}

@Composable
private fun ArrowGeometryLoading(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "arrowLoading").animateFloat(
        .35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "arrowLoadingPulse",
    )
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFFF8FAFF),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF00A8FF)),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("➜", fontSize = 52.sp, color = Color(0xFF7C3AED), modifier = Modifier.graphicsLayer {
                alpha = pulse
                scaleX = .82f + pulse * .18f
                scaleY = scaleX
            })
            Text("Calculando geometrías…", fontWeight = FontWeight.Black, color = Color(0xFF102A56))
            Text("La arena aparecerá en un instante", fontSize = 12.sp, color = Color(0xFF526581))
        }
    }
}

@Composable
private fun LegacyArrowGrid(
    state: GenericBoardState,
    enabled: Boolean,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier.fillMaxSize()
            .background(Color(0xFFF8FAFF), RoundedCornerShape(16.dp))
            .pointerInput(state.revision, enabled) {
                detectTapGestures { tap ->
                    if (!enabled || state.rows <= 0 || state.columns <= 0) return@detectTapGestures
                    val col = (tap.x / size.width * state.columns).toInt().coerceIn(0, state.columns - 1)
                    val row = (tap.y / size.height * state.rows).toInt().coerceIn(0, state.rows - 1)
                    if (state.board[row][col].ownerId == null) onDirectMove(row, col, "ESCAPE")
                }
            },
    ) {
        if (state.rows <= 0 || state.columns <= 0) return@Canvas
        val cellWidth = size.width / state.columns
        val cellHeight = size.height / state.rows
        state.board.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, cell ->
                if (cell.ownerId != null) return@forEachIndexed
                val origin = Offset(col * cellWidth, row * cellHeight)
                drawRoundRect(
                    Color(0xFF00A8FF).copy(alpha = .16f),
                    origin + Offset(3f, 3f),
                    Size(cellWidth - 6f, cellHeight - 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f),
                )
                drawRoundRect(
                    Color(0xFF7C3AED),
                    origin + Offset(3f, 3f),
                    Size(cellWidth - 6f, cellHeight - 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f),
                    style = Stroke(2.5f),
                )
                val glyph = when (cell.value?.toString()) {
                    "UP" -> "↑"; "RIGHT" -> "→"; "DOWN" -> "↓"; else -> "←"
                }
                drawCenteredText(
                    glyph,
                    origin + Offset(cellWidth / 2f, cellHeight / 2f),
                    min(cellWidth, cellHeight) * .72f,
                    textMeasurer,
                    Color(0xFF102A56),
                )
            }
        }
    }
}

private data class SpatialArrowShape(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val offsets: List<Pair<Int, Int>>,
    val direction: String,
    val pathType: String,
    val z: Float,
    val depth: Float,
    val blockType: String,
    val memberKeys: List<String>,
)

private fun parseSpatialArrowShapes(state: GenericBoardState): List<SpatialArrowShape> =
    (state.meta["shapes"] as? List<*>)?.mapNotNull { raw ->
        val shape = raw as? Map<*, *> ?: return@mapNotNull null
        SpatialArrowShape(
            id = shape["id"]?.toString() ?: return@mapNotNull null,
            x = (shape["x"] as? Number)?.toFloat() ?: return@mapNotNull null,
            y = (shape["y"] as? Number)?.toFloat() ?: return@mapNotNull null,
            width = (shape["width"] as? Number)?.toFloat() ?: return@mapNotNull null,
            height = (shape["height"] as? Number)?.toFloat() ?: return@mapNotNull null,
            offsets = (shape["offsets"] as? List<*>)?.mapNotNull { offsetRaw ->
                val offset = offsetRaw as? Map<*, *> ?: return@mapNotNull null
                ((offset["x"] as? Number)?.toInt() ?: 0) to ((offset["y"] as? Number)?.toInt() ?: 0)
            }.orEmpty(),
            direction = shape["direction"]?.toString().orEmpty(),
            pathType = shape["pathType"]?.toString() ?: "STRAIGHT",
            z = (shape["z"] as? Number)?.toFloat() ?: .5f,
            depth = (shape["depth"] as? Number)?.toFloat() ?: .06f,
            blockType = shape["blockType"]?.toString() ?: "NORMAL",
            memberKeys = (shape["memberKeys"] as? List<*>)?.map { it.toString() }.orEmpty(),
        )
    }.orEmpty()

private fun canArrowEscapeClient(
    state: GenericBoardState,
    row: Int,
    col: Int,
    removed: Set<String>,
): Boolean {
    if (state.meta["freeSpace"] == true) {
        val shapes = parseSpatialArrowShapes(state)
        val shapeId = state.board[row][col].meta["shapeId"]?.toString() ?: return false
        val shape = shapes.find { it.id == shapeId } ?: return false
        val obstacles = shapes.filter { candidate ->
            candidate.id != shape.id && !candidate.memberKeys.all(removed::contains)
        }
        val initiallyOverlapping = obstacles.filter { other ->
            shape.x < other.x + other.width && shape.x + shape.width > other.x &&
                shape.y < other.y + other.height && shape.y + shape.height > other.y &&
                shape.z < other.z + other.depth && shape.z + shape.depth > other.z
        }.mapTo(mutableSetOf()) { it.id }
        val vector = when (shape.direction) {
            "UP" -> Triple(0f, -1f, 0f)
            "RIGHT" -> Triple(1f, 0f, 0f)
            "DOWN" -> Triple(0f, 1f, 0f)
            "LEFT" -> Triple(-1f, 0f, 0f)
            "FRONT" -> Triple(0f, 0f, -1f)
            else -> Triple(0f, 0f, 1f)
        }
        val perpendicular = -vector.second to vector.first
        repeat(80) { index ->
            val progress = (index + 1) / 40f
            val sign = if (shape.pathType == "CURVE_LEFT") -1f else if (shape.pathType == "CURVE_RIGHT") 1f else 0f
            val curve = sign * sin(progress.coerceAtMost(1f) * Math.PI.toFloat()) * .11f
            val x = shape.x + vector.first * progress + perpendicular.first * curve
            val y = shape.y + vector.second * progress + perpendicular.second * curve
            val z = shape.z + vector.third * progress
            if (x + shape.width < 0 || x > 1 || y + shape.height < 0 || y > 1 ||
                z + shape.depth < 0 || z > 1
            ) return true
            if (obstacles.any { other ->
                    other.id !in initiallyOverlapping &&
                    x < other.x + other.width && x + shape.width > other.x
                        && y < other.y + other.height && y + shape.height > other.y
                        && z < other.z + other.depth && z + shape.depth > other.z
                }) return false
        }
        return false
    }
    val shapeId = state.board[row][col].meta["shapeId"]?.toString() ?: "$row:$col"
    val members = buildList {
        state.board.forEachIndexed { y, cells ->
            cells.forEachIndexed { x, cell ->
                if ((cell.meta["shapeId"]?.toString() ?: "$y:$x") == shapeId) add(CellPosition(y, x))
            }
        }
    }
    if (members.isEmpty()) return false
    val own = members.map { "${it.row}:${it.column}" }.toSet()
    val direction = state.board[members.first().row][members.first().column].meta["arrow"]?.toString()
        ?: state.board[members.first().row][members.first().column].value?.toString()
    val (dy, dx) = when (direction) {
        "UP" -> -1 to 0
        "RIGHT" -> 0 to 1
        "DOWN" -> 1 to 0
        else -> 0 to -1
    }
    return members.all { member ->
        var y = member.row + dy
        var x = member.column + dx
        while (y in 0 until state.rows && x in 0 until state.columns) {
            val key = "$y:$x"
            if (key !in own && key !in removed) return@all false
            y += dy
            x += dx
        }
        true
    }
}

private data class TowerVisualEnemy(
    val id: String,
    val kind: String,
    val hp: Float,
    val maxHp: Float,
    val progress: Float,
    val speed: Float,
    val spawnAt: Long,
    val slowUntil: Long,
    val status: String,
)

private data class TowerVisualProjectile(
    val id: String,
    val towerRow: Int,
    val towerCol: Int,
    val targetId: String,
    val color: Color,
    val damage: Float,
    val towerType: String,
    val firedAt: Long,
    val arrivesAt: Long,
)

private fun reactorGroup(state: GenericBoardState, startRow: Int, startCol: Int): Set<CellPosition> {
    val color = (state.board.getOrNull(startRow)?.getOrNull(startCol)?.value as? Number)?.toInt() ?: return emptySet()
    val queue = ArrayDeque<CellPosition>()
    val found = linkedSetOf<CellPosition>()
    queue.add(CellPosition(startRow, startCol))
    while (queue.isNotEmpty()) {
        val point = queue.removeFirst()
        if (point in found || (state.board.getOrNull(point.row)?.getOrNull(point.column)?.value as? Number)?.toInt() != color) continue
        found += point
        queue.add(CellPosition(point.row - 1, point.column)); queue.add(CellPosition(point.row + 1, point.column))
        queue.add(CellPosition(point.row, point.column - 1)); queue.add(CellPosition(point.row, point.column + 1))
    }
    return found
}

private fun findReactorHint(state: GenericBoardState): Set<CellPosition> {
    state.board.forEachIndexed { row, cells -> cells.indices.forEach { col ->
        val group = reactorGroup(state, row, col)
        if (group.size >= 3) return group
    } }
    return emptySet()
}

@Composable
private fun ReactorChainBoard(
    state: GenericBoardState,
    enabled: Boolean,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fx = rememberGameFxController()
    val fall = remember(state.gameId) { androidx.compose.animation.core.Animatable(1f) }
    var previousValues by remember(state.gameId) { mutableStateOf(state.board.map { row -> row.map { it.value } }) }
    var explosionValues by remember(state.gameId) { mutableStateOf<List<List<Any?>>>(emptyList()) }
    var explosionCells by remember(state.gameId) { mutableStateOf(emptySet<CellPosition>()) }
    var lastChainAt by remember(state.gameId) { mutableStateOf(0L) }
    var lastTouchAt by remember(state.gameId) { mutableStateOf(System.currentTimeMillis()) }
    var hintCells by remember(state.gameId) { mutableStateOf(emptySet<CellPosition>()) }
    var sparkleSeed by remember(state.gameId) { mutableStateOf(0) }
    val shimmer by rememberInfiniteTransition(label = "reactorGlass").animateFloat(
        0f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "reactorShimmer",
    )
    LaunchedEffect(state.revision) {
        val chain = state.meta["lastChain"] as? Map<*, *>
        val at = (chain?.get("at") as? Number)?.toLong() ?: 0L
        val current = state.board.map { row -> row.map { it.value } }
        if (at > 0 && at != lastChainAt) {
            lastChainAt = at
            explosionValues = previousValues
            explosionCells = (chain?.get("cells") as? List<*>)?.mapNotNull { raw ->
                val point = raw as? Map<*, *> ?: return@mapNotNull null
                CellPosition((point["row"] as? Number)?.toInt() ?: return@mapNotNull null, (point["col"] as? Number)?.toInt() ?: return@mapNotNull null)
            }?.toSet().orEmpty()
            val center = explosionCells.firstOrNull()
            fx.emit(
                GameFxKind.EXPLOSION,
                x = ((center?.column ?: state.columns / 2) + .5f) / state.columns,
                y = ((center?.row ?: state.rows / 2) + .5f) / state.rows,
                text = "CADENA ×${chain?.get("size") ?: explosionCells.size}",
                intensity = 1.35f,
            )
            previousValues = current
            fall.snapTo(0f)
            fall.animateTo(1f, tween(620, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            explosionCells = emptySet(); explosionValues = emptyList()
        } else previousValues = current
    }
    LaunchedEffect(state.gameId) {
        while (true) { kotlinx.coroutines.delay(2_000); sparkleSeed += 1 }
    }
    LaunchedEffect(state.revision, lastTouchAt) {
        kotlinx.coroutines.delay(5_000)
        if (System.currentTimeMillis() - lastTouchAt >= 4_900) hintCells = findReactorHint(state)
    }
    val colors = listOf(
        Color(0xFF00C2FF), Color(0xFFFF2D95), Color(0xFF7C3AED),
        Color(0xFF00C853), Color(0xFFFFB300), Color(0xFFFF5A36),
    )
    GameFxHost(fx, modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().background(Color(0xFF07152F), RoundedCornerShape(18.dp))
                .pointerInput(state.revision, enabled) {
                    detectTapGestures { tap ->
                        if (!enabled || state.rows <= 0 || state.columns <= 0) return@detectTapGestures
                        val col = (tap.x / size.width * state.columns).toInt().coerceIn(0, state.columns - 1)
                        val row = (tap.y / size.height * state.rows).toInt().coerceIn(0, state.rows - 1)
                        lastTouchAt = System.currentTimeMillis(); hintCells = emptySet()
                        onDirectMove(row, col, "CHAIN")
                    }
                },
        ) {
            val cw = size.width / state.columns; val ch = size.height / state.rows
            drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF17366A), Color(0xFF050A1D)), center, size.maxDimension * .75f))
            val removedPerColumn = IntArray(state.columns) { column -> explosionCells.count { it.column == column } }
            state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
                val value = (cell.value as? Number)?.toInt() ?: return@forEachIndexed
                val offsetY = -(1f - fall.value) * removedPerColumn[col] * ch
                val sphereCenter = Offset((col + .5f) * cw, (row + .5f) * ch + offsetY)
                val radius = min(cw, ch) * .38f
                val color = colors[(value - 1).mod(colors.size)]
                if (CellPosition(row, col) in hintCells) {
                    drawCircle(Color.White.copy(alpha = .14f + shimmer * .22f), radius * (1.15f + shimmer * .13f), sphereCenter)
                    drawCircle(Color(0xFFFFD54F), radius * 1.12f, sphereCenter, style = Stroke(2.5f + shimmer * 2f))
                }
                drawCircle(Color.Black.copy(alpha = .42f), radius * 1.05f, sphereCenter + Offset(radius * .12f, radius * .16f))
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(Color.White.copy(alpha = .88f), color, color.copy(alpha = .68f), Color.Black.copy(alpha = .7f)),
                        center = sphereCenter - Offset(radius * .27f, radius * .3f), radius = radius * 1.45f,
                    ), radius, sphereCenter,
                )
                drawCircle(Color.White.copy(alpha = .28f), radius, sphereCenter, style = Stroke(maxOf(1.5f, radius * .07f)))
                if ((row * 31 + col * 17 + sparkleSeed * 13).mod(maxOf(1, state.rows * state.columns)) % 7 == 0) {
                    val glint = sphereCenter - Offset(radius * .33f, radius * .36f)
                    drawLine(Color.White.copy(alpha = shimmer), glint - Offset(radius * .18f, 0f), glint + Offset(radius * .18f, 0f), 2f)
                    drawLine(Color.White.copy(alpha = shimmer), glint - Offset(0f, radius * .18f), glint + Offset(0f, radius * .18f), 2f)
                }
            } }
            if (explosionCells.isNotEmpty() && explosionValues.isNotEmpty()) {
                explosionCells.forEach { point ->
                    val value = (explosionValues.getOrNull(point.row)?.getOrNull(point.column) as? Number)?.toInt() ?: return@forEach
                    val center = Offset((point.column + .5f) * cw, (point.row + .5f) * ch)
                    val color = colors[(value - 1).mod(colors.size)]
                    val radius = min(cw, ch) * .38f * (1f - fall.value)
                    drawCircle(color.copy(alpha = 1f - fall.value), radius, center)
                    repeat(8) { shard ->
                        val angle = shard * Math.PI.toFloat() / 4f
                        val direction = Offset(kotlin.math.cos(angle), kotlin.math.sin(angle))
                        drawLine(color, center + direction * radius, center + direction * (radius + fall.value * min(cw, ch) * .42f), maxOf(1f, radius * .1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TowerDefenseGrid(
    state: GenericBoardState,
    players: Map<String, Player>,
    selected: CellPosition?,
    enabled: Boolean,
    onCellSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "towerArena").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "towerPulse",
    )
    val receivedAt = remember(state.revision) { System.currentTimeMillis() }
    val path = remember(state.meta) {
        (state.meta["path"] as? List<*>)?.mapNotNull { raw ->
            val point = raw as? Map<*, *> ?: return@mapNotNull null
            val row = (point["row"] as? Number)?.toFloat() ?: return@mapNotNull null
            val col = (point["col"] as? Number)?.toFloat() ?: return@mapNotNull null
            row to col
        }.orEmpty()
    }
    val enemies = (state.meta["enemies"] as? List<*>)?.mapNotNull { raw ->
        val enemy = raw as? Map<*, *> ?: return@mapNotNull null
        TowerVisualEnemy(
            id = enemy["id"]?.toString() ?: return@mapNotNull null,
            kind = enemy["kind"]?.toString() ?: "SCOUT",
            hp = (enemy["hp"] as? Number)?.toFloat() ?: 0f,
            maxHp = (enemy["maxHp"] as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 1f,
            progress = (enemy["progress"] as? Number)?.toFloat() ?: 0f,
            speed = (enemy["speed"] as? Number)?.toFloat() ?: 0f,
            spawnAt = (enemy["spawnAt"] as? Number)?.toLong() ?: 0L,
            slowUntil = (enemy["slowUntil"] as? Number)?.toLong() ?: 0L,
            status = enemy["status"]?.toString() ?: "WAITING",
        )
    }.orEmpty()
    val projectiles = (state.meta["projectiles"] as? List<*>)?.mapNotNull { raw ->
        val projectile = raw as? Map<*, *> ?: return@mapNotNull null
        TowerVisualProjectile(
            id = projectile["id"]?.toString() ?: return@mapNotNull null,
            towerRow = (projectile["towerRow"] as? Number)?.toInt() ?: return@mapNotNull null,
            towerCol = (projectile["towerCol"] as? Number)?.toInt() ?: return@mapNotNull null,
            targetId = projectile["targetId"]?.toString() ?: return@mapNotNull null,
            color = parseGenericColor(projectile["color"]?.toString() ?: "#60A5FA"),
            damage = (projectile["damage"] as? Number)?.toFloat() ?: 0f,
            towerType = projectile["towerType"]?.toString() ?: "RAPID",
            firedAt = (projectile["firedAt"] as? Number)?.toLong() ?: 0L,
            arrivesAt = (projectile["arrivesAt"] as? Number)?.toLong() ?: 0L,
        )
    }.orEmpty()
    val ownerColors = remember(players) { players.mapValues { parseGenericColor(it.value.colorHex) } }
    val fx = rememberGameFxController()
    val seenProjectiles = remember(state.gameId) { mutableSetOf<String>() }
    val previousEnemyStates = remember(state.gameId) { mutableMapOf<String, String>() }
    val previousTowers = remember(state.gameId) { mutableSetOf<String>() }
    var previousWaveActive by remember(state.gameId) { mutableStateOf(false) }
    var previousBaseHealth by remember(state.gameId) { mutableStateOf((state.meta["baseHealth"] as? Number)?.toInt() ?: 20) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.revision) {
        fun normalized(progress: Float): Pair<Float, Float> {
            if (path.isEmpty()) return .5f to .5f
            val start = progress.toInt().coerceIn(0, path.lastIndex)
            val end = (start + 1).coerceAtMost(path.lastIndex)
            val fraction = (progress - start).coerceIn(0f, 1f)
            val a = path[start]; val b = path[end]
            val row = a.first + (b.first - a.first) * fraction
            val col = a.second + (b.second - a.second) * fraction
            return ((col + .5f) / state.columns) to ((row + .5f) / state.rows)
        }
        val activeWave = state.meta["waveActive"] == true
        if (activeWave && !previousWaveActive) {
            fx.emit(GameFxKind.WAVE, text = "OLEADA ${state.meta["wave"] ?: 1}", intensity = 1.4f)
        }
        previousWaveActive = activeWave
        val baseHealth = (state.meta["baseHealth"] as? Number)?.toInt() ?: previousBaseHealth
        if (baseHealth < previousBaseHealth) {
            val base = path.lastOrNull()
            fx.emit(
                GameFxKind.ERROR,
                x = ((base?.second ?: state.columns / 2f) + .5f) / state.columns,
                y = ((base?.first ?: state.rows / 2f) + .5f) / state.rows,
                text = "-${previousBaseHealth - baseHealth} NÚCLEO",
                intensity = 1.45f,
            )
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        previousBaseHealth = baseHealth
        state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
            if (cell.meta["towerType"] != null && previousTowers.add("$row:$col")) {
                fx.emit(GameFxKind.MAGIC, (col + .5f) / state.columns, (row + .5f) / state.rows, text = "TORRE LISTA")
            }
        } }
        enemies.forEach { enemy ->
            val old = previousEnemyStates.put(enemy.id, enemy.status)
            if (old != null && old != enemy.status && enemy.status == "DEFEATED") {
                val (x, y) = normalized(enemy.progress)
                fx.emit(GameFxKind.EXPLOSION, x, y, text = "+${(enemy.maxHp / 6f).toInt().coerceAtLeast(5)}", intensity = if (enemy.kind == "GOLEM") 1.65f else 1f)
            }
        }
        projectiles.filter { seenProjectiles.add(it.id) }.forEach { projectile ->
            val target = enemies.firstOrNull { it.id == projectile.targetId } ?: return@forEach
            val (x, y) = normalized(target.progress)
            val kind = if (projectile.color == Color(0xFF22D3EE)) GameFxKind.FROST else GameFxKind.IMPACT
            fx.emit(
                kind, x, y,
                text = if (projectile.damage > 0f) "-${projectile.damage.toInt()}" else null,
                color = projectile.color,
                intensity = if (projectile.towerType == "BLAST") .9f else .62f,
                durationMs = 440,
                shake = false,
            )
        }
        if (seenProjectiles.size > 300) seenProjectiles.retainAll(projectiles.mapTo(mutableSetOf()) { it.id })
    }
    GameFxHost(fx, modifier.fillMaxSize()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF071426), RoundedCornerShape(18.dp))
            .pointerInput(state.revision, enabled) {
                detectTapGestures { tap ->
                    if (!enabled || state.rows <= 0 || state.columns <= 0) return@detectTapGestures
                    val tileWidth = size.width * 1.72f / (state.columns + state.rows)
                    val tileHeight = tileWidth * .54f
                    val originX = size.width / 2f + (state.rows - state.columns) * tileWidth * .04f
                    val originY = size.height * .13f
                    val deltaColumnMinusRow = (tap.x - originX) / (tileWidth / 2f)
                    val deltaColumnPlusRow = (tap.y - originY) / (tileHeight / 2f)
                    val col = ((deltaColumnMinusRow + deltaColumnPlusRow) / 2f).roundToInt()
                    val row = ((deltaColumnPlusRow - deltaColumnMinusRow) / 2f).roundToInt()
                    if (row !in 0 until state.rows || col !in 0 until state.columns) return@detectTapGestures
                    if (state.board[row][col].meta["path"] != true) onCellSelected(row, col)
                }
            },
    ) {
        if (state.rows <= 0 || state.columns <= 0) return@Canvas
        val cw = size.width * 1.72f / (state.columns + state.rows)
        val ch = cw * .54f
        val now = state.serverTime + (System.currentTimeMillis() - receivedAt)
        val isoOrigin = Offset(size.width / 2f + (state.rows - state.columns) * cw * .04f, size.height * .13f)
        fun point(row: Float, col: Float) = Offset(
            isoOrigin.x + (col - row) * cw / 2f,
            isoOrigin.y + (col + row) * ch / 2f,
        )
        fun position(progress: Float): Offset {
            if (path.isEmpty()) return Offset.Zero
            val start = progress.toInt().coerceIn(0, path.lastIndex)
            val end = (start + 1).coerceAtMost(path.lastIndex)
            val fraction = (progress - start).coerceIn(0f, 1f)
            val a = path[start]; val b = path[end]
            return point(a.first + (b.first - a.first) * fraction, a.second + (b.second - a.second) * fraction)
        }
        drawRect(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0xFF183A61), Color(0xFF071426)),
                center = center,
                radius = size.maxDimension * .72f,
            ),
        )
        repeat(24) { index ->
            val x = ((index * 47 % 101) / 100f * size.width + pulse * (6f + index % 5)).mod(size.width)
            val y = (index * 71 % 97) / 96f * size.height
            drawCircle(
                if (index % 3 == 0) Color(0xFF22D3EE).copy(alpha = .24f) else Color.White.copy(alpha = .10f),
                1.2f + index % 3,
                Offset(x, y),
            )
        }
        // Terreno isométrico 2.5D: cada nodo es un rombo con una cara lateral
        // sombreada, manteniendo la selección lógica en la matriz autoritativa.
        for (row in 0 until state.rows) for (col in 0 until state.columns) {
            val tileCenter = point(row.toFloat(), col.toFloat())
            val terrain = state.board[row][col].meta["terrain"] as? Number
            val topColor = when (terrain?.toInt()?.mod(3)) {
                1 -> Color(0xFF123B48); 2 -> Color(0xFF24345C); else -> Color(0xFF15314A)
            }
            val top = Path().apply {
                moveTo(tileCenter.x, tileCenter.y - ch / 2f); lineTo(tileCenter.x + cw / 2f, tileCenter.y)
                lineTo(tileCenter.x, tileCenter.y + ch / 2f); lineTo(tileCenter.x - cw / 2f, tileCenter.y); close()
            }
            val side = Path().apply {
                moveTo(tileCenter.x - cw / 2f, tileCenter.y); lineTo(tileCenter.x, tileCenter.y + ch / 2f)
                lineTo(tileCenter.x, tileCenter.y + ch * .72f); lineTo(tileCenter.x - cw / 2f, tileCenter.y + ch * .22f); close()
            }
            drawPath(side, Color(0xFF07111F))
            drawPath(top, topColor)
            drawPath(top, Color(0xFF38BDF8).copy(alpha = .13f), style = Stroke(1.2f))
        }
        if (path.size >= 2) {
            path.zipWithNext().forEach { (a, b) ->
                val start = point(a.first, a.second); val end = point(b.first, b.second)
                drawLine(Color(0xFF22D3EE).copy(alpha = .16f), start, end, min(cw, ch) * .74f)
                drawLine(Color(0xFF27364F), start, end, min(cw, ch) * .58f)
                drawLine(
                    Color(0xFF94A3B8).copy(alpha = .62f), start, end, maxOf(2f, min(cw, ch) * .035f),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 10f), pulse * 18f),
                )
            }
            drawCircle(Color(0xFF22C55E).copy(alpha = .24f + pulse * .18f), min(cw, ch) * .42f, point(path.first().first, path.first().second))
            drawCircle(Color(0xFFFACC15).copy(alpha = .28f + pulse * .2f), min(cw, ch) * .46f, point(path.last().first, path.last().second))
            val gate = point(path.first().first, path.first().second)
            drawLine(Color(0xFF4ADE80), gate + Offset(-cw * .28f, -ch * .34f), gate + Offset(-cw * .28f, ch * .34f), maxOf(3f, cw * .05f))
            drawLine(Color(0xFF4ADE80), gate + Offset(cw * .28f, -ch * .34f), gate + Offset(cw * .28f, ch * .34f), maxOf(3f, cw * .05f))
            drawLine(Color.White.copy(alpha = .65f), gate + Offset(-cw * .28f, -ch * .34f), gate + Offset(cw * .28f, -ch * .34f), maxOf(2f, cw * .035f))
            val core = point(path.last().first, path.last().second)
            val corePath = Path().apply {
                moveTo(core.x, core.y - ch * .38f); lineTo(core.x + cw * .34f, core.y)
                lineTo(core.x, core.y + ch * .38f); lineTo(core.x - cw * .34f, core.y); close()
            }
            drawPath(corePath, Color(0xFFFACC15).copy(alpha = .75f))
            drawCircle(Color.White.copy(alpha = .85f), min(cw, ch) * (.09f + pulse * .035f), core)
        }
        state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
            if (cell.meta["path"] == true) return@forEachIndexed
            val towerType = cell.meta["towerType"]?.toString()
            val towerCenter = point(row.toFloat(), col.toFloat())
            if (selected?.row == row && selected.column == col) {
                val range = when (towerType) { "SNIPER" -> 6.4f; "BLAST" -> 3.2f; "FROST" -> 3.7f; else -> 3.4f }
                if (towerType != null) drawCircle(
                    towerColorFor(towerType).copy(alpha = .14f),
                    min(cw, ch) * range,
                    towerCenter,
                    style = Stroke(maxOf(1.5f, cw * .025f)),
                )
                drawCircle(Color(0xFFFFD54F).copy(alpha = .22f), min(cw, ch) * .47f, towerCenter)
                drawCircle(Color(0xFFFFD54F), min(cw, ch) * .39f, towerCenter, style = Stroke(maxOf(2f, cw * .035f)))
            }
            if (towerType == null) {
                val padColor = Color(0xFF34D399)
                drawCircle(padColor.copy(alpha = .06f), min(cw, ch) * .34f, towerCenter)
                drawCircle(padColor.copy(alpha = .28f), min(cw, ch) * .27f, towerCenter, style = Stroke(maxOf(1f, cw * .025f)))
                return@forEachIndexed
            }
            val towerColor = when (towerType) {
                "BLAST" -> Color(0xFFFB923C); "SNIPER" -> Color(0xFFC084FC)
                "FROST" -> Color(0xFF22D3EE); else -> Color(0xFF60A5FA)
            }
            val ownerColor = cell.ownerId?.let(ownerColors::get) ?: towerColor
            val radius = min(cw, ch) * .26f
            drawCircle(Color.Black.copy(alpha = .4f), radius * 1.18f, towerCenter + Offset(0f, radius * .22f))
            drawCircle(ownerColor.copy(alpha = .24f + pulse * .08f), radius * 1.45f, towerCenter)
            drawCircle(Color(0xFF172033), radius, towerCenter)
            drawCircle(towerColor, radius * .72f, towerCenter)
            val angle = pulse * Math.PI.toFloat() * 2f + (row + col) * .7f
            val direction = Offset(kotlin.math.cos(angle), kotlin.math.sin(angle))
            val perpendicular = Offset(-direction.y, direction.x)
            when (towerType) {
                "BLAST" -> {
                    drawLine(Color.White.copy(alpha = .90f), towerCenter + perpendicular * radius * .24f, towerCenter + direction * radius * 1.18f + perpendicular * radius * .24f, maxOf(3f, radius * .24f))
                    drawLine(Color.White.copy(alpha = .90f), towerCenter - perpendicular * radius * .24f, towerCenter + direction * radius * 1.18f - perpendicular * radius * .24f, maxOf(3f, radius * .24f))
                }
                "SNIPER" -> {
                    drawLine(Color(0xFFE9D5FF), towerCenter, towerCenter + direction * radius * 1.65f, maxOf(2f, radius * .20f))
                    drawCircle(Color.White, radius * .18f, towerCenter + direction * radius * .45f)
                }
                "FROST" -> {
                    repeat(4) { crystal ->
                        val crystalAngle = angle + crystal * Math.PI.toFloat() / 2f
                        val tip = towerCenter + Offset(kotlin.math.cos(crystalAngle), kotlin.math.sin(crystalAngle)) * radius * 1.08f
                        drawLine(Color.White.copy(alpha = .88f), towerCenter, tip, maxOf(2f, radius * .17f))
                    }
                }
                else -> repeat(3) { barrelIndex ->
                    val barrelAngle = angle + barrelIndex * Math.PI.toFloat() * 2f / 3f
                    val barrel = towerCenter + Offset(kotlin.math.cos(barrelAngle), kotlin.math.sin(barrelAngle)) * radius * 1.2f
                    drawLine(Color.White.copy(alpha = .92f), towerCenter, barrel, maxOf(2.5f, radius * .19f))
                }
            }
            val level = (cell.meta["level"] as? Number)?.toInt() ?: 1
            repeat(level) { index ->
                drawCircle(Color(0xFFFFD54F), maxOf(1.7f, radius * .09f), towerCenter + Offset((index - (level - 1) / 2f) * radius * .35f, radius * .78f))
            }
        } }
        val visualPositions = mutableMapOf<String, Offset>()
        enemies.forEach { enemy ->
            if (enemy.status == "DEFEATED" || enemy.status == "LEAKED" || now < enemy.spawnAt) return@forEach
            val elapsed = ((now - state.serverTime).coerceAtLeast(0L) / 1_000f).coerceAtMost(.18f)
            val projected = enemy.progress + if (enemy.status == "MOVING") enemy.speed * elapsed else 0f
            val enemyCenter = position(projected)
            visualPositions[enemy.id] = enemyCenter
            val baseRadius = min(cw, ch) * when (enemy.kind) { "GOLEM" -> .30f; "BRUTE" -> .25f; else -> .20f }
            val enemyColor = when (enemy.kind) {
                "GOLEM" -> Color(0xFF8B5CF6); "BRUTE" -> Color(0xFFEF4444)
                "PHANTOM" -> Color(0xFFA5F3FC); else -> Color(0xFFF97316)
            }
            drawCircle(Color.Black.copy(alpha = .42f), baseRadius * 1.1f, enemyCenter + Offset(2f, 3f))
            if (enemy.slowUntil > now) drawCircle(Color(0xFF67E8F9).copy(alpha = .35f), baseRadius * 1.55f, enemyCenter, style = Stroke(3f))
            when (enemy.kind) {
                "GOLEM" -> {
                    drawRoundRect(enemyColor, enemyCenter - Offset(baseRadius, baseRadius), Size(baseRadius * 2, baseRadius * 2), cornerRadius = androidx.compose.ui.geometry.CornerRadius(baseRadius * .3f))
                    drawRect(Color(0xFF312E81), enemyCenter - Offset(baseRadius * .72f, baseRadius * .22f), Size(baseRadius * 1.44f, baseRadius * .44f))
                    drawCircle(Color(0xFFFDE68A), baseRadius * .13f, enemyCenter)
                }
                "PHANTOM" -> {
                    val diamond = Path().apply {
                        moveTo(enemyCenter.x, enemyCenter.y - baseRadius); lineTo(enemyCenter.x + baseRadius, enemyCenter.y)
                        lineTo(enemyCenter.x, enemyCenter.y + baseRadius); lineTo(enemyCenter.x - baseRadius, enemyCenter.y); close()
                    }
                    drawPath(diamond, enemyColor.copy(alpha = .72f))
                    repeat(3) { trail -> drawCircle(enemyColor.copy(alpha = .20f - trail * .05f), baseRadius * (.78f - trail * .16f), enemyCenter - Offset((trail + 1) * baseRadius * .65f, 0f)) }
                }
                "BRUTE" -> {
                    drawCircle(enemyColor, baseRadius, enemyCenter)
                    drawCircle(Color(0xFF7F1D1D), baseRadius * .78f, enemyCenter, style = Stroke(baseRadius * .20f))
                    drawLine(Color.White, enemyCenter - Offset(baseRadius * .42f, 0f), enemyCenter + Offset(baseRadius * .42f, 0f), maxOf(2f, baseRadius * .14f))
                }
                else -> {
                    val scout = Path().apply {
                        moveTo(enemyCenter.x + baseRadius, enemyCenter.y); lineTo(enemyCenter.x - baseRadius * .72f, enemyCenter.y - baseRadius * .72f)
                        lineTo(enemyCenter.x - baseRadius * .45f, enemyCenter.y); lineTo(enemyCenter.x - baseRadius * .72f, enemyCenter.y + baseRadius * .72f); close()
                    }
                    drawPath(scout, enemyColor)
                    drawCircle(Color.White, baseRadius * .14f, enemyCenter + Offset(baseRadius * .20f, 0f))
                }
            }
            val barWidth = baseRadius * 2.25f
            val barOrigin = enemyCenter + Offset(-barWidth / 2f, -baseRadius * 1.55f)
            drawRoundRect(Color(0xFF1F2937), barOrigin, Size(barWidth, maxOf(3f, ch * .055f)))
            drawRoundRect(
                if (enemy.hp / enemy.maxHp > .45f) Color(0xFF22C55E) else Color(0xFFEF4444),
                barOrigin, Size(barWidth * (enemy.hp / enemy.maxHp).coerceIn(0f, 1f), maxOf(3f, ch * .055f)),
            )
        }
        projectiles.forEach { projectile ->
            val target = visualPositions[projectile.targetId] ?: return@forEach
            val start = point(projectile.towerRow.toFloat(), projectile.towerCol.toFloat())
            val travel = ((now - projectile.firedAt).toFloat() / (projectile.arrivesAt - projectile.firedAt).coerceAtLeast(1L)).coerceIn(0f, 1f)
            val head = start + (target - start) * travel
            drawLine(projectile.color.copy(alpha = .35f), start, head, 5f)
            drawCircle(projectile.color.copy(alpha = .20f), min(cw, ch) * .18f, head)
            drawCircle(Color.White, maxOf(2.5f, min(cw, ch) * .06f), head)
            if (travel >= .96f) drawCircle(projectile.color.copy(alpha = .65f), min(cw, ch) * (.12f + pulse * .18f), target, style = Stroke(3f))
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
    onDirectMove: (Int, Int, Any?) -> Unit,
    secretKey: List<String> = emptyList(),
    secretCanGuess: Boolean = true,
    localPlayerId: String? = null,
    modifier: Modifier = Modifier,
) {
    if (state.gameType == GameType.REACTOR_CHAIN) {
        ReactorChainBoard(state, enabled, onDirectMove, modifier)
        return
    }
    if (state.gameType == GameType.TOWER_DEFENSE) {
        TowerDefenseGrid(state, players, selected, enabled, onCellSelected, modifier)
        return
    }
    if (state.gameType == GameType.CAPITAL_ARENA) {
        CapitalArenaBoard(state, players, localPlayerId, onDirectMove, modifier)
        return
    }
    if (state.gameType == GameType.DOTS_AND_BOXES) {
        DotsAndBoxesGrid(state, players, enabled, onDirectMove, modifier)
        return
    }
    if (state.gameType == GameType.NEXUS_ZERO && state.meta["engine"] != "NEXUS_SWIPE") {
        NexusSpatialGrid(state, players, enabled, onCellSelected, onDirectMove, modifier)
        return
    }
    if (state.gameType == GameType.HANGMAN) {
        AnimatedHangmanBoard(state, localPlayerId, modifier)
        return
    }
    if (state.gameType == GameType.ARROWS_ESCAPE) {
        AnimatedArrowsGrid(state, players, enabled, localPlayerId, onDirectMove, modifier)
        return
    }
    val textMeasurer = rememberTextMeasurer()
    var dragStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var dragEnd by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var selectedDot by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var selectedBridgeIsland by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var nexusStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var tacticalStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    var tacticalSkillStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    val illegalWater = remember(state.board, state.gameType) { if (state.gameType == GameType.NURIKABE) illegalWaterCells(state) else emptySet() }
    val warningAlpha by rememberInfiniteTransition(label = "nurikabeWarning").animateFloat(
        initialValue = .35f, targetValue = .9f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "water2x2",
    )
    val arcadeFall by rememberInfiniteTransition(label = "arcadeFall").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "fallingTiles",
    )
    val chessAction = state.meta["lastChessAction"] as? Map<*, *>
    val chessActionProgress = remember(state.gameId) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(state.revision, chessAction) {
        if (state.gameType == GameType.CHESS_TACTICS && chessAction != null) {
            chessActionProgress.snapTo(0f)
            chessActionProgress.animateTo(
                1f,
                tween(620, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            )
        }
    }
    val gestureModifier = if (state.gameType in setOf(GameType.MERGE_2048, GameType.NEXUS_ZERO)) {
        Modifier.pointerInput(state.revision, enabled) {
            var totalDrag = Offset.Zero
            detectDragGestures(
                onDragStart = { totalDrag = Offset.Zero },
                onDrag = { change, amount ->
                    if (enabled) {
                        change.consume()
                        totalDrag += amount
                    }
                },
                onDragEnd = {
                    if (!enabled || totalDrag.getDistance() < 28f) return@detectDragGestures
                    val direction = if (kotlin.math.abs(totalDrag.x) > kotlin.math.abs(totalDrag.y)) {
                        if (totalDrag.x > 0) "RIGHT" else "LEFT"
                    } else if (totalDrag.y > 0) "DOWN" else "UP"
                    onDirectMove(0, 0, direction)
                    totalDrag = Offset.Zero
                },
            )
        }
    } else if (state.gameType == GameType.WORD_SEARCH) {
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
    } else {
        Modifier.pointerInput(state.board, enabled) {
            detectTapGestures(
                onLongPress = { offset ->
                    if (!enabled || state.gameType != GameType.CHESS_TACTICS) return@detectTapGestures
                    val row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1)
                    val col = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1)
                    if (state.board[row][col].value != null) {
                        tacticalStart = CellPosition(row, col)
                        tacticalSkillStart = tacticalStart
                    }
                },
                onTap = { offset ->
                if (!enabled) return@detectTapGestures
                val row = floor(offset.y / (size.height / state.rows)).toInt().coerceIn(0, state.rows - 1)
                val col = floor(offset.x / (size.width / state.columns)).toInt().coerceIn(0, state.columns - 1)
                tacticalSkillStart?.takeIf { state.gameType == GameType.CHESS_TACTICS }?.let { source ->
                    onDirectMove(
                        source.row,
                        source.column,
                        mapOf("action" to "SKILL", "targetRow" to row, "targetCol" to col),
                    )
                    tacticalSkillStart = null
                    tacticalStart = null
                    return@detectTapGestures
                }
                when (state.gameType) {
                    GameType.MINESWEEPER -> onDirectMove(row, col, "REVEAL")
                    GameType.ARROWS_ESCAPE -> onDirectMove(row, col, "ESCAPE")
                    GameType.TIC_TAC_TOE -> if (state.board[row][col].value == null) {
                        onDirectMove(row, col, "MARK")
                    } else onCellSelected(row, col)
                    GameType.HITORI -> onDirectMove(row, col, "BLOCK")
                    GameType.MEMORY_NEON -> onDirectMove(row, col, "FLIP")
                    GameType.REACTOR_CHAIN -> onDirectMove(row, col, "CHAIN")
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
                        val tapped = CellPosition(row, col)
                        val first = nexusStart
                        if (first == null || first == tapped) {
                            nexusStart = tapped.takeUnless { first == tapped }
                            nexusStart?.let { onCellSelected(it.row, it.column) }
                        } else {
                            // Estado local inmediato: no depende de esperar el eco Socket.io
                            // entre el primer y el segundo toque.
                            onDirectMove(first.row, first.column, mapOf("targetRow" to row, "targetCol" to col))
                            nexusStart = null
                        }
                    }
                    GameType.CHECKERS, GameType.CHESS_TACTICS -> {
                        val tapped = CellPosition(row, col)
                        val first = tacticalStart
                        if (first == null || first == tapped) {
                            tacticalStart = tapped.takeUnless { first == tapped }
                            tacticalStart?.let { onCellSelected(it.row, it.column) }
                        } else {
                            val targetCell = state.board[row][col]
                            val action = if (state.gameType == GameType.CHESS_TACTICS && targetCell.value != null) "ATTACK" else "MOVE"
                            onDirectMove(first.row, first.column, mapOf("action" to action, "targetRow" to row, "targetCol" to col))
                            tacticalStart = null
                        }
                    }
                    else -> onCellSelected(row, col)
                }
                },
            )
        }
    }

    // Cachea geometría/colores fuera del DrawScope.
    val identityColors = remember(players) {
        players.mapValues { (_, player) -> parseGenericColor(player.colorHex) }
    }
    val locallyRemovedArrows = remember(state.meta, localPlayerId) {
        ((state.meta["removedByPlayer"] as? Map<*, *>)?.get(localPlayerId) as? List<*>)
            ?.mapNotNull { it?.toString() }?.toSet().orEmpty()
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
    val tacticalRanges = remember(state.board, tacticalStart, tacticalSkillStart, state.gameType) {
        tacticalStart?.let { source ->
            if (tacticalSkillStart != null && state.gameType == GameType.CHESS_TACTICS) {
                emptySet<CellPosition>() to composeChessSkillTargets(state, source)
            } else composeTacticalRanges(state, source)
        } ?: (emptySet<CellPosition>() to emptySet())
    }

    Box(
        modifier.fillMaxWidth().aspectRatio(state.columns.toFloat() / state.rows.toFloat()),
    ) {
    Canvas(
        Modifier.fillMaxSize().then(gestureModifier)
            .background(Color.White, RoundedCornerShape(8.dp)),
    ) {
        val cellWidth = size.width / state.columns
        val cellHeight = size.height / state.rows
        state.board.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, cell ->
                if (state.gameType == GameType.ARROWS_ESCAPE && "$row:$col" in locallyRemovedArrows) return@forEachIndexed
                val origin = Offset(col * cellWidth, row * cellHeight)
                val ownerColor = cell.ownerId?.let(identityColors::get)
                val bridgeValid = state.gameType == GameType.BRIDGES && "${row}:${col}" in validBridgeTargets
                val fill = when {
                    cell.meta["hintColor"] == "RED" -> Color(0xFFFF1744).copy(alpha = .42f)
                    cell.meta["hintColor"] == "GREEN" -> Color(0xFF00C853).copy(alpha = .38f)
                    cell.meta["sonarState"] == "RIVER" -> Color(0xFF2196F3).copy(alpha = .36f)
                    cell.meta["sonarState"] == "ISLAND" -> Color(0xFF66BB6A).copy(alpha = .34f)
                    CellPosition(row, col) in illegalWater -> Color(0xFFFF1744).copy(alpha = warningAlpha)
                    state.gameType == GameType.NURIKABE && cell.value == "RIVER" -> Color(0xFF2196F3).copy(alpha = .78f)
                    state.gameType == GameType.NURIKABE && cell.value == "ISLAND" -> Color(0xFF66BB6A).copy(alpha = .72f)
                    bridgeValid -> Color(0xFF00C853).copy(alpha = .32f)
                    CellPosition(row, col) in tacticalRanges.first -> Color(0xFF1565C0).copy(alpha = .34f)
                    CellPosition(row, col) in tacticalRanges.second -> Color(0xFFE53935).copy(alpha = .38f)
                    state.gameType == GameType.SECRET_CODE && cell.meta["revealedColor"] != null -> secretColor(cell.meta["revealedColor"].toString()).copy(alpha = .82f)
                    state.gameType == GameType.SECRET_CODE && secretKey.size == 25 -> secretColor(secretKey[row * 5 + col]).copy(alpha = .42f)
                    state.gameType == GameType.BRIDGES && selectedBridgeIsland == CellPosition(row, col) -> Color(0xFF00A8FF).copy(alpha = .28f)
                    cell.meta["given"] == true -> Color(0xFFFFD54F).copy(alpha = .62f)
                    state.gameType == GameType.MEMORY_NEON && cell.value == null -> Color(0xFF312E81)
                    state.gameType == GameType.MEMORY_NEON && cell.meta["mismatch"] == true -> Color(0xFFFFCDD2)
                    state.gameType == GameType.MERGE_2048 -> mergeTileColor((cell.value as? Number)?.toInt() ?: 0)
                    tacticalSkillStart == CellPosition(row, col) -> Color(0xFFE1BEE7)
                    (tacticalStart ?: selected) == CellPosition(row, col) -> Color(0xFFFFF59D)
                    ownerColor != null -> ownerColor.copy(alpha = 0.25f)
                    state.gameType == GameType.CHECKERS -> if ((row + col) % 2 == 0) Color(0xFFE7D7C1) else Color(0xFF704D38)
                    state.gameType == GameType.CHESS_TACTICS -> if ((row + col) % 2 == 0) Color(0xFFE8EEF7) else Color(0xFF91A7C5)
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
                } else if (state.gameType == GameType.DOTS_AND_BOXES) {
                    cell.meta + listOf("top", "right", "bottom", "left").associate { side ->
                        "_${side}Color" to (cell.meta["${side}OwnerId"]?.toString()?.let(identityColors::get) ?: Color(0xFF00A8FF))
                    }
                } else cell.meta
                renderGenericCell(
                    state.gameType,
                    cell.value,
                    renderMeta,
                    cell.isBlocked,
                    origin,
                    cellWidth,
                    cellHeight,
                    textMeasurer,
                    arcadeFall,
                )
                drawRect(Color(0xFFB0BEC5), origin, Size(cellWidth, cellHeight), style = Stroke(1.2f))
                if (ownerColor != null) drawRect(ownerColor, origin, Size(cellWidth, cellHeight), style = Stroke(2.4f))
            }
        }
        if (state.gameType == GameType.CHESS_TACTICS && chessAction != null) {
            val sourceRow = (chessAction["sourceRow"] as? Number)?.toInt() ?: -1
            val sourceCol = (chessAction["sourceCol"] as? Number)?.toInt() ?: -1
            val targetRow = (chessAction["targetRow"] as? Number)?.toInt() ?: sourceRow
            val targetCol = (chessAction["targetCol"] as? Number)?.toInt() ?: sourceCol
            if (sourceRow >= 0 && sourceCol >= 0 && targetRow >= 0 && targetCol >= 0) {
                val start = Offset((sourceCol + .5f) * cellWidth, (sourceRow + .5f) * cellHeight)
                val end = Offset((targetCol + .5f) * cellWidth, (targetRow + .5f) * cellHeight)
                val progress = chessActionProgress.value
                val head = start + (end - start) * progress
                val skill = chessAction["skill"]?.toString()
                val action = chessAction["action"]?.toString()
                val effectColor = if (action == "SKILL") Color(0xFFAA00FF) else Color(0xFFFF1744)
                drawLine(
                    effectColor.copy(alpha = 1f - progress * .45f),
                    start,
                    head,
                    maxOf(4f, cellWidth * .09f),
                )
                drawCircle(
                    effectColor.copy(alpha = (1f - progress).coerceAtLeast(.08f)),
                    min(cellWidth, cellHeight) * (.18f + progress * .52f),
                    end,
                    style = Stroke(maxOf(3f, cellWidth * .055f)),
                )
                if (skill == "SEISMIC_LEAP" || skill == "STONE_WALL") {
                    drawCircle(
                        Color(0xFFFF6D00).copy(alpha = 1f - progress),
                        min(cellWidth, cellHeight) * progress * 1.7f,
                        end,
                        style = Stroke(maxOf(4f, cellWidth * .08f)),
                    )
                }
            }
        }
    }
    val abilitySource = tacticalStart ?: selected
    if (state.gameType == GameType.CHESS_TACTICS && abilitySource != null) {
        val abilityCell = state.board.getOrNull(abilitySource.row)?.getOrNull(abilitySource.column)
        val cooldown = (abilityCell?.meta?.get("currentCooldown") as? Number)?.toInt() ?: 0
        val skillLabel = when (abilityCell?.meta?.get("type")?.toString()) {
            "PAWN" -> "Carga del Peón"
            "KNIGHT" -> "Salto Sísmico"
            "BISHOP" -> "Rayo"
            "ROOK" -> "Muro"
            "QUEEN" -> "Intimidación"
            "KING" -> "Revivir"
            else -> "Habilidad"
        }
        Button(
            onClick = {
                tacticalStart = abilitySource
                tacticalSkillStart = abilitySource
            },
            enabled = enabled && cooldown == 0,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).zIndex(4f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
        ) {
            Text(if (cooldown > 0) "$skillLabel · CD $cooldown" else "✦ $skillLabel", fontWeight = FontWeight.Black)
        }
    }
    }
}

@Composable
private fun DotsAndBoxesGrid(
    state: GenericBoardState,
    players: Map<String, Player>,
    enabled: Boolean,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tappedStart by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    val colors = remember(players) { players.mapValues { parseGenericColor(it.value.colorHex) } }
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(state.columns.toFloat() / state.rows.toFloat())
            .padding(10.dp)
            .pointerInput(state.board, enabled, tappedStart) {
                val inset = 12f
                fun dot(offset: Offset): CellPosition {
                    val stepX = (size.width - inset * 2) / state.columns
                    val stepY = (size.height - inset * 2) / state.rows
                    return CellPosition(
                        ((offset.y - inset) / stepY).roundToInt().coerceIn(0, state.rows),
                        ((offset.x - inset) / stepX).roundToInt().coerceIn(0, state.columns),
                    )
                }
                var dragStart: CellPosition? = null
                var dragEnd: CellPosition? = null
                detectDragGestures(
                    onDragStart = { if (enabled) { dragStart = dot(it); dragEnd = dragStart } },
                    onDrag = { change, _ -> if (enabled) { change.consume(); dragEnd = dot(change.position) } },
                    onDragEnd = {
                        dragStart?.let { first -> dragEnd?.let { second ->
                            dotsEdge(first, second, state.rows, state.columns)?.let { onDirectMove(it.row, it.col, it.side) }
                        } }
                    },
                )
            }
            .pointerInput(state.board, enabled, tappedStart) {
                val inset = 12f
                detectTapGestures { offset ->
                    if (!enabled) return@detectTapGestures
                    val stepX = (size.width - inset * 2) / state.columns
                    val stepY = (size.height - inset * 2) / state.rows
                    val dot = CellPosition(
                        ((offset.y - inset) / stepY).roundToInt().coerceIn(0, state.rows),
                        ((offset.x - inset) / stepX).roundToInt().coerceIn(0, state.columns),
                    )
                    val first = tappedStart
                    if (first == null || first == dot) tappedStart = dot.takeUnless { first == dot }
                    else {
                        dotsEdge(first, dot, state.rows, state.columns)?.let { onDirectMove(it.row, it.col, it.side) }
                        tappedStart = null
                    }
                }
            },
    ) {
        val inset = 12f
        val stepX = (size.width - inset * 2) / state.columns
        val stepY = (size.height - inset * 2) / state.rows
        fun point(row: Int, col: Int) = Offset(inset + col * stepX, inset + row * stepY)
        state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
            val topLeft = point(row, col)
            cell.ownerId?.let(colors::get)?.let { drawRect(it.copy(alpha = .23f), topLeft, Size(stepX, stepY)) }
            val edgeColor: (String) -> Color = { side ->
                cell.meta["${side}OwnerId"]?.toString()?.let(colors::get) ?: Color(0xFF00A8FF)
            }
            if (cell.meta["top"] == true) drawLine(edgeColor("top"), point(row, col), point(row, col + 1), 6f)
            if (cell.meta["left"] == true) drawLine(edgeColor("left"), point(row, col), point(row + 1, col), 6f)
            if (col == state.columns - 1 && cell.meta["right"] == true) drawLine(edgeColor("right"), point(row, col + 1), point(row + 1, col + 1), 6f)
            if (row == state.rows - 1 && cell.meta["bottom"] == true) drawLine(edgeColor("bottom"), point(row + 1, col), point(row + 1, col + 1), 6f)
        } }
        // Límites inclusivos y margen interno: ningún punto inferior/derecho se recorta.
        for (y in 0..state.rows) for (x in 0..state.columns) {
            val selected = tappedStart == CellPosition(y, x)
            drawCircle(if (selected) Color(0xFFFFC400) else Color(0xFF102A56), if (selected) 8f else 6f, point(y, x))
        }
    }
}

@Composable
private fun NexusSpatialGrid(
    state: GenericBoardState,
    players: Map<String, Player>,
    enabled: Boolean,
    onCellSelected: (Int, Int) -> Unit,
    onDirectMove: (Int, Int, Any?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var start by remember(state.gameId) { mutableStateOf<CellPosition?>(null) }
    val colors = remember(players) { players.mapValues { parseGenericColor(it.value.colorHex) } }
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxSize()
            .background(Color(0xFFF5F8FF), RoundedCornerShape(12.dp))
            .pointerInput(state.board, enabled, start) {
                detectTapGestures { offset ->
                    if (!enabled) return@detectTapGestures
                    val arenaWidth = (state.meta["arenaWidth"] as? Number)?.toFloat() ?: 1000f
                    val arenaHeight = (state.meta["arenaHeight"] as? Number)?.toFloat() ?: 700f
                    val scale = minOf(size.width / arenaWidth, size.height / arenaHeight)
                    val left = (size.width - arenaWidth * scale) / 2f
                    val top = (size.height - arenaHeight * scale) / 2f
                    var tapped: CellPosition? = null
                    state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
                        val x = left + ((cell.meta["x"] as? Number)?.toFloat() ?: 0f) * scale
                        val y = top + ((cell.meta["y"] as? Number)?.toFloat() ?: 0f) * scale
                        val width = ((cell.meta["width"] as? Number)?.toFloat() ?: 70f) * scale
                        val height = ((cell.meta["height"] as? Number)?.toFloat() ?: 54f) * scale
                        if (offset.x in x..(x + width) && offset.y in y..(y + height)) tapped = CellPosition(row, col)
                    } }
                    val target = tapped ?: return@detectTapGestures
                    val first = start
                    if (first == null || first == target) {
                        start = target.takeUnless { first == target }
                        start?.let { onCellSelected(it.row, it.column) }
                    } else {
                        onDirectMove(first.row, first.column, mapOf("targetRow" to target.row, "targetCol" to target.column))
                        start = null
                    }
                }
            },
    ) {
        val arenaWidth = (state.meta["arenaWidth"] as? Number)?.toFloat() ?: 1000f
        val arenaHeight = (state.meta["arenaHeight"] as? Number)?.toFloat() ?: 700f
        val scale = minOf(size.width / arenaWidth, size.height / arenaHeight)
        val left = (size.width - arenaWidth * scale) / 2f
        val top = (size.height - arenaHeight * scale) / 2f
        state.board.forEachIndexed { row, cells -> cells.forEachIndexed { col, cell ->
            val x = left + ((cell.meta["x"] as? Number)?.toFloat() ?: 0f) * scale
            val y = top + ((cell.meta["y"] as? Number)?.toFloat() ?: 0f) * scale
            val width = ((cell.meta["width"] as? Number)?.toFloat() ?: 70f) * scale
            val height = ((cell.meta["height"] as? Number)?.toFloat() ?: 54f) * scale
            val selected = start == CellPosition(row, col)
            val charge = (cell.value as? Number)?.toInt() ?: 0
            val color = cell.ownerId?.let(colors::get) ?: if (charge >= 0) Color(0xFF1565C0) else Color(0xFFE91E63)
            drawRoundRect(color.copy(alpha = if (cell.ownerId == null) .18f else .08f), Offset(x, y), Size(width, height))
            drawRoundRect(if (selected) Color(0xFFFFC400) else color, Offset(x, y), Size(width, height), style = Stroke(if (selected) 5f else 2.5f))
            drawCircle(color.copy(alpha = .22f), minOf(width, height) * .24f, Offset(x + width / 2f, y + height / 2f))
            drawCenteredText(if (charge > 0) "+$charge" else charge, Offset(x + width / 2f, y + height / 2f), width, textMeasurer, color)
        } }
    }
}

private fun composeChessSkillTargets(
    state: GenericBoardState,
    source: CellPosition,
): Set<CellPosition> {
    val cell = state.board.getOrNull(source.row)?.getOrNull(source.column) ?: return emptySet()
    val team = cell.meta["team"]?.toString()
    fun available(row: Int, col: Int): Boolean {
        val target = state.board.getOrNull(row)?.getOrNull(col) ?: return false
        return !target.isBlocked
    }
    return when (cell.meta["type"]?.toString()) {
        "PAWN" -> {
            val direction = if (team == "BLUE") 1 else -1
            val middle = state.board.getOrNull(source.row + direction)?.getOrNull(source.column)
            val destination = state.board.getOrNull(source.row + direction * 2)?.getOrNull(source.column)
            if (middle?.value == null && destination != null && !destination.isBlocked &&
                (destination.value == null || destination.meta["team"] != team)
            ) setOf(CellPosition(source.row + direction * 2, source.column)) else emptySet()
        }
        "KNIGHT" -> listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
            .map { (dy, dx) -> CellPosition(source.row + dy, source.column + dx) }
            .filterTo(mutableSetOf()) { available(it.row, it.column) && state.board[it.row][it.column].value == null }
        "BISHOP" -> buildSet {
            for ((dy, dx) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
                for (distance in 1..3) {
                    val row = source.row + dy * distance
                    val col = source.column + dx * distance
                    val target = state.board.getOrNull(row)?.getOrNull(col) ?: break
                    if (target.isBlocked) break
                    if (target.value != null) {
                        if (target.meta["team"] != team) add(CellPosition(row, col))
                        break
                    }
                    add(CellPosition(row, col))
                }
            }
        }
        "ROOK" -> buildSet {
            for (row in source.row - 1..source.row + 1) for (col in source.column - 1..source.column + 1) {
                if ((row != source.row || col != source.column) && available(row, col) && state.board[row][col].value == null) {
                    add(CellPosition(row, col))
                }
            }
        }
        "QUEEN" -> setOf(source)
        "KING" -> buildSet {
            for (row in source.row - 1..source.row + 1) for (col in source.column - 1..source.column + 1) {
                if ((row != source.row || col != source.column) && available(row, col) && state.board[row][col].value == null) {
                    add(CellPosition(row, col))
                }
            }
        }
        else -> emptySet()
    }
}

private fun composeTacticalRanges(
    state: GenericBoardState,
    source: CellPosition,
): Pair<Set<CellPosition>, Set<CellPosition>> {
    val cell = state.board.getOrNull(source.row)?.getOrNull(source.column) ?: return emptySet<CellPosition>() to emptySet()
    if (cell.value == null) return emptySet<CellPosition>() to emptySet()
    if (state.gameType == GameType.CHECKERS) {
        val team = cell.meta["team"]?.toString()
        val moves = mutableSetOf<CellPosition>()
        val attacks = mutableSetOf<CellPosition>()
        for (dy in listOf(-1, 1)) for (dx in listOf(-1, 1)) {
            val near = state.board.getOrNull(source.row + dy)?.getOrNull(source.column + dx)
            if (near?.value == null && near?.isBlocked == false) moves += CellPosition(source.row + dy, source.column + dx)
            val landing = state.board.getOrNull(source.row + dy * 2)?.getOrNull(source.column + dx * 2)
            if (near?.meta?.get("team") != null && near.meta["team"] != team && landing?.value == null && landing?.isBlocked == false) {
                attacks += CellPosition(source.row + dy * 2, source.column + dx * 2)
            }
        }
        return moves to attacks
    }
    if (state.gameType != GameType.CHESS_TACTICS) return emptySet<CellPosition>() to emptySet()
    val ap = (cell.meta["ap"] as? Number)?.toInt() ?: 0
    if (ap <= 0 || (cell.meta["statusEffects"] as? List<*>)?.any { it.toString().equals("Stunned", true) } == true) {
        return emptySet<CellPosition>() to emptySet()
    }
    val team = cell.meta["team"]?.toString()
    val moves = mutableSetOf<CellPosition>()
    val attacks = mutableSetOf<CellPosition>()
    val type = cell.meta["type"]?.toString()
    val stepOffsets = when (type) {
        "KNIGHT" -> listOf(-2 to -1, -2 to 1, -1 to -2, -1 to 2, 1 to -2, 1 to 2, 2 to -1, 2 to 1)
        "KING" -> listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
        else -> emptyList()
    }
    stepOffsets.forEach { (dy, dx) ->
        val row = source.row + dy; val col = source.column + dx
        if (row !in state.board.indices || col !in state.board[row].indices) return@forEach
        val target = state.board[row][col]
        if (target.value == null) moves += CellPosition(row, col)
        else if (target.meta["team"] != team) attacks += CellPosition(row, col)
    }
    if (type == "PAWN") {
        val direction = if (team == "BLUE") 1 else -1
        val forward = state.board.getOrNull(source.row + direction)?.getOrNull(source.column)
        if (forward?.value == null) {
            moves += CellPosition(source.row + direction, source.column)
            val double = state.board.getOrNull(source.row + direction * 2)?.getOrNull(source.column)
            if (cell.meta["hasMoved"] != true && double?.value == null && double?.isBlocked == false) {
                moves += CellPosition(source.row + direction * 2, source.column)
            }
        }
        for (dx in listOf(-1, 1)) {
            val target = state.board.getOrNull(source.row + direction)?.getOrNull(source.column + dx)
            if (target?.value != null && target.meta["team"] != team) attacks += CellPosition(source.row + direction, source.column + dx)
        }
    }
    val directions = when (type) {
        "BISHOP" -> listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)
        "ROOK" -> listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        "QUEEN" -> listOf(-1 to -1, -1 to 0, -1 to 1, 0 to -1, 0 to 1, 1 to -1, 1 to 0, 1 to 1)
        else -> emptyList()
    }
    for ((dy, dx) in directions) {
        var row = source.row + dy; var col = source.column + dx
        while (row in state.board.indices && col in state.board[row].indices) {
            val target = state.board[row][col]
            if (target.value == null) moves += CellPosition(row, col)
            else {
                if (target.meta["team"] != team) attacks += CellPosition(row, col)
                break
            }
            row += dy; col += dx
        }
    }
    return moves to attacks
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
        GameType.TIC_TAC_TOE -> Text(
            "Ultimate: gana 3 mini-tableros en línea. Al poner tu 4.ª ficha, se desintegra la más antigua; la casilla jugada decide el siguiente mini-tablero.",
            fontWeight = FontWeight.Black,
        )
        GameType.MINESWEEPER -> Text("✦ Toca directamente una casilla para revelarla · ${state.meta["mineCount"] ?: "?"} minas")
        GameType.DOTS_AND_BOXES -> Text("Toca cerca del borde que quieres trazar.")
        GameType.HITORI -> Text("Toca el número duplicado que quieras apagar.")
        GameType.NURIKABE -> Text("Toca las casillas de río; conserva blancas las islas numeradas.")
        GameType.BRIDGES -> Text("Toca los segmentos entre islas para construir la red.")
        GameType.CHECKERS -> Text("Las capturas son obligatorias. Si capturas, puedes encadenar otro salto.")
        GameType.CHESS_TACTICS -> Text("Azul: movimiento · Rojo: ataque. Toca una pieza y luego su botón morado para apuntar la habilidad; solo consumes una acción.")
        GameType.HANGMAN -> Text("Pista: ${state.meta["clue"] ?: "Sin pista"} · Errores máximos: 6", fontWeight = FontWeight.Black)
        GameType.ARROWS_ESCAPE -> Text("Libera todas las flechas. Progreso: ${state.meta["progress"] ?: emptyMap<String, Int>()}")
        GameType.MEMORY_NEON -> Text(
            "Parejas encontradas: ${state.meta["pairsFound"] ?: 0}/${state.meta["pairCount"] ?: "?"} · recuerda cada carta.",
            fontWeight = FontWeight.Black,
        )
        GameType.MERGE_2048 -> Text(
            "Ficha máxima: ${state.meta["highestTile"] ?: state.board.flatten().mapNotNull { (it.value as? Number)?.toInt() }.maxOrNull() ?: 0} · meta ${state.meta["target"] ?: 256}",
            fontWeight = FontWeight.Black,
        )
        GameType.NEXUS_ZERO -> Text(
            "Desliza todo el tablero. Las fichas +N y -N se destruyen al tocarse; valores incompatibles nunca se superponen.",
            fontWeight = FontWeight.Black,
        )
        GameType.CROSS_LETTERS -> {
            val active = state.meta["activePlayerId"]?.toString()
            Text(
                if (state.meta["blitz"] == true) "BLITZ EN TIEMPO REAL · ambos jugadores pueden colocar palabras"
                else "Turno: ${active?.take(8) ?: "preparando…"} · selecciona la casilla inicial",
                fontWeight = FontWeight.Bold,
            )
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
    animationPhase: Float = 0f,
) {
    val center = Offset(origin.x + width / 2f, origin.y + height / 2f)
    when (gameType) {
        GameType.TIC_TAC_TOE -> {
            val mark = value?.toString().orEmpty()
            if (mark == "X") {
                drawLine(Color(0xFF1565C0), origin + Offset(width * .22f, height * .22f), origin + Offset(width * .78f, height * .78f), width * .09f)
                drawLine(Color(0xFF1565C0), origin + Offset(width * .78f, height * .22f), origin + Offset(width * .22f, height * .78f), width * .09f)
            } else if (mark == "O") {
                drawCircle(Color(0xFFE91E63), min(width, height) * .29f, center, style = Stroke(width * .09f))
            }
            if (meta["shielded"] == true) {
                drawCircle(Color(0xFFFFC107).copy(alpha = .24f), min(width, height) * .43f, center)
                drawCircle(Color(0xFFFFA000), min(width, height) * .41f, center, style = Stroke(maxOf(2f, width * .055f)))
            }
            val row = (meta["_row"] as? Number)?.toInt() ?: 0
            val col = (meta["_col"] as? Number)?.toInt() ?: 0
            if (row % 3 == 0) drawLine(Color(0xFF7C3AED), origin, origin + Offset(width, 0f), maxOf(2f, width * .07f))
            if (col % 3 == 0) drawLine(Color(0xFF7C3AED), origin, origin + Offset(0f, height), maxOf(2f, width * .07f))
        }
        GameType.DOTS_AND_BOXES -> {
            drawCircle(Color(0xFF102A56), min(width, height) * .08f, Offset(origin.x, origin.y))
            if (meta["top"] == true) drawLine(meta["_topColor"] as? Color ?: Color(0xFF00A8FF), origin, Offset(origin.x + width, origin.y), 5f)
            if (meta["left"] == true) drawLine(meta["_leftColor"] as? Color ?: Color(0xFF00A8FF), origin, Offset(origin.x, origin.y + height), 5f)
            if (meta["right"] == true) drawLine(meta["_rightColor"] as? Color ?: Color(0xFF00A8FF), Offset(origin.x + width, origin.y), Offset(origin.x + width, origin.y + height), 5f)
            if (meta["bottom"] == true) drawLine(meta["_bottomColor"] as? Color ?: Color(0xFF00A8FF), Offset(origin.x, origin.y + height), Offset(origin.x + width, origin.y + height), 5f)
        }
        GameType.CHESS_TACTICS -> {
            if (value != null) {
                val teamColor = if (meta["team"] == "BLUE") Color(0xFF1565C0) else Color(0xFFE53935)
                val glyph = when (value.toString()) {
                    "PAWN" -> "♟"
                    "KNIGHT" -> "♞"
                    "BISHOP" -> "♝"
                    "ROOK" -> "♜"
                    "QUEEN" -> "♛"
                    else -> "♚"
                }
                val bob = sin(animationPhase * Math.PI.toFloat() * 2f) * height * .025f
                val animatedCenter = center + Offset(0f, bob)
                val status = (meta["statusEffects"] as? List<*>)?.map { it.toString() }.orEmpty()
                val auraColor = when {
                    status.any { it.equals("Invulnerable", true) } -> Color(0xFFFFD600)
                    status.any { it.equals("Ambushing", true) } -> Color(0xFFAA00FF)
                    status.any { it.equals("Stunned", true) } -> Color(0xFF00B8D4)
                    meta["isShielded"] == true -> Color(0xFF00C853)
                    else -> teamColor
                }
                val auraPulse = .36f + sin(animationPhase * Math.PI.toFloat() * 2f) * .025f
                drawCircle(auraColor.copy(alpha = .18f), min(width, height) * auraPulse, animatedCenter)
                if (status.isNotEmpty() || meta["isShielded"] == true) {
                    drawCircle(
                        auraColor.copy(alpha = .72f),
                        min(width, height) * (auraPulse + .02f),
                        animatedCenter,
                        style = Stroke(maxOf(2f, width * .035f)),
                    )
                }
                drawCenteredText(glyph, animatedCenter, width * .92f, textMeasurer, teamColor)
                val hp = (meta["hp"] as? Number)?.toFloat() ?: 0f
                val maxHp = (meta["maxHp"] as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 1f
                val barOrigin = origin + Offset(width * .08f, height * .07f)
                drawRoundRect(Color(0xFF3E2723), barOrigin, Size(width * .84f, height * .08f))
                drawRoundRect(Color(0xFF00C853), barOrigin, Size(width * .84f * (hp / maxHp).coerceIn(0f, 1f), height * .08f))
                val ap = (meta["ap"] as? Number)?.toInt() ?: 0
                val apLayout = textMeasurer.measure("AP $ap", TextStyle(color = teamColor, fontSize = 7.sp, fontWeight = FontWeight.Black))
                drawText(apLayout, topLeft = origin + Offset(width - apLayout.size.width - 2f, height - apLayout.size.height - 1f))
                val cooldown = (meta["currentCooldown"] as? Number)?.toInt() ?: 0
                if (cooldown > 0) {
                    val cooldownLayout = textMeasurer.measure(
                        "CD $cooldown",
                        TextStyle(color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black),
                    )
                    drawRoundRect(
                        Color(0xFF311B92).copy(alpha = .90f),
                        origin + Offset(2f, height - cooldownLayout.size.height - 4f),
                        Size(cooldownLayout.size.width + 5f, cooldownLayout.size.height + 2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
                    )
                    drawText(cooldownLayout, topLeft = origin + Offset(4f, height - cooldownLayout.size.height - 3f))
                }
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
        GameType.CHECKERS -> {
            if (value != null) {
                val teamColor = if (meta["team"] == "BLUE") Color(0xFF1565C0) else Color(0xFFE53935)
                drawCircle(Color.Black.copy(alpha = .18f), min(width, height) * .37f, center + Offset(0f, 3f))
                drawCircle(teamColor, min(width, height) * .35f, center)
                drawCircle(Color.White.copy(alpha = .62f), min(width, height) * .27f, center, style = Stroke(2f))
                if (meta["king"] == true) drawCenteredText("♛", center, width * .7f, textMeasurer, Color(0xFFFFD54F))
            }
        }
        GameType.HANGMAN -> drawCenteredText(value ?: "_", center, width, textMeasurer, Color(0xFF102A56))
        GameType.ARROWS_ESCAPE -> {
            drawRoundRect(Color(0xFF311B92).copy(alpha = .22f), origin + Offset(width * .07f, height * .09f), Size(width * .88f, height * .85f))
            drawRoundRect(Color(0xFF7C3AED).copy(alpha = .18f), origin + Offset(2f, 2f), Size(width - 4f, height - 4f))
            if (meta["shapeAnchor"] == true || meta["shapeId"] == null) {
                drawCenteredText(
                    when (value?.toString()) { "UP" -> "↑"; "RIGHT" -> "→"; "DOWN" -> "↓"; else -> "←" },
                    center, width, textMeasurer, Color(0xFF5B21B6),
                )
            }
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
        GameType.MEMORY_NEON -> {
            if (value == null) {
                val pulse = .18f + sin(animationPhase * Math.PI.toFloat() * 2f) * .03f
                drawCircle(Color(0xFF22D3EE).copy(alpha = .28f), min(width, height) * pulse, center)
                drawCenteredText("?", center, width * .72f, textMeasurer, Color.White)
            } else {
                drawCircle(Color.White.copy(alpha = .94f), min(width, height) * .34f, center)
                drawCenteredText(value, center, width * .78f, textMeasurer, Color(0xFF5B21B6))
            }
        }
        GameType.MERGE_2048 -> if (value != null) {
            val number = (value as? Number)?.toInt() ?: 0
            drawRoundRect(
                Color.White.copy(alpha = .16f),
                origin + Offset(width * .07f, height * .07f),
                Size(width * .86f, height * .86f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * .14f),
            )
            drawFittedCellText(number, center, width * .92f, textMeasurer, if (number <= 4) Color(0xFF102A56) else Color.White)
        }
        GameType.TOWER_DEFENSE -> {
            val isPath = meta["path"] == true
            if (isPath) {
                drawRoundRect(
                    Color(0xFF334155),
                    origin + Offset(1f, 1f),
                    Size(width - 2f, height - 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(min(width, height) * .12f),
                )
                drawLine(
                    Color(0xFF94A3B8),
                    Offset(origin.x + width * .12f, center.y),
                    Offset(origin.x + width * .88f, center.y),
                    maxOf(1.5f, height * .045f),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
                when {
                    meta["spawn"] == true -> drawCenteredText("▶", center, width * .78f, textMeasurer, Color(0xFF22C55E))
                    meta["base"] == true -> drawCenteredText("◆", center, width * .78f, textMeasurer, Color(0xFFFACC15))
                }
            } else {
                drawRect(Color(0xFFDDF4E5), origin + Offset(1f, 1f), Size(width - 2f, height - 2f))
                val towerType = meta["towerType"]?.toString()
                if (towerType != null) {
                    val towerColor = when (towerType) {
                        "BLAST" -> Color(0xFFF97316)
                        "SNIPER" -> Color(0xFF7C3AED)
                        "FROST" -> Color(0xFF06B6D4)
                        else -> Color(0xFF2563EB)
                    }
                    val pulse = 1f + sin(animationPhase * Math.PI.toFloat() * 2f) * .06f
                    drawCircle(towerColor.copy(alpha = .18f), min(width, height) * .42f * pulse, center)
                    drawCircle(towerColor, min(width, height) * .28f, center)
                    drawLine(
                        Color.White,
                        center,
                        Offset(center.x, origin.y + height * .14f),
                        maxOf(2f, width * .07f),
                    )
                    val level = (meta["level"] as? Number)?.toInt() ?: 1
                    drawCenteredText(level, center, width * .55f, textMeasurer, Color.White)
                }
            }
        }
        GameType.REACTOR_CHAIN -> if (value != null) {
            val orbColors = listOf(
                Color(0xFFEF4444), Color(0xFF2563EB), Color(0xFF16A34A),
                Color(0xFFFACC15), Color(0xFF9333EA), Color(0xFFF97316),
            )
            val color = orbColors[((value as? Number)?.toInt() ?: 1).minus(1).mod(orbColors.size)]
            val pulse = .30f + sin((animationPhase + ((meta["_row"] as? Number)?.toFloat() ?: 0f) * .08f) * Math.PI.toFloat() * 2f) * .025f
            drawCircle(color.copy(alpha = .24f), min(width, height) * (pulse + .10f), center)
            drawCircle(color, min(width, height) * pulse, center)
            drawCircle(Color.White.copy(alpha = .72f), min(width, height) * .09f, center - Offset(width * .08f, height * .08f))
        }
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
        GameType.MINESWEEPER, GameType.TIC_TAC_TOE, GameType.DOTS_AND_BOXES,
        GameType.BRIDGES, GameType.NEXUS_ZERO, GameType.ARROWS_ESCAPE,
        GameType.CHECKERS, GameType.MEMORY_NEON, GameType.REACTOR_CHAIN -> Unit
        GameType.HITORI -> Button(
            onClick = { onMoveAt(0, 0, mapOf("action" to "HINT")) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("💡 Pista determinista", fontWeight = FontWeight.Black) }
        GameType.NURIKABE -> {
            val used = ((state.genericBoard?.meta?.get("sonarUses") as? Map<*, *>)?.get(state.playerId) as? Number)?.toInt() ?: 0
            val target = state.selected ?: CellPosition(state.genericBoard?.rows?.div(2) ?: 0, state.genericBoard?.columns?.div(2) ?: 0)
            Button(
                onClick = { onMoveAt(target.row, target.column, mapOf("action" to "SONAR")) },
                enabled = enabled && used < 3,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("◉ Sonar 3×3 · ${3 - used} pulsos", fontWeight = FontWeight.Black) }
        }
        GameType.MERGE_2048 -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "Desliza el tablero o usa los controles · Meta ${state.genericBoard?.meta?.get("target") ?: 256}",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF102A56),
                )
                Button({ onMoveAt(0, 0, "UP") }, enabled = enabled) { Text("↑") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onMoveAt(0, 0, "LEFT") }, enabled = enabled) { Text("←") }
                    Button({ onMoveAt(0, 0, "DOWN") }, enabled = enabled) { Text("↓") }
                    Button({ onMoveAt(0, 0, "RIGHT") }, enabled = enabled) { Text("→") }
                }
            }
        }
        GameType.TOWER_DEFENSE -> {
            var tower by remember { mutableStateOf("RAPID") }
            val credits = ((state.genericBoard?.meta?.get("credits") as? Map<*, *>)?.get(state.playerId) as? Number)?.toInt() ?: 0
            val wave = (state.genericBoard?.meta?.get("wave") as? Number)?.toInt() ?: 0
            val health = (state.genericBoard?.meta?.get("baseHealth") as? Number)?.toInt() ?: 20
            val waveActive = state.genericBoard?.meta?.get("waveActive") == true
            val remaining = (state.genericBoard?.meta?.get("remainingEnemies") as? Number)?.toInt() ?: 0
            val selectedCell = state.selected?.let { state.genericBoard?.board?.getOrNull(it.row)?.getOrNull(it.column) }
            val selectedTower = selectedCell?.meta?.get("towerType")?.toString()
            val towerDescription = when (tower) {
                "BLAST" -> "Daño de área · alcance medio · ideal contra grupos"
                "SNIPER" -> "Gran alcance · impacto pesado · cadencia lenta"
                "FROST" -> "Ralentiza tropas · prepara combos para el equipo"
                else -> "Cadencia alta · económica · excelente al inicio"
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("◆ NÚCLEO $health", color = if (health <= 6) Color(0xFFDC2626) else Color(0xFF059669), fontWeight = FontWeight.Black)
                    Text("OLEADA $wave/20", color = Color(0xFF7C3AED), fontWeight = FontWeight.Black)
                    Text("◈ $credits", color = Color(0xFF0369A1), fontWeight = FontWeight.Black)
                }
                if (waveActive) {
                    LinearProgressIndicator(
                        progress = { (1f - remaining / (5f + wave * 2f)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = Color(0xFFE11D48),
                        trackColor = Color(0xFFE2E8F0),
                    )
                    Text("⚔ $remaining tropas siguen en el circuito", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("RAPID" to "⚡100", "BLAST" to "💥150", "SNIPER" to "◎180", "FROST" to "❄130").forEach { (id, label) ->
                        OutlinedButton(
                            onClick = { tower = id },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(
                                if (tower == id) 2.dp else 1.dp,
                                if (tower == id) Color(0xFF7C3AED) else Color(0xFF94A3B8),
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (tower == id) towerColorFor(id).copy(alpha = .16f) else Color.Transparent,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                        ) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                    }
                }
                Surface(
                    color = towerColorFor(tower).copy(alpha = .10f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, towerColorFor(tower).copy(alpha = .42f)),
                ) {
                    Text(towerDescription, Modifier.fillMaxWidth().padding(7.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val target = state.selected ?: return@Button
                        if (selectedTower != null) onMoveAt(target.row, target.column, mapOf("action" to "UPGRADE"))
                        else onMoveAt(target.row, target.column, mapOf("action" to "BUILD", "towerType" to tower))
                    },
                    enabled = enabled && state.selected != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (selectedTower != null) "⬆ MEJORAR $selectedTower" else "＋ CONSTRUIR EN EL NODO",
                        fontWeight = FontWeight.Black,
                    )
                }
                Button(
                    onClick = { onMoveAt(0, 0, mapOf("action" to "START_WAVE")) },
                    enabled = enabled && health > 0 && wave < 20 && !waveActive,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                ) { Text(if (waveActive) "⚔ COMBATE EN CURSO" else "▶ LANZAR OLEADA", fontWeight = FontWeight.Black) }
                Text("HABILIDADES DE ARENA", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color(0xFF475569))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        Triple("EMP", "⚡ EMP", 180), Triple("ORBITAL", "☄ Orbital", 280),
                        Triple("REPAIR", "♥ Reparar", 220), Triple("OVERCLOCK", "⏩ Turbo", 200),
                    ).forEach { (action, label, cost) ->
                        OutlinedButton(
                            onClick = { onMoveAt(0, 0, mapOf("action" to action)) },
                            enabled = enabled && credits >= cost && (action == "REPAIR" || waveActive),
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 3.dp),
                        ) { Text("$label\n$cost", fontSize = 8.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
        GameType.TIC_TAC_TOE -> {
            val target = state.selected
            val used = ((state.genericBoard?.meta?.get("ticUsedPowers") as? Map<*, *>)?.get(state.playerId) as? List<*>)
                ?.map { it.toString() }?.toSet().orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Selecciona una ficha y usa una carta, o toca una casilla vacía para jugar.", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("PUSH" to "⇢ Empujón", "SHIELD" to "◈ Escudo", "BOMB" to "✹ Bomba").forEach { (action, label) ->
                        OutlinedButton(
                            onClick = { target?.let { onMoveAt(it.row, it.column, mapOf("action" to action)) } },
                            enabled = enabled && target != null && action !in used,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) { Text(if (action in used) "✓ Usado" else label, fontSize = 9.sp, fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
        GameType.HANGMAN -> {
            val letters = ('A'..'Z').map(Char::toString) + "Ñ"
            val guessed = (state.genericBoard?.meta?.get("guessedLetters") as? List<*>)?.mapNotNull { it?.toString() }?.toSet().orEmpty()
            val correct = (state.genericBoard?.meta?.get("correctGuesses") as? List<*>)?.mapNotNull { it?.toString() }?.toSet().orEmpty()
            val wrong = (state.genericBoard?.meta?.get("wrongGuesses") as? List<*>)?.mapNotNull { it?.toString() }?.toSet().orEmpty()
            val discarded = ((state.genericBoard?.meta?.get("discardedByPlayer") as? Map<*, *>)?.get(state.playerId) as? List<*>)
                ?.mapNotNull { it?.toString() }?.toSet().orEmpty()
            val revealUsed = (state.genericBoard?.meta?.get("revealUsed") as? List<*>)?.any { it?.toString() == state.playerId } == true
            val discardUsed = (state.genericBoard?.meta?.get("discardUsed") as? List<*>)?.any { it?.toString() == state.playerId } == true
            val lastBreathUsed = (state.genericBoard?.meta?.get("lastBreathUsed") as? List<*>)?.any { it?.toString() == state.playerId } == true
            val targetColumn = state.genericBoard?.board?.firstOrNull()
                ?.indexOfFirst { it.value == null }
                ?.coerceAtLeast(0) ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        { onMoveAt(0, targetColumn, mapOf("action" to "REVEAL")) },
                        enabled = enabled && !revealUsed,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(if (revealUsed) "✓ Revelación" else "💡 Revelación", fontSize = 11.sp, fontWeight = FontWeight.Black) }
                    Button(
                        { onMoveAt(0, targetColumn, mapOf("action" to "DISCARD")) },
                        enabled = enabled && !discardUsed,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(if (discardUsed) "✓ Descarte" else "⌫ Descartar 3", fontSize = 11.sp, fontWeight = FontWeight.Black) }
                }
                Text(
                    if (lastBreathUsed) "Último Aliento consumido" else "♥ Último Aliento disponible",
                    fontSize = 11.sp,
                    color = if (lastBreathUsed) Color.Gray else Color(0xFF00875A),
                    fontWeight = FontWeight.Bold,
                )
                letters.chunked(9).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { letter ->
                            Button(
                                { onMoveAt(0, targetColumn, letter) },
                                enabled = enabled && letter !in guessed && letter !in discarded,
                                modifier = Modifier.weight(1f).height(44.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = when {
                                        letter in correct -> Color(0xFF22C55E)
                                        letter in wrong -> Color(0xFFEF4444)
                                        letter in discarded -> Color(0xFF475569)
                                        else -> Color(0xFFCBD5E1)
                                    },
                                    disabledContentColor = Color.White,
                                ),
                            ) { Text(letter, fontSize = 11.sp) }
                        }
                        repeat(9 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        GameType.WORD_SEARCH -> Text("Arrastra desde la primera hasta la última letra de una palabra.")
        GameType.CROSSWORD -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.uppercase().take(1) }, label = { Text("Letra") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(4.dp))
            Button({ onMove(text); text = "" }, enabled = enabled && text.isNotBlank()) { Text("Colocar") }
        }
        GameType.CROSS_LETTERS -> {
            var vertical by remember { mutableStateOf(false) }
            var chosen by remember(state.letterRack) { mutableStateOf(emptyList<Int>()) }
            val normalized = chosen.mapNotNull(state.letterRack::getOrNull).joinToString("")
            val selectedCell = state.selected
            val board = state.genericBoard?.board.orEmpty()
            val coordinates = remember(normalized, vertical, selectedCell, board) {
                normalized.mapIndexed { index, letter ->
                    Triple((selectedCell?.row ?: -1) + if (vertical) index else 0, (selectedCell?.column ?: -1) + if (vertical) 0 else index, letter)
                }
            }
            val inBounds = coordinates.all { (row, col, _) -> board.getOrNull(row)?.getOrNull(col) != null }
            val compatible = inBounds && coordinates.all { (row, col, letter) ->
                board[row][col].value == null || board[row][col].value.toString() == letter.toString()
            }
            val connected = coordinates.any { (row, col, letter) -> board.getOrNull(row)?.getOrNull(col)?.value?.toString() == letter.toString() }
            val previewValid = normalized.length >= 2 && selectedCell != null && inBounds && compatible && connected
            val letterScores = mapOf('A' to 1, 'B' to 3, 'C' to 3, 'D' to 2, 'E' to 1, 'F' to 4, 'G' to 2, 'H' to 4, 'I' to 1, 'J' to 8, 'L' to 1, 'M' to 3, 'N' to 1, 'Ñ' to 8, 'O' to 1, 'P' to 3, 'Q' to 5, 'R' to 1, 'S' to 1, 'T' to 1, 'U' to 1, 'V' to 4, 'X' to 8, 'Y' to 4, 'Z' to 10)
            val estimatedPoints = if (!inBounds) 0 else coordinates.sumOf { (row, col, letter) ->
                val bonus = board[row][col].meta["bonus"]?.toString()
                (letterScores[letter] ?: 1) * when (bonus) { "DL" -> 2; "TL" -> 3; else -> 1 }
            } * coordinates.fold(1) { multiplier, (row, col, _) ->
                multiplier * when (board.getOrNull(row)?.getOrNull(col)?.meta?.get("bonus")?.toString()) { "DW" -> 2; "TW" -> 3; else -> 1 }
            }
            val haptics = LocalHapticFeedback.current
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (selectedCell == null) "1. Toca la casilla donde comenzará la palabra"
                    else "Inicio ${selectedCell.row + 1},${selectedCell.column + 1} · toca las fichas en orden",
                    fontWeight = FontWeight.Black,
                    color = if (selectedCell == null) Color(0xFFD97706) else Color(0xFF047857),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.letterRack.forEachIndexed { index, letter ->
                        val used = index in chosen
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (used) Color(0xFF94A3B8) else Color(0xFFFFF4C2),
                            border = androidx.compose.foundation.BorderStroke(
                                2.dp,
                                if (used) Color(0xFF475569) else Color(0xFF7A4E00),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clickable(enabled = enabled && !used) { chosen = chosen + index },
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(letter, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
                Surface(
                    Modifier.fillMaxWidth().height(48.dp),
                    color = when { normalized.isBlank() -> Color(0xFFEFF6FF); previewValid -> Color(0xFFDCFCE7); else -> Color(0xFFFEE2E2) },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, if (previewValid) Color(0xFF16A34A) else if (normalized.isBlank()) Color(0xFF2563EB) else Color(0xFFDC2626)),
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            normalized.ifBlank { "FORMA TU PALABRA" },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF102A56),
                        )
                        if (normalized.isNotBlank()) Text("â‰ˆ $estimatedPoints pts", color = if (previewValid) Color(0xFF15803D) else Color(0xFFB91C1C), fontWeight = FontWeight.Black)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ vertical = false }, modifier = Modifier.weight(1f)) { Text(if (!vertical) "✓ Horizontal" else "Horizontal") }
                    OutlinedButton({ vertical = true }, modifier = Modifier.weight(1f)) { Text(if (vertical) "✓ Vertical" else "Vertical") }
                    OutlinedButton(
                        { chosen = chosen.dropLast(1) },
                        enabled = chosen.isNotEmpty(),
                        modifier = Modifier.weight(.65f),
                    ) { Text("⌫") }
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(if (estimatedPoints >= 40) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                            onMove(mapOf("word" to normalized, "direction" to if (vertical) "V" else "H"))
                            chosen = emptyList()
                        },
                        enabled = enabled && previewValid,
                        modifier = Modifier.weight(1f),
                    ) { Text("JUGAR", fontWeight = FontWeight.Black) }
                }
                Text(
                    if (previewValid) "âœ“ ConexiÃ³n vÃ¡lida. Blitz: juega ahora para conquistar esas casillas."
                    else "Tap & Write: toca origen, direcciÃ³n y luego las letras. Verde = colocaciÃ³n conectada; rojo = ajusta origen o direcciÃ³n.",
                    fontSize = 11.sp,
                    color = Color(0xFF475569),
                )
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
        GameType.CHESS_TACTICS -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Chess Tactics RPG", fontWeight = FontWeight.Black)
                Text("Toca tu unidad: azul permite moverse y rojo atacar. Mantén pulsada una unidad para activar Carga del Peón, Terremoto o Rayo Perforante.")
            }
        }
        else -> {
            val values = when (gameType) {
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

private fun towerColorFor(type: String): Color = when (type) {
    "BLAST" -> Color(0xFFFB923C)
    "SNIPER" -> Color(0xFFC084FC)
    "FROST" -> Color(0xFF22D3EE)
    else -> Color(0xFF60A5FA)
}

private fun mergeTileColor(value: Int): Color = when (value) {
    0 -> Color(0xFFE8EEF7)
    2 -> Color(0xFFE0F2FE)
    4 -> Color(0xFFBAE6FD)
    8 -> Color(0xFF67E8F9)
    16 -> Color(0xFF22D3EE)
    32 -> Color(0xFF38BDF8)
    64 -> Color(0xFF6366F1)
    128 -> Color(0xFF8B5CF6)
    256 -> Color(0xFFD946EF)
    512 -> Color(0xFFEC4899)
    else -> Color(0xFFF59E0B)
}

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
    GameType.CROSSWORD -> "Multi Arena · Crucigramas"; GameType.TIC_TAC_TOE -> "Multi Arena · El Gato"; GameType.DOTS_AND_BOXES -> "Multi Arena · Timbiriche"
    GameType.KAKURO -> "Multi Arena · Kakuro"; GameType.MATHDOKU -> "Multi Arena · Mathdoku"; GameType.HITORI -> "Multi Arena · Hitori"; GameType.CHESS_TACTICS -> "Multi Arena · Chess Tactics RPG"
    GameType.NURIKABE -> "Multi Arena · Nurikabe"; GameType.BRIDGES -> "Multi Arena · Bridges"
    GameType.TETRIS_ARENA -> "Multi Arena · Tetris Arena"
    GameType.HANGMAN -> "Multi Arena · El Ahorcado"
    GameType.ARROWS_ESCAPE -> "Multi Arena · Flechas en Fuga"
    GameType.PACMAN_ARENA -> "Multi Arena · Pac-Man Arena"
    GameType.CROSS_LETTERS -> "Multi Arena · Letras Cruzadas"
    GameType.SECRET_CODE -> "Multi Arena · Código Secreto"
    GameType.CAPITAL_ARENA -> "Multi Arena · Capital Arena"
    GameType.NEXUS_ZERO -> "Multi Arena · Nexo Cero"
    GameType.CHECKERS -> "Multi Arena · Damas Clásicas"
    GameType.DEMOLITION_ARCADE -> "Multi Arena · Demolición Arcade"
    GameType.MEMORY_NEON -> "Multi Arena · Memoria Neón"
    GameType.MERGE_2048 -> "Multi Arena · 2048 Arena"
    GameType.TOWER_DEFENSE -> "Multi Arena · Defensa de Torres"
    GameType.REACTOR_CHAIN -> "Multi Arena · Reactor Chain"
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
