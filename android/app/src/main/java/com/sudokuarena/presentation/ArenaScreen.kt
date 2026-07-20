package com.sudokuarena.presentation

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.BoardEventType
import com.sudokuarena.domain.Player
import com.sudokuarena.domain.RoomPhase
import com.sudokuarena.domain.TeamMode
import com.sudokuarena.domain.GameType
import com.sudokuarena.audio.GameSound
import com.sudokuarena.audio.GlobalAudioManager
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
private fun BoardErrorScreen(detail: String?, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFFF7F9FF)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            shadowElevation = 14.dp,
            border = BorderStroke(2.dp, Color(0xFFFF5577)),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("⚠", fontSize = 46.sp)
                Text("Error al cargar el tablero", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                if (!detail.isNullOrBlank()) Text(detail, color = Color(0xFF526581))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Reintentar") }
                TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Volver al menú") }
            }
        }
    }
}

@Composable
fun ArenaRoute(viewModel: ArenaViewModel, onExit: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val haptics = remember(context) { HapticFeedbackController(context) }
    LaunchedEffect(viewModel, haptics) {
        viewModel.haptics.collect { cue ->
            haptics.play(cue)
            GlobalAudioManager.play(
                when (cue) {
                    HapticCue.CLICK -> GameSound.CLICK
                    HapticCue.CRESCENDO -> GameSound.SUCCESS
                    HapticCue.DANGER -> GameSound.DANGER
                },
            )
        }
    }

    if (state.boardError != null) {
        BoardErrorScreen(
            detail = state.message,
            onRetry = viewModel::retryBoardLoad,
            onExit = onExit,
        )
        return
    }

    val roomState = state.roomState
    if (!state.isSoloMode && (roomState == null || roomState.phase == RoomPhase.LOBBY)) {
        RoomLobbyScreen(
            state = state,
            onPowersChanged = viewModel::setPowersEnabled,
            onTeamModeChanged = viewModel::setTeamMode,
            onTileTypeChanged = viewModel::setTileType,
            onBotDifficultyChanged = viewModel::setBotDifficulty,
            onPuzzleDifficultyChanged = viewModel::setPuzzleDifficulty,
            onGameTypeChanged = viewModel::setGameType,
            onLoadoutPower = viewModel::toggleLoadoutPower,
            onFillWithAi = viewModel::fillWithAi,
            onStart = viewModel::startOnlineMatch,
            onExit = onExit,
        )
        return
    }

    if (!state.isSoloMode && roomState?.phase == RoomPhase.RPS) {
        RpsStartScreen(state, viewModel::chooseRps, onExit)
        return
    }

    if (state.gameType == GameType.TETRIS_ARENA) {
        ArcadeGameHost(
            state, viewModel::resumePausedGame, viewModel::newSoloGame,
            viewModel::requestRematch, viewModel::sendGlobalChat, onExit,
        ) { requestExit ->
            TetrisArenaScreen(state, viewModel::sendTetrisInput, viewModel::requestPause, requestExit)
        }
        return
    }

    if (state.gameType == GameType.PACMAN_ARENA) {
        ArcadeGameHost(
            state, viewModel::resumePausedGame, viewModel::newSoloGame,
            viewModel::requestRematch, viewModel::sendGlobalChat, onExit,
        ) { requestExit ->
            PacmanArenaScreen(state, viewModel::sendPacmanInput, viewModel::requestPause, requestExit)
        }
        return
    }

    if (state.gameType == GameType.DEMOLITION_ARCADE) {
        ArcadeGameHost(
            state, viewModel::resumePausedGame, viewModel::newSoloGame,
            viewModel::requestRematch, viewModel::sendGlobalChat, onExit,
        ) { requestExit ->
            DemolitionArenaScreen(state, viewModel::sendDemolitionInput, viewModel::requestPause, requestExit)
        }
        return
    }

    if (state.gameType != GameType.SUDOKU) {
        GenericArenaScreen(
            state = state,
            onCellSelected = viewModel::selectGeneric,
            onMove = viewModel::makeGenericMove,
            onMoveAt = viewModel::makeGenericMoveAt,
            onWordSelection = viewModel::makeWordSelection,
            onUseFog = viewModel::useFog,
            onUseReflect = viewModel::useReflect,
            onUseReveal = viewModel::useReveal,
            onFogSwipe = viewModel::cleanFogSwipe,
            onRematch = viewModel::requestRematch,
            onRequestPause = viewModel::requestPause,
            onPauseResponse = viewModel::respondPause,
            onResume = viewModel::resumePausedGame,
            onTutorialComplete = viewModel::completeTutorial,
            onOpenTutorial = viewModel::openTutorial,
            onReaction = viewModel::sendReaction,
            onSecretChat = viewModel::sendSecretChat,
            onGlobalChat = viewModel::sendGlobalChat,
            onNewSoloGame = viewModel::newSoloGame,
            onExit = onExit,
        )
        return
    }

    ArenaScreen(
        state = state,
        onCellSelected = viewModel::select,
        onNumber = viewModel::place,
            onUseFog = viewModel::useFog,
            onUseReflect = viewModel::useReflect,
            onUseReveal = viewModel::useReveal,
        onReaction = viewModel::sendReaction,
        onGlobalChat = viewModel::sendGlobalChat,
        onFogSwipe = viewModel::cleanFogSwipe,
        onNewSoloGame = viewModel::newSoloGame,
        onTutorialComplete = viewModel::completeTutorial,
        onOpenTutorial = viewModel::openTutorial,
        onRematch = viewModel::requestRematch,
        onRequestPause = viewModel::requestPause,
        onPauseResponse = viewModel::respondPause,
        onResume = viewModel::resumePausedGame,
        onExit = onExit,
    )
}

@Composable
private fun ArcadeGameHost(
    state: ArenaUiState,
    onResume: () -> Unit,
    onNewSoloGame: () -> Unit,
    onRematch: () -> Unit,
    onGlobalChat: (String) -> Unit,
    onExit: () -> Unit,
    content: @Composable (requestExit: () -> Unit) -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().blur(if (state.isLocallyPaused) 18.dp else 0.dp)) {
            content { confirmExit = true }
        }
        if (!state.isSoloMode) {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).zIndex(20f),
                color = Color.White.copy(alpha = .96f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 10.dp,
            ) {
                if (showChat) {
                    Column(Modifier.widthIn(max = 320.dp).padding(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Chat de Arena", Modifier.weight(1f), fontWeight = FontWeight.Black)
                            TextButton(onClick = { showChat = false }) { Text("Cerrar") }
                        }
                        GlobalGameChat(state, onGlobalChat)
                    }
                } else {
                    TextButton(onClick = { showChat = true }) {
                        Text("💬 ${state.globalChat.size}", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        if (state.soloCompleted || state.matchResults.isNotEmpty()) {
            MatchResultsOverlay(
                state,
                onNewSoloGame = onNewSoloGame,
                onRematch = onRematch,
                onExit = onExit,
                modifier = Modifier.zIndex(30f),
            )
        }
        if (state.isLocallyPaused) PauseLayer(state, onResume, Modifier.zIndex(50f))
        ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
    }
}

@Composable
fun ArenaScreen(
    state: ArenaUiState,
    onCellSelected: (row: Int, column: Int) -> Unit,
    onNumber: (Int) -> Unit,
    onUseFog: (String) -> Unit,
    onUseReflect: () -> Unit,
    onUseReveal: () -> Unit,
    onReaction: (String) -> Unit,
    onGlobalChat: (String) -> Unit,
    onFogSwipe: () -> Unit,
    onNewSoloGame: () -> Unit,
    onTutorialComplete: () -> Unit,
    onOpenTutorial: () -> Unit,
    onRematch: () -> Unit,
    onRequestPause: () -> Unit,
    onPauseResponse: (Boolean) -> Unit,
    onResume: () -> Unit,
    onExit: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { confirmExit = true }
    val shake = remember { Animatable(0f) }
    val boardPulse = remember { Animatable(1f) }
    val screenFx = rememberGameFxController()
    var previousScore by remember(state.playerId) { mutableStateOf(state.ownPlayer?.score ?: 0) }
    var previousConquest by remember(state.playerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.ownPlayer?.score, state.conquestMessage, state.comboMessage) {
        val score = state.ownPlayer?.score ?: previousScore
        if (score > previousScore) {
            val gained = score - previousScore
            screenFx.emit(if (gained >= 40) GameFxKind.COMBO else GameFxKind.SUCCESS, x = .5f, y = .38f, text = "+$gained", intensity = if (gained >= 40) 1.35f else .8f)
        }
        previousScore = score
        state.conquestMessage?.takeIf { it != previousConquest }?.let {
            screenFx.emit(GameFxKind.MAGIC, x = .5f, y = .5f, text = "CONQUISTA", intensity = 1.45f)
            previousConquest = it
        }
    }
    LaunchedEffect(state.penaltyRemainingMs > 0) {
        if (state.penaltyRemainingMs > 0) {
            repeat(7) { index -> shake.animateTo(if (index % 2 == 0) 13f else -13f, tween(42)) }
            shake.animateTo(0f, tween(55))
        }
    }
    LaunchedEffect(state.revision) {
        if (state.revision > 0) {
            boardPulse.snapTo(.965f)
            boardPulse.animateTo(1f, tween(190))
        }
    }
    Scaffold(
        topBar = {
            PinnedGameHeader(
                title = "Multi Arena · Sudoku",
                subtitle = null,
                state = state,
                onTutorial = onOpenTutorial,
                onPause = onRequestPause,
                onExit = { confirmExit = true },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            GameAtmosphere(GameType.SUDOKU, Modifier.fillMaxSize())
            AdaptiveArenaLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(if (state.isLocallyPaused || state.roomState?.phase == RoomPhase.PAUSED) 18.dp else 0.dp),
                board = {
                BoxWithConstraints(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val boardSize = minOf(maxWidth, maxHeight)
                    Box(Modifier.size(boardSize), contentAlignment = Alignment.Center) {
                        SudokuBoard(
                            board = state.board,
                            isColorMode = state.isColorMode,
                            players = state.players.associateBy { it.id },
                            selected = state.selected,
                            enabled = state.penaltyRemainingMs == 0L && state.fogSwipesRemaining == 0,
                            onCellSelected = onCellSelected,
                            modifier = Modifier
                                .offset { IntOffset(shake.value.roundToInt(), 0) }
                                .graphicsLayer {
                                    scaleX = boardPulse.value
                                    scaleY = boardPulse.value
                                    alpha = .78f + boardPulse.value * .22f
                                },
                        )
                    }
                }
                },
                controls = {
                    PauseVoteBanner(state, onPauseResponse)
                    Text(
                        when {
                            state.roomState?.suddenDeath == true -> "MUERTE SÚBITA · próximo acierto gana"
                            state.isSoloMode -> "Solitario · ${if (state.isColorMode) "Colores" else "Números"} · ${formatDuration(state.soloElapsedMs)}"
                            state.connected && state.roomCode != null -> "Sala ${state.roomCode} · ${formatDuration(state.matchRemainingMs)}"
                            else -> "Conectando…"
                        },
                        color = if (state.connected || state.isSoloMode) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    )
                    if (!state.isSoloMode) BoardEventBanner(state)
                    Scoreboard(state)
                    if (!state.isSoloMode && state.roomState?.config?.powersEnabled == true) {
                        PowerPanel(state, onUseFog, onUseReflect, onUseReveal)
                    }
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
                        Text(
                            when {
                                !state.isSoloMode && state.players.size < 2 -> "Comparte ${state.roomCode.orEmpty()} y espera rival"
                                state.selected != null -> "Casilla ${state.selected.row + 1}, ${state.selected.column + 1}"
                                else -> "Selecciona una casilla"
                            },
                        )
                    }
                    TilePad(enabled = state.canPlay, isColorMode = state.isColorMode, onTile = onNumber)
                    if (!state.isSoloMode) {
                        ReactionMenu(onReaction)
                        GlobalGameChat(state, onGlobalChat)
                    }
                }
            )

            GameFxOverlay(screenFx, Modifier.fillMaxSize().zIndex(9f))

            if ((state.ownPlayer?.shieldUntil ?: 0) > state.serverNowMs) {
                ShieldAuraOverlay(Modifier.zIndex(8f))
            }
            if (state.fogSwipesRemaining > 0) {
                FogInkOverlay(
                    swipesRemaining = state.fogSwipesRemaining,
                    onValidSwipe = onFogSwipe,
                    modifier = Modifier.zIndex(20f),
                )
            }
            if (state.penaltyRemainingMs > 0) {
                PenaltyOverlay(
                    remainingMs = state.penaltyRemainingMs,
                    modifier = Modifier.zIndex(15f),
                )
            }
            if (state.soloCompleted || state.matchResults.isNotEmpty()) {
                MatchResultsOverlay(
                    state = state,
                    onNewSoloGame = onNewSoloGame,
                    onRematch = onRematch,
                    onExit = onExit,
                    modifier = Modifier.zIndex(30f),
                )
            }
            state.comboMessage?.let { combo ->
                Text(
                    combo,
                    color = Color(0xFFFF8F00),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.Center).zIndex(32f),
                )
            }
            if (state.showTutorial) {
                ArenaTutorialOverlay(
                    isSoloMode = state.isSoloMode,
                    gameType = state.gameType,
                    onFinished = onTutorialComplete,
                    modifier = Modifier.zIndex(40f),
                )
            }
            if (state.isLocallyPaused || state.roomState?.phase == RoomPhase.PAUSED) {
                PauseLayer(state, onResume, Modifier.zIndex(50f))
            }
            ConfirmExitDialog(confirmExit, onDismiss = { confirmExit = false }, onConfirm = onExit)
        }
    }
}

@Composable
fun ShieldAuraOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shieldAura")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "shieldAuraPulse",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            color = Color(0xFF7C3AED).copy(alpha = 0.24f + pulse * 0.28f),
            style = Stroke(width = 8f + pulse * 7f),
        )
        drawCircle(
            color = Color(0xFF00A8FF).copy(alpha = 0.05f + pulse * 0.05f),
            radius = size.minDimension * (0.48f + pulse * 0.05f),
            center = center,
            style = Stroke(width = 10f),
        )
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
fun Scoreboard(state: ArenaUiState) {
    val columns = if (state.players.size > 2) 2 else state.players.size.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.players.chunked(columns).forEach { rowPlayers ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowPlayers.forEach { player ->
            val color = parseColor(player.colorHex)
            val shieldActive = player.shieldUntil > state.serverNowMs
            Box(modifier = Modifier.weight(1f)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { shadowElevation = if (shieldActive) 18f else 2f },
                    border = if (shieldActive) BorderStroke(3.dp, Color(0xFF7C3AED)) else null,
                ) {
                    Column(
                        Modifier
                            .background(color.copy(alpha = if (player.id == state.playerId) 0.28f else 0.13f))
                            .padding(7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            AvatarBadge(player.avatarId, color, 26.dp)
                            Text(player.name, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                        Text("${player.score} pts", style = MaterialTheme.typography.titleMedium)
                        if (player.comboMultiplier > 1) {
                            Text("COMBO x${player.comboMultiplier}", color = Color(0xFFE65100), fontWeight = FontWeight.Black)
                        }
                        player.botPersona?.let { Text(botPersonaLabel(it), style = MaterialTheme.typography.labelSmall) }
                        if (!state.isSoloMode && state.roomState?.config?.powersEnabled == true) {
                            Text("⚡ ${player.energy}%", style = MaterialTheme.typography.labelSmall)
                            if (shieldActive) Text("🛡 ESCUDO", color = Color(0xFF6D28D9), fontWeight = FontWeight.Black)
                        }
                        if (!state.isSoloMode && state.roomState?.config?.teamMode !in setOf(TeamMode.FFA, TeamMode.DUEL)) {
                            Text("Equipo ${player.teamScore}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
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
                if (rowPlayers.size < columns) Spacer(Modifier.weight(1f))
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
fun PowerPanel(
    state: ArenaUiState,
    onUseFog: (String) -> Unit,
    onUseReflect: () -> Unit,
    onUseReveal: () -> Unit,
) {
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
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onUseReflect,
                enabled = "REFLECT" in ownPlayer.powerLoadout && ownPlayer.energy >= 100 && ownPlayer.shieldUntil <= state.serverNowMs,
                modifier = Modifier.weight(1f),
            ) { Text("🛡 Escudo · 100%", maxLines = 1) }
            Button(
                onClick = onUseReveal,
                enabled = "REVEAL" in ownPlayer.powerLoadout && ownPlayer.energy >= 50 && state.selected != null,
                modifier = Modifier.weight(1f),
            ) { Text("👁 Ojo · 50%", maxLines = 1) }
        }
        if (ownPlayer.energy >= 100 && "FOG" in ownPlayer.powerLoadout) {
            Text("Niebla ofensiva", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.players.filter { target ->
                    target.id != state.playerId &&
                        (state.roomState?.config?.teamMode == TeamMode.FFA || target.teamId != ownPlayer.teamId)
                }.forEach { target ->
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
    isColorMode: Boolean,
    players: Map<String, Player>,
    selected: CellPosition?,
    enabled: Boolean,
    onCellSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val hasClearingCells = board.any { row -> row.any(BoardCell::clearing) }
    val burstScale by animateFloatAsState(
        targetValue = if (hasClearingCells) 1.45f else 1f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "sectionBurst",
    )

    Canvas(
        modifier = modifier
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
            // La conquista muestra al jugador exacto, incluso cuando el puntaje es de equipo.
            val ownerColor = cell.ownerId?.let { players[it]?.colorHex }?.let(::parseColor)
                ?: cell.ownerTeamId?.let(::teamColor)
            val background = when {
                selected == CellPosition(row, column) -> Color(0xFFFFF59D)
                cell.golden -> Color(0xFFFFD54F)
                ownerColor != null -> ownerColor.copy(alpha = if (cell.clearing) 0.48f else 0.24f)
                cell.clearing -> Color.LightGray
                else -> Color.Transparent
            }
            drawRect(background, topLeft, Size(cellSize, cellSize))
            if (ownerColor != null) {
                drawRect(
                    ownerColor.copy(alpha = 0.82f),
                    topLeft,
                    Size(cellSize, cellSize),
                    style = Stroke(width = 2.2f),
                )
            }
            if (cell.golden) {
                drawRect(Color(0xFFFFA000), topLeft, Size(cellSize, cellSize), style = Stroke(width = 4f))
            }

            cell.value?.let { value ->
                val scale = if (cell.clearing) burstScale else 1f
                if (isColorMode) {
                    val center = Offset(topLeft.x + cellSize / 2f, topLeft.y + cellSize / 2f)
                    val radius = cellSize * 0.30f * scale
                    drawCircle(Color.Black.copy(alpha = 0.50f), radius = radius + 2.2f, center = center)
                    drawCircle(SudokuTilePalette.colorFor(value), radius = radius, center = center)
                    drawCircle(Color.White.copy(alpha = 0.72f), radius = radius, center = center, style = Stroke(1.4f))
                } else {
                    val layout = textMeasurer.measure(
                        value.toString(),
                        TextStyle(
                            color = when {
                                cell.clearing -> Color.Black.copy(alpha = 0.72f)
                                cell.given -> Color(0xFF263238)
                                else -> Color(0xFF1565C0)
                            },
                            fontSize = 22.sp * scale,
                            fontWeight = if (cell.golden || cell.given) FontWeight.Bold else FontWeight.Normal,
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
fun PenaltyOverlay(remainingMs: Long, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "penaltyBlink")
    val blink by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(tween(220), RepeatMode.Reverse),
        label = "penaltyAlpha",
    )
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Red.copy(alpha = blink)) }
        Surface(
            color = Color(0xDD7F1010),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("ERROR · SISTEMA CONGELADO", color = Color.White, fontWeight = FontWeight.Black)
                Text("${(remainingMs + 999) / 1_000}", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun ReactionMenu(onReaction: (String) -> Unit) {
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
fun FogInkOverlay(
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
private fun TilePad(enabled: Boolean, isColorMode: Boolean, onTile: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (0..2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..3).forEach { column ->
                    val number = row * 3 + column
                    Button(
                        onClick = { onTile(number) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isColorMode) {
                            ColorTile(SudokuTilePalette.colorFor(number), Modifier.size(28.dp))
                        } else {
                            Text(number.toString())
                        }
                    }
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

private fun teamColor(teamId: String): Color = when (teamId) {
    "TEAM_A", "RAIDERS" -> Color(0xFF1E88E5)
    "TEAM_B" -> Color(0xFFE53935)
    "BOSS" -> Color(0xFFFF8F00)
    else -> Color.Gray
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun botPersonaLabel(persona: String): String = when (persona) {
    "CALCULATOR" -> "Preciso"
    "TRICKSTER" -> "Tramposo"
    "GUARDIAN" -> "Guardián"
    else -> "Bot"
}
