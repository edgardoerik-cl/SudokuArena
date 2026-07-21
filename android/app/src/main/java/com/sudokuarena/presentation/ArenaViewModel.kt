package com.sudokuarena.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sudokuarena.domain.ActiveBoardEvent
import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.ConqueredSection
import com.sudokuarena.domain.GameRealtimeGateway
import com.sudokuarena.domain.GameSnapshot
import com.sudokuarena.domain.GameChatMessage
import com.sudokuarena.domain.Player
import com.sudokuarena.domain.PlayerRecordStore
import com.sudokuarena.domain.RealtimeEvent
import com.sudokuarena.domain.RoomConfig
import com.sudokuarena.domain.RoomPhase
import com.sudokuarena.domain.RoomState
import com.sudokuarena.domain.TeamMode
import com.sudokuarena.domain.TileType
import com.sudokuarena.domain.BotDifficulty
import com.sudokuarena.domain.MatchResultEntry
import com.sudokuarena.domain.GameType
import com.sudokuarena.domain.PuzzleDifficulty
import com.sudokuarena.domain.GenericBoardState
import com.sudokuarena.domain.LeaderboardRepository
import com.sudokuarena.domain.SudokuGenerator
import com.sudokuarena.domain.SudokuPuzzle
import com.sudokuarena.data.local.LocalPuzzleEngine
import com.sudokuarena.data.local.LocalTetrisEngine
import com.sudokuarena.data.local.LocalPacmanEngine
import com.sudokuarena.data.local.LocalDemolitionEngine
import java.util.UUID
import java.time.LocalDate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class CellPosition(val row: Int, val column: Int)

enum class HapticCue { CLICK, DANGER, CRESCENDO }

data class ReactionUi(val reactionId: String, val emojiId: String)
data class SecretChatUi(val playerId: String, val message: String, val penalized: Boolean)

data class ArenaUiState(
    val isSoloMode: Boolean = false,
    val isColorMode: Boolean = false,
    val connected: Boolean = false,
    val serverNowMs: Long = System.currentTimeMillis(),
    val roomCode: String? = null,
    val roomState: RoomState? = null,
    val matchRemainingMs: Long = 0,
    val matchResults: List<MatchResultEntry> = emptyList(),
    val playerId: String? = null,
    val revision: Long = 0,
    val board: List<List<BoardCell>> = emptyBoard(),
    val players: List<Player> = emptyList(),
    val selected: CellPosition? = null,
    val pendingRequestId: String? = null,
    val penaltyRemainingMs: Long = 0,
    val boardEvent: ActiveBoardEvent? = null,
    val boardEventRemainingMs: Long = 0,
    val fogSwipesRemaining: Int = 0,
    val reactions: Map<String, ReactionUi> = emptyMap(),
    val conquestMessage: String? = null,
    val soloElapsedMs: Long = 0,
    val soloErrors: Int = 0,
    val soloCompleted: Boolean = false,
    val soloBestMs: Long = 0,
    val soloNewRecord: Boolean = false,
    val message: String? = null,
    val showTutorial: Boolean = false,
    val isDailyChallenge: Boolean = false,
    val totalXp: Int = 0,
    val comboMessage: String? = null,
    val rematchRequested: Boolean = false,
    val genericBoard: GenericBoardState? = null,
    val explosionRemainingMs: Long = 0,
    val activeGameType: GameType = GameType.SUDOKU,
    val isLocallyPaused: Boolean = false,
    val resumeCountdownMs: Long = 0,
    val letterRack: List<String> = emptyList(),
    val activeLetterPlayerId: String? = null,
    val letterTurnEndsAt: Long = 0,
    val secretTeam: String? = null,
    val secretRole: String? = null,
    val secretKey: List<String> = emptyList(),
    val secretCurrentTeam: String? = null,
    val secretClue: String? = null,
    val secretClueCount: Int = 0,
    val secretChat: List<SecretChatUi> = emptyList(),
    val secretChatBlockedUntil: Long = 0,
    val globalChat: List<GameChatMessage> = emptyList(),
    val rpsRound: Int = 0,
    val rpsEndsAt: Long = 0,
    val rpsChoice: String? = null,
    val rpsChoices: Map<String, String> = emptyMap(),
    val rpsWinnerId: String? = null,
    val rpsTie: Boolean = false,
    val tetrisState: com.sudokuarena.domain.TetrisArenaState? = null,
    val pacmanState: com.sudokuarena.domain.PacmanArenaState? = null,
    val demolitionState: com.sudokuarena.domain.DemolitionArenaState? = null,
    val boardError: String? = null,
) {
    val canPlay: Boolean
        get() = connected && (isSoloMode || (playerId != null && roomState?.phase in setOf(RoomPhase.PLAYING, RoomPhase.SUDDEN_DEATH))) && selected != null &&
            pendingRequestId == null && penaltyRemainingMs == 0L &&
            fogSwipesRemaining == 0 && !soloCompleted && !isLocallyPaused && roomState?.phase != RoomPhase.PAUSED

    val ownPlayer: Player?
        get() = players.firstOrNull { it.id == playerId }

    val level: Int get() = totalXp / 500 + 1
    val gameType: GameType get() = roomState?.config?.gameType ?: activeGameType
    val genericTurnPlayerId: String?
        get() = genericBoard?.meta?.get("currentPlayerTurn")?.toString()
            ?: genericBoard?.meta?.get("activePlayerId")?.toString()
    val canInteractGeneric: Boolean
        get() {
            val phaseAllowsPlay = isSoloMode || roomState?.phase in setOf(RoomPhase.PLAYING, RoomPhase.SUDDEN_DEATH)
            val turnAllowsPlay = gameType !in setOf(
                GameType.MINESWEEPER, GameType.CROSSWORD, GameType.DOTS_AND_BOXES,
                GameType.SECRET_CODE, GameType.CAPITAL_ARENA,
            ) ||
                isSoloMode || genericTurnPlayerId == null || genericTurnPlayerId == playerId
            return connected && genericBoard != null && phaseAllowsPlay && turnAllowsPlay &&
                penaltyRemainingMs == 0L && fogSwipesRemaining == 0 && pendingRequestId == null &&
                !soloCompleted && !isLocallyPaused && roomState?.phase != RoomPhase.PAUSED
        }
    val canMakeGenericMove: Boolean
        get() = gameType != GameType.SUDOKU && selected != null && canInteractGeneric
}

class ArenaViewModel(
    private val isSoloMode: Boolean,
    private val initialColorMode: Boolean,
    private val gateway: GameRealtimeGateway?,
    private val sudokuGenerator: SudokuGenerator,
    private val recordStore: PlayerRecordStore,
    private val leaderboardRepository: LeaderboardRepository,
    private val playerName: String,
    private val requestedRoomCode: String? = null,
    private val isDailyChallenge: Boolean = false,
    private val initialGameType: GameType = GameType.SUDOKU,
    private val initialPuzzleDifficulty: PuzzleDifficulty = PuzzleDifficulty.MEDIUM,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ArenaUiState(
            isSoloMode = isSoloMode,
            isColorMode = isSoloMode && initialColorMode,
            soloBestMs = recordStore.soloBestMs(initialGameType),
            showTutorial = !recordStore.tutorialCompleted(initialGameType),
            isDailyChallenge = isDailyChallenge,
            totalXp = recordStore.totalXp(),
            activeGameType = initialGameType,
        ),
    )
    val state: StateFlow<ArenaUiState> = mutableState.asStateFlow()

    private val mutableHaptics = MutableSharedFlow<HapticCue>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val haptics: SharedFlow<HapticCue> = mutableHaptics.asSharedFlow()

    private var blockedUntil = 0L
    private var serverClockOffsetMs = 0L
    private var nearCompletionKeys: Set<String> = emptySet()
    private var soloPuzzle: SudokuPuzzle? = null
    private var soloStartedAt = 0L
    private var roomRequestSent = false
    private var tetrisInputSeq = 0L
    private var reconnectRoomCode = requestedRoomCode
    private var soloChallengeToken: String? = null
    private var explosionUntil = 0L
    private var initialGameConfigured = false
    private var localPuzzleEngine: LocalPuzzleEngine? = null
    private var localTetrisEngine: LocalTetrisEngine? = null
    private var localPacmanEngine: LocalPacmanEngine? = null
    private var localDemolitionEngine: LocalDemolitionEngine? = null
    private var localArcadeJob: Job? = null
    private var soloPausedAt = 0L
    private var soloPausedAccumulatedMs = 0L

    init {
        if (isSoloMode) {
            runCatching(::startSoloGame).onFailure(::reportBoardFailure)
        } else {
            val onlineGateway = requireNotNull(gateway) { "El modo online requiere GameRealtimeGateway" }
            viewModelScope.launch {
                onlineGateway.events.collect { event ->
                    runCatching { handleEvent(event) }.onFailure(::reportBoardFailure)
                }
            }
            onlineGateway.connect()
        }
        viewModelScope.launch {
            while (isActive) {
                val now = serverNow()
                mutableState.update { current ->
                    current.copy(
                        penaltyRemainingMs = (blockedUntil - now).coerceAtLeast(0),
                        serverNowMs = now,
                        boardEventRemainingMs = current.boardEvent
                            ?.let { (it.endsAt - now).coerceAtLeast(0) }
                            ?: 0,
                        matchRemainingMs = current.roomState?.endsAt
                            ?.let { (it - now).coerceAtLeast(0) }
                            ?: 0,
                        soloElapsedMs = if (current.isSoloMode && !current.soloCompleted) {
                            val currentPause = if (current.isLocallyPaused) System.currentTimeMillis() - soloPausedAt else 0L
                            (System.currentTimeMillis() - soloStartedAt - soloPausedAccumulatedMs - currentPause).coerceAtLeast(0)
                        } else current.soloElapsedMs,
                        explosionRemainingMs = (explosionUntil - System.currentTimeMillis()).coerceAtLeast(0),
                        resumeCountdownMs = current.roomState?.resumeCountdownEndsAt?.let { (it - now).coerceAtLeast(0) } ?: 0L,
                    )
                }
                delay(100)
            }
        }
    }

    fun select(row: Int, column: Int) {
        val current = mutableState.value
        val cell = current.board.getOrNull(row)?.getOrNull(column) ?: return
        if (cell.value == null && !cell.clearing && current.penaltyRemainingMs == 0L && current.fogSwipesRemaining == 0) {
            mutableState.update { it.copy(selected = CellPosition(row, column), message = null) }
        }
    }

    fun place(value: Int) {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (!current.canPlay || value !in 1..9) return
        mutableHaptics.tryEmit(HapticCue.CLICK)

        if (isSoloMode) {
            placeSolo(selected, value)
            return
        }

        val requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(pendingRequestId = requestId, message = null) }
        val activeGateway = gateway
        if (activeGateway == null) {
            mutableState.update { it.copy(pendingRequestId = null, message = "Conexión no disponible") }
            return
        }
        activeGateway.place(requestId, selected.row, selected.column, value, current.revision)
        releasePendingMoveAfterTimeout(requestId)
    }

    fun newSoloGame() {
        if (isSoloMode) runCatching(::startSoloGame).onFailure(::reportBoardFailure)
    }

    fun retryBoardLoad() {
        mutableState.update { it.copy(boardError = null, message = "Recargando arena…") }
        if (isSoloMode) {
            runCatching(::startSoloGame).onFailure(::reportBoardFailure)
        } else {
            roomRequestSent = false
            gateway?.disconnect()
            viewModelScope.launch {
                delay(250)
                gateway?.connect()
            }
        }
    }

    fun completeTutorial() {
        recordStore.markTutorialCompleted(initialGameType)
        mutableState.update { it.copy(showTutorial = false) }
    }

    fun openTutorial() {
        mutableState.update { it.copy(showTutorial = true) }
    }

    fun requestRematch() {
        if (isSoloMode) return
        gateway?.requestRematch()
        mutableState.update { it.copy(rematchRequested = true, message = "Voto de revancha enviado") }
    }

    fun toggleLoadoutPower(power: String) {
        if (isSoloMode || power !in setOf("FOG", "REFLECT", "REVEAL")) return
        val current = mutableState.value.ownPlayer?.powerLoadout.orEmpty()
        val next = if (power in current) current - power else (current + power).takeLast(2)
        if (next.size == 2) gateway?.setPowerLoadout(next)
    }

    fun setPowersEnabled(enabled: Boolean) {
        if (isSoloMode) return
        val current = mutableState.value.roomState ?: return
        gateway?.configureRoom(current.config.copy(powersEnabled = enabled))
    }

    fun setTeamMode(mode: TeamMode) {
        if (isSoloMode) return
        val current = mutableState.value.roomState ?: return
        gateway?.configureRoom(current.config.copy(teamMode = mode))
    }

    fun setTileType(type: TileType) {
        if (isSoloMode) return
        val current = mutableState.value.roomState ?: return
        gateway?.configureRoom(current.config.copy(tileType = type))
    }

    fun setBotDifficulty(difficulty: BotDifficulty) {
        if (isSoloMode) return
        val current = mutableState.value.roomState ?: return
        gateway?.configureRoom(current.config.copy(botDifficulty = difficulty))
    }

    fun setPuzzleDifficulty(difficulty: PuzzleDifficulty) {
        val current = mutableState.value.roomState ?: return
        if (current.hostPlayerId != mutableState.value.playerId) return
        gateway?.configureRoom(current.config.copy(puzzleDifficulty = difficulty))
    }

    fun setGameType(gameType: GameType) {
        if (isSoloMode) return
        val current = mutableState.value.roomState ?: return
        gateway?.configureRoom(current.config.copy(gameType = gameType))
    }

    fun sendSecretChat(message: String) {
        if (message.isNotBlank()) gateway?.sendSecretChat(message.trim())
    }

    fun sendGlobalChat(message: String) {
        if (!isSoloMode && message.isNotBlank()) gateway?.sendGlobalChat(message.trim())
    }

    fun sendTetrisInput(action: String) {
        if (isSoloMode) {
            localTetrisEngine?.input(action)
            publishLocalTetris()
        } else {
            tetrisInputSeq += 1
            val sequence = tetrisInputSeq
            // LEFT/RIGHT/ROTATE/DOWN se dibujan en el mismo frame. El servidor
            // sigue siendo autoritativo y su ACK corrige paredes o colisiones.
            mutableState.update { current ->
                current.copy(tetrisState = current.tetrisState?.let {
                    predictTetrisInput(it, current.playerId, action, sequence)
                })
            }
            gateway?.sendTetrisInput(action, sequence)
        }
    }

    fun sendPacmanInput(direction: String) {
        if (isSoloMode) localPacmanEngine?.input(direction) else gateway?.sendPacmanInput(direction)
    }

    fun sendDemolitionInput(paddleX: Float) {
        if (isSoloMode) localDemolitionEngine?.input(paddleX) else gateway?.sendDemolitionInput(paddleX)
    }

    fun chooseRps(choice: String) {
        if (choice !in setOf("ROCK", "PAPER", "SCISSORS") || mutableState.value.rpsChoice != null) return
        mutableState.update { it.copy(rpsChoice = choice) }
        gateway?.chooseRps(choice)
    }

    fun selectGeneric(row: Int, column: Int) {
        val current = mutableState.value
        if (!current.canInteractGeneric) return
        val cell = current.genericBoard?.board?.getOrNull(row)?.getOrNull(column) ?: return
        if (!cell.isBlocked && (cell.ownerId == null || current.gameType in setOf(GameType.CROSS_LETTERS, GameType.NURIKABE, GameType.NEXUS_ZERO))) {
            mutableState.update { it.copy(selected = CellPosition(row, column), message = null) }
        }
    }

    fun makeGenericMove(value: Any?) {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (!current.canMakeGenericMove) return
        submitGenericMove(selected, value)
    }

    fun makeGenericMoveAt(row: Int, column: Int, value: Any?) {
        val current = mutableState.value
        if (!current.canInteractGeneric) return
        val cell = current.genericBoard?.board?.getOrNull(row)?.getOrNull(column) ?: return
        // Ahorcado usa una coordenada de transporte para todas las teclas. Esa
        // celda puede quedar revelada tras el primer acierto, pero el teclado
        // debe seguir emitiendo letras hasta que finalice la palabra.
        if (cell.isBlocked || (cell.ownerId != null && current.gameType !in setOf(
                GameType.NURIKABE, GameType.TETRIS_ARENA, GameType.WORD_SEARCH,
                GameType.MERGE_2048, GameType.HANGMAN,
            ))) return
        submitGenericMove(CellPosition(row, column), value)
        if (current.gameType == GameType.NEXUS_ZERO) {
            mutableState.update { it.copy(selected = null) }
        }
    }

    private fun submitGenericMove(selected: CellPosition, value: Any?) {
        if (isSoloMode) {
            val result = localPuzzleEngine?.move(selected.row, selected.column, value) ?: return
            if (!result.accepted && result.penaltyMs > 0) {
                blockedUntil = System.currentTimeMillis() + result.penaltyMs
                if (result.hitMine) explosionUntil = System.currentTimeMillis() + 900L
                mutableHaptics.tryEmit(HapticCue.DANGER)
            } else if (result.accepted) mutableHaptics.tryEmit(HapticCue.CLICK)
            mutableState.update { current ->
                val score = (current.ownPlayer?.score ?: 0) + result.points
                current.copy(
                    genericBoard = result.state,
                    revision = result.state.revision,
                    players = listOf(soloPlayer(score)),
                    selected = null,
                    soloErrors = current.soloErrors + if (!result.accepted && result.penaltyMs > 0) 1 else 0,
                    message = result.message,
                )
            }
            if (result.state.completed) finishSoloGame()
            return
        }
        val requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(pendingRequestId = requestId, message = null) }
        val activeGateway = gateway
        if (activeGateway == null) {
            mutableState.update { it.copy(pendingRequestId = null, message = "Conexión no disponible") }
            return
        }
        activeGateway.makeMove(requestId, selected.row, selected.column, value)
        mutableHaptics.tryEmit(HapticCue.CLICK)
        releasePendingMoveAfterTimeout(requestId)
    }

    /** Un ACK perdido o una reconexión nunca deben convertir la UI en un candado permanente. */
    private fun releasePendingMoveAfterTimeout(requestId: String) {
        viewModelScope.launch {
            delay(GENERIC_REQUEST_TIMEOUT_MS)
            mutableState.update { current ->
                if (current.pendingRequestId == requestId) {
                    current.copy(pendingRequestId = null, message = "La jugada tardó demasiado. Intenta nuevamente.")
                } else current
            }
        }
    }

    fun makeWordSelection(start: CellPosition, end: CellPosition, word: String) {
        if (mutableState.value.gameType != GameType.WORD_SEARCH) return
        mutableState.update { it.copy(selected = start) }
        makeGenericMove(mapOf("word" to word, "endRow" to end.row, "endCol" to end.column))
    }

    fun fillWithAi() {
        if (!isSoloMode) gateway?.fillWithAi()
    }

    fun requestPause() {
        if (isSoloMode) {
            toggleSoloPause()
        } else gateway?.requestPause()
    }

    fun respondPause(accepted: Boolean) {
        if (!isSoloMode) gateway?.respondPause(accepted)
    }

    fun resumePausedGame() {
        if (isSoloMode) toggleSoloPause() else gateway?.resumePausedGame()
    }

    private fun toggleSoloPause() {
        val current = mutableState.value
        if (current.soloCompleted) return
        if (current.isLocallyPaused) {
            soloPausedAccumulatedMs += System.currentTimeMillis() - soloPausedAt
            soloPausedAt = 0L
        } else soloPausedAt = System.currentTimeMillis()
        mutableState.update { it.copy(isLocallyPaused = !current.isLocallyPaused, selected = null) }
    }

    fun startOnlineMatch() {
        if (!isSoloMode) gateway?.startRoom()
    }

    fun useFog(targetPlayerId: String) {
        if (isSoloMode) return
        val current = mutableState.value
        if (current.ownPlayer?.energy != 100 || targetPlayerId == current.playerId) return
        gateway?.usePower(type = "FOG", targetPlayerId = targetPlayerId)
        mutableState.update { it.copy(message = "Lanzando niebla…") }
    }

    fun useReflect() {
        if (isSoloMode || mutableState.value.ownPlayer?.energy != 100) return
        gateway?.usePower(type = "REFLECT")
        mutableState.update { it.copy(message = "Activando Escudo de Espejo…") }
    }

    fun useReveal() {
        if (isSoloMode) return
        val current = mutableState.value
        val selected = current.selected ?: run {
            mutableState.update { it.copy(message = "Selecciona una casilla para usar Ojo de Lince") }
            return
        }
        if ((current.ownPlayer?.energy ?: 0) < 50) return
        gateway?.usePower(
            type = "REVEAL",
            row = selected.row,
            column = selected.column,
            requestId = "reveal-${UUID.randomUUID()}",
        )
        mutableState.update { it.copy(message = "Ojo de Lince revelando la ficha…") }
    }

    fun sendReaction(emojiId: String) {
        if (isSoloMode || emojiId !in ALLOWED_REACTIONS) return
        gateway?.sendReaction(emojiId)
    }

    fun cleanFogSwipe() {
        if (isSoloMode) return
        mutableState.update { current ->
            val remaining = (current.fogSwipesRemaining - 1).coerceAtLeast(0)
            current.copy(
                fogSwipesRemaining = remaining,
                message = if (remaining == 0) "¡Niebla despejada!" else current.message,
            )
        }
    }

    private fun startSoloGame() {
        localArcadeJob?.cancel()
        localArcadeJob = null
        localTetrisEngine = null
        localPacmanEngine = null
        localDemolitionEngine = null
        soloChallengeToken = null
        viewModelScope.launch {
            soloChallengeToken = runCatching { leaderboardRepository.beginSoloChallenge() }.getOrNull()
        }
        soloPuzzle = if (initialGameType == GameType.SUDOKU) sudokuGenerator.generate(if (isDailyChallenge) LocalDate.now().toEpochDay() else null, initialPuzzleDifficulty) else null
        val arcadeTypes = setOf(GameType.TETRIS_ARENA, GameType.PACMAN_ARENA, GameType.DEMOLITION_ARCADE)
        localPuzzleEngine = if (initialGameType == GameType.SUDOKU || initialGameType in arcadeTypes) null
            else LocalPuzzleEngine(initialGameType, initialPuzzleDifficulty)
        if (initialGameType == GameType.TETRIS_ARENA) localTetrisEngine = LocalTetrisEngine(playerName)
        if (initialGameType == GameType.PACMAN_ARENA) localPacmanEngine = LocalPacmanEngine(playerName)
        if (initialGameType == GameType.DEMOLITION_ARCADE) localDemolitionEngine = LocalDemolitionEngine(playerName)
        soloStartedAt = System.currentTimeMillis()
        soloPausedAt = 0L
        soloPausedAccumulatedMs = 0L
        blockedUntil = 0
        nearCompletionKeys = emptySet()
        val board = soloPuzzle?.initialBoard?.map { row ->
            row.map { value ->
                BoardCell(value = value, ownerId = null, given = value != null)
            }
        } ?: emptyBoard()
        mutableState.value = ArenaUiState(
            isSoloMode = true,
            isColorMode = initialColorMode,
            connected = true,
            roomCode = null,
            playerId = SOLO_PLAYER_ID,
            board = board,
            players = listOf(soloPlayer(score = 0)),
            soloBestMs = recordStore.soloBestMs(initialGameType),
            showTutorial = mutableState.value.showTutorial,
            isDailyChallenge = isDailyChallenge,
            totalXp = recordStore.totalXp(),
            activeGameType = initialGameType,
            genericBoard = localPuzzleEngine?.snapshot(),
            tetrisState = localTetrisEngine?.snapshot(),
            pacmanState = localPacmanEngine?.snapshot(),
            demolitionState = localDemolitionEngine?.snapshot(),
            letterRack = localPuzzleEngine?.letterRack().orEmpty(),
            activeLetterPlayerId = SOLO_PLAYER_ID,
            secretTeam = if (initialGameType == GameType.SECRET_CODE) "RED" else null,
            secretRole = if (initialGameType == GameType.SECRET_CODE) "OPERATIVE" else null,
            secretCurrentTeam = if (initialGameType == GameType.SECRET_CODE) "RED" else null,
        )
        startLocalArcadeLoop()
    }

    private fun startLocalArcadeLoop() {
        localArcadeJob = when (initialGameType) {
            GameType.TETRIS_ARENA -> viewModelScope.launch {
                while (isActive && !mutableState.value.soloCompleted) {
                    delay(430)
                    if (!mutableState.value.isLocallyPaused) {
                        localTetrisEngine?.tick()
                        publishLocalTetris()
                    }
                }
            }
            GameType.PACMAN_ARENA -> viewModelScope.launch {
                while (isActive && !mutableState.value.soloCompleted) {
                    delay(170)
                    if (!mutableState.value.isLocallyPaused) {
                        localPacmanEngine?.tick()
                        val snapshot = localPacmanEngine?.snapshot() ?: continue
                        mutableState.update {
                            it.copy(
                                pacmanState = snapshot,
                                players = listOf(soloPlayer(snapshot.players.firstOrNull()?.score ?: 0)),
                            )
                        }
                        if (snapshot.completed) finishSoloGame()
                    }
                }
            }
            GameType.DEMOLITION_ARCADE -> viewModelScope.launch {
                while (isActive && !mutableState.value.soloCompleted) {
                    delay(48)
                    if (!mutableState.value.isLocallyPaused) {
                        repeat(3) { localDemolitionEngine?.tick() }
                        val snapshot = localDemolitionEngine?.snapshot() ?: continue
                        mutableState.update {
                            it.copy(
                                demolitionState = snapshot,
                                players = listOf(soloPlayer(snapshot.players.firstOrNull()?.score ?: 0)),
                            )
                        }
                        if (snapshot.completed) finishSoloGame()
                    }
                }
            }
            GameType.TOWER_DEFENSE -> viewModelScope.launch {
                while (isActive && !mutableState.value.soloCompleted) {
                    delay(50)
                    if (!mutableState.value.isLocallyPaused && localPuzzleEngine?.tickTowerDefense() == true) {
                        val snapshot = localPuzzleEngine?.snapshot() ?: continue
                        mutableState.update { it.copy(genericBoard = snapshot) }
                        if (snapshot.completed) finishSoloGame()
                    }
                }
            }
            else -> null
        }
    }

    private fun publishLocalTetris() {
        val snapshot = localTetrisEngine?.snapshot() ?: return
        mutableState.update {
            it.copy(
                tetrisState = snapshot,
                players = listOf(soloPlayer(snapshot.players.firstOrNull()?.score ?: 0)),
            )
        }
        if (snapshot.completed && !mutableState.value.soloCompleted) finishSoloGame()
    }

    private fun placeSolo(position: CellPosition, value: Int) {
        val puzzle = soloPuzzle ?: return
        if (puzzle.solution[position.row][position.column] != value) {
            blockedUntil = System.currentTimeMillis() + SOLO_PENALTY_MS
            mutableHaptics.tryEmit(HapticCue.DANGER)
            mutableState.update {
                it.copy(
                    selected = null,
                    soloErrors = it.soloErrors + 1,
                    message = "Ficha incorrecta: bloqueo de 3 segundos",
                )
            }
            return
        }

        mutableState.update { current ->
            val updatedBoard = current.board.mapIndexed { row, cells ->
                if (row != position.row) cells else cells.mapIndexed { column, cell ->
                    if (column == position.column) cell.copy(value = value, ownerId = SOLO_PLAYER_ID) else cell
                }
            }
            val updatedScore = (current.ownPlayer?.score ?: 0) + 10
            current.copy(
                board = updatedBoard,
                players = listOf(soloPlayer(updatedScore)),
                selected = null,
                revision = current.revision + 1,
                message = null,
            )
        }
        emitNearCompletionIfNeeded(mutableState.value.board)
        if (mutableState.value.board.all { row -> row.all { it.value != null } }) finishSoloGame()
    }

    private fun finishSoloGame() {
        val elapsed = mutableState.value.soloElapsedMs
        val score = mutableState.value.ownPlayer?.score ?: 0
        val newRecord = recordStore.recordSoloTime(initialGameType, elapsed)
        recordStore.recordSoloScore(initialGameType, score)
        val dailyBonus = isDailyChallenge && recordStore.markDailyCompleted(LocalDate.now().toString())
        recordStore.addXp(100 + if (dailyBonus) 250 else 0)
        mutableState.update {
            it.copy(
                soloCompleted = true,
                soloElapsedMs = elapsed,
                soloBestMs = recordStore.soloBestMs(initialGameType),
                soloNewRecord = newRecord,
                selected = null,
                totalXp = recordStore.totalXp(),
                message = if (dailyBonus) "Reto diario completado: +350 XP" else "+100 XP",
            )
        }
        viewModelScope.launch {
            soloChallengeToken?.let { token ->
                runCatching { leaderboardRepository.submitSoloRecord(playerName, initialGameType, elapsed, score, token) }
            }
        }
    }

    private fun handleEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Connected -> {
                mutableState.update { it.copy(connected = true, message = "Entrando a la sala…") }
                if (!roomRequestSent) {
                    roomRequestSent = true
                    reconnectRoomCode?.let { gateway?.joinRoom(it) } ?: gateway?.createRoom()
                }
            }
            RealtimeEvent.Disconnected -> {
                roomRequestSent = false
                mutableState.update {
                    it.copy(connected = false, playerId = null, pendingRequestId = null, message = "Reconectando…")
                }
            }
            is RealtimeEvent.Joined -> {
                reconnectRoomCode = event.roomCode
                mutableState.update {
                    it.copy(
                        playerId = event.playerId,
                        roomCode = event.roomCode,
                        roomState = event.roomState,
                        isColorMode = event.roomState.config.tileType == TileType.COLORS,
                        message = null,
                    )
                }
                applySnapshot(event.snapshot)
                if (!initialGameConfigured && requestedRoomCode == null && event.roomState.hostPlayerId == event.playerId) {
                    initialGameConfigured = true
                    if (event.roomState.config.gameType != initialGameType) {
                        gateway?.configureRoom(event.roomState.config.copy(gameType = initialGameType))
                    }
                }
            }
            is RealtimeEvent.RoomError -> mutableState.update { it.copy(message = event.message) }
            is RealtimeEvent.RoomStateUpdated -> mutableState.update {
                it.copy(
                    roomState = event.roomState,
                    roomCode = event.roomState.roomCode,
                    isColorMode = event.roomState.config.tileType == TileType.COLORS,
                    matchRemainingMs = event.roomState.endsAt?.let { end -> (end - serverNow()).coerceAtLeast(0) } ?: 0,
                    matchResults = if (event.roomState.phase == RoomPhase.PLAYING) emptyList() else it.matchResults,
                    rematchRequested = if (event.roomState.phase == RoomPhase.PLAYING) false else it.rematchRequested,
                )
            }
            is RealtimeEvent.MatchFinished -> mutableState.update {
                val ownResult = event.results.firstOrNull { result -> result.playerId == it.playerId }
                val xp = if (ownResult?.rank == 1) 180 else 80
                recordStore.addXp(xp)
                it.copy(
                    matchResults = event.results,
                    selected = null,
                    pendingRequestId = null,
                    totalXp = recordStore.totalXp(),
                    message = "+$xp XP de arena",
                )
            }
            is RealtimeEvent.SuddenDeath -> mutableState.update {
                it.copy(message = "¡MUERTE SÚBITA! La próxima jugada correcta gana")
            }
            is RealtimeEvent.StateUpdated -> applySnapshot(event.snapshot)
            is RealtimeEvent.MoveAccepted -> {
                val comboText = if (event.comboMultiplier > 1) "COMBO x${event.comboMultiplier} · ${event.combo} aciertos" else null
                mutableState.update {
                    if (it.pendingRequestId == event.requestId) {
                        it.copy(pendingRequestId = null, selected = null, comboMessage = comboText)
                    } else it
                }
                if (comboText != null) viewModelScope.launch {
                    delay(1_400)
                    mutableState.update { current -> if (current.comboMessage == comboText) current.copy(comboMessage = null) else current }
                }
            }
            is RealtimeEvent.MoveRejected -> mutableState.update {
                if (it.pendingRequestId == event.requestId) {
                    it.copy(pendingRequestId = null, message = friendlyRejectionMessage(event.code, event.message))
                } else it
            }
            is RealtimeEvent.Penalty -> {
                blockedUntil = event.blockedUntil
                mutableHaptics.tryEmit(HapticCue.DANGER)
                mutableState.update { it.copy(pendingRequestId = null, selected = null) }
            }
            is RealtimeEvent.SectionConquered -> showConquest(event)
            is RealtimeEvent.PowerReceived -> if (event.type == "FOG") {
                mutableHaptics.tryEmit(HapticCue.DANGER)
                mutableState.update {
                    it.copy(
                        selected = null,
                        fogSwipesRemaining = FOG_SWIPES,
                        message = if (event.reflected) {
                            "¡Tu Niebla fue reflejada! Desliza rápido para limpiar"
                        } else {
                            "¡Ataque de niebla! Desliza rápido para limpiar la tinta"
                        },
                    )
                }
            }
            is RealtimeEvent.PowerUsed -> mutableState.update {
                it.copy(
                    message = when (event.type) {
                        "REFLECT" -> "Escudo de Espejo activo durante 5 segundos"
                        "REVEAL" -> "Ojo de Lince completó la casilla"
                        "FOG" -> if (event.reflected) "El rival reflejó tu Niebla" else "Niebla lanzada"
                        else -> it.message
                    },
                )
            }
            is RealtimeEvent.PowerReflected -> mutableState.update {
                it.copy(message = "¡Escudo de Espejo! El sabotaje volvió al atacante")
            }
            is RealtimeEvent.PowerRejected -> mutableState.update { it.copy(message = event.message) }
            is RealtimeEvent.BoardEventStarted -> mutableState.update {
                it.copy(boardEvent = event.event, boardEventRemainingMs = event.event.endsAt - serverNow())
            }
            is RealtimeEvent.BoardEventEnded -> mutableState.update { it.copy(boardEvent = null, boardEventRemainingMs = 0) }
            is RealtimeEvent.ReactionReceived -> showReaction(event)
            is RealtimeEvent.GenericStateUpdated -> mutableState.update { current ->
                requireValidBoard(event.state)
                current.copy(
                    genericBoard = event.state,
                    revision = event.state.revision,
                    pendingRequestId = if (event.state.revision > current.revision) null else current.pendingRequestId,
                )
            }
            is RealtimeEvent.GenericMoveAccepted -> mutableState.update {
                if (it.pendingRequestId == event.requestId) {
                    it.copy(pendingRequestId = null, selected = null, message = "+${event.points} puntos")
                } else it
            }
            is RealtimeEvent.GenericMoveRejected -> {
                if (event.message.contains("Mina", ignoreCase = true)) {
                    explosionUntil = System.currentTimeMillis() + 900L
                    mutableHaptics.tryEmit(HapticCue.DANGER)
                }
                mutableState.update {
                    it.copy(pendingRequestId = null, selected = null, message = event.message)
                }
            }
            is RealtimeEvent.LetterRackUpdated -> mutableState.update {
                it.copy(letterRack = event.letters, activeLetterPlayerId = event.activePlayerId, letterTurnEndsAt = event.turnEndsAt)
            }
            is RealtimeEvent.SecretRoleUpdated -> mutableState.update {
                it.copy(
                    secretTeam = event.team, secretRole = event.role, secretKey = event.key,
                    secretCurrentTeam = event.currentTeam, secretClue = event.clue, secretClueCount = event.clueCount,
                )
            }
            is RealtimeEvent.SecretChatMessage -> mutableState.update {
                it.copy(secretChat = (it.secretChat + SecretChatUi(event.playerId, event.message, event.penalized)).takeLast(5))
            }
            is RealtimeEvent.SecretChatLocked -> mutableState.update {
                it.copy(secretChatBlockedUntil = event.blockedUntil, message = "Chat bloqueado 10 segundos por mencionar una palabra del tablero")
            }
            is RealtimeEvent.GlobalChatReceived -> mutableState.update {
                it.copy(globalChat = (it.globalChat + event.message).takeLast(40))
            }
            is RealtimeEvent.RpsStarted -> mutableState.update {
                it.copy(
                    rpsRound = event.round,
                    rpsEndsAt = event.endsAt,
                    rpsChoice = null,
                    rpsChoices = emptyMap(),
                    rpsWinnerId = null,
                    rpsTie = false,
                )
            }
            is RealtimeEvent.RpsResult -> mutableState.update {
                it.copy(rpsChoices = event.choices, rpsWinnerId = event.winnerId, rpsTie = event.tie)
            }
            is RealtimeEvent.TetrisStateUpdated -> mutableState.update { current ->
                val acknowledged = event.state.players.firstOrNull { it.id == current.playerId }?.lastInputSeq ?: 0L
                // 0 identifica un backend anterior al ACK secuencial. Se acepta
                // como fallback para que el cliente siga siendo compatible
                // mientras Render termina un despliegue progresivo.
                if (!isSoloMode && acknowledged > 0L && acknowledged < tetrisInputSeq) current
                else current.copy(tetrisState = event.state, serverNowMs = event.state.serverTime)
            }
            is RealtimeEvent.PacmanStateUpdated -> mutableState.update {
                it.copy(pacmanState = event.state, serverNowMs = event.state.serverTime)
            }
            is RealtimeEvent.DemolitionStateUpdated -> mutableState.update {
                it.copy(demolitionState = event.state, serverNowMs = event.state.serverTime)
            }
            is RealtimeEvent.Failure -> mutableState.update { it.copy(message = event.message) }
        }
    }

    private fun requireValidBoard(state: GenericBoardState) {
        require(state.rows > 0 && state.columns > 0) { "El tablero llegó vacío" }
        require(state.board.size == state.rows) { "Cantidad de filas inconsistente" }
        require(state.board.all { it.size == state.columns }) { "Cantidad de columnas inconsistente" }
    }

    private fun reportBoardFailure(error: Throwable) {
        mutableState.update {
            it.copy(
                pendingRequestId = null,
                boardError = "Error al cargar el tablero",
                message = error.message?.take(160),
            )
        }
    }

    private fun showConquest(event: RealtimeEvent.SectionConquered) {
        val winner = mutableState.value.players.firstOrNull { it.id == event.playerId }?.name ?: "Un jugador"
        val sections = event.sections.joinToString(" + ") { sectionLabel(it) }
        val message = "$winner conquistó $sections (+${event.bonus})"
        mutableState.update { it.copy(conquestMessage = message) }
        viewModelScope.launch {
            delay(2_000)
            mutableState.update { current ->
                if (current.conquestMessage == message) current.copy(conquestMessage = null) else current
            }
        }
    }

    private fun showReaction(event: RealtimeEvent.ReactionReceived) {
        val reaction = ReactionUi(event.reactionId, event.emojiId)
        mutableState.update { it.copy(reactions = it.reactions + (event.playerId to reaction)) }
        viewModelScope.launch {
            delay(2_200)
            mutableState.update { current ->
                if (current.reactions[event.playerId]?.reactionId == event.reactionId) {
                    current.copy(reactions = current.reactions - event.playerId)
                } else current
            }
        }
    }

    private fun applySnapshot(snapshot: GameSnapshot) {
        serverClockOffsetMs = snapshot.serverTime - System.currentTimeMillis()
        val ownId = mutableState.value.playerId
        blockedUntil = snapshot.players.firstOrNull { it.id == ownId }?.blockedUntil ?: blockedUntil
        emitNearCompletionIfNeeded(snapshot.board)
        mutableState.update { current ->
            val selected = current.selected?.takeIf { position ->
                snapshot.board[position.row][position.column].let { it.value == null && !it.clearing }
            }
            current.copy(
                revision = snapshot.revision,
                board = snapshot.board,
                players = snapshot.players,
                boardEvent = snapshot.boardEvent,
                boardEventRemainingMs = snapshot.boardEvent?.let { (it.endsAt - serverNow()).coerceAtLeast(0) } ?: 0,
                selected = selected,
                message = current.message.takeUnless { it == "Lanzando niebla…" || it == "Entrando a la sala…" },
            )
        }
    }

    private fun emitNearCompletionIfNeeded(board: List<List<BoardCell>>) {
        val currentKeys = nearCompletions(board)
        if ((currentKeys - nearCompletionKeys).isNotEmpty()) mutableHaptics.tryEmit(HapticCue.CRESCENDO)
        nearCompletionKeys = currentKeys
    }

    private fun serverNow(): Long = System.currentTimeMillis() + serverClockOffsetMs

    override fun onCleared() {
        localArcadeJob?.cancel()
        gateway?.disconnect()
        super.onCleared()
    }

    companion object {
        private const val FOG_SWIPES = 6
        private const val SOLO_PENALTY_MS = 3_000L
        private const val GENERIC_REQUEST_TIMEOUT_MS = 5_000L
        private const val SOLO_PLAYER_ID = "solo"
        private val ALLOWED_REACTIONS = setOf("LAUGH", "CRY", "ANGRY", "SURPRISED")

        fun factory(
            isSoloMode: Boolean,
            initialColorMode: Boolean,
            gateway: GameRealtimeGateway?,
            sudokuGenerator: SudokuGenerator,
            recordStore: PlayerRecordStore,
            leaderboardRepository: LeaderboardRepository,
            playerName: String,
            requestedRoomCode: String? = null,
            isDailyChallenge: Boolean = false,
            initialGameType: GameType = GameType.SUDOKU,
            initialPuzzleDifficulty: PuzzleDifficulty = PuzzleDifficulty.MEDIUM,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ArenaViewModel(
                isSoloMode = isSoloMode,
                initialColorMode = initialColorMode,
                gateway = gateway,
                sudokuGenerator = sudokuGenerator,
                recordStore = recordStore,
                leaderboardRepository = leaderboardRepository,
                playerName = playerName,
                requestedRoomCode = requestedRoomCode,
                isDailyChallenge = isDailyChallenge,
                initialGameType = initialGameType,
                initialPuzzleDifficulty = initialPuzzleDifficulty,
            ) as T
        }
    }
}

private val predictiveTetrominoes: Map<String, List<List<Pair<Int, Int>>>> = mapOf(
    "I" to listOf(listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3), listOf(0 to 2, 1 to 2, 2 to 2, 3 to 2)),
    "J" to listOf(listOf(0 to 0, 1 to 0, 1 to 1, 1 to 2), listOf(0 to 1, 0 to 2, 1 to 1, 2 to 1), listOf(1 to 0, 1 to 1, 1 to 2, 2 to 2), listOf(0 to 1, 1 to 1, 2 to 0, 2 to 1)),
    "L" to listOf(listOf(0 to 2, 1 to 0, 1 to 1, 1 to 2), listOf(0 to 1, 1 to 1, 2 to 1, 2 to 2), listOf(1 to 0, 1 to 1, 1 to 2, 2 to 0), listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1)),
    "O" to listOf(listOf(0 to 1, 0 to 2, 1 to 1, 1 to 2)),
    "S" to listOf(listOf(0 to 1, 0 to 2, 1 to 0, 1 to 1), listOf(0 to 1, 1 to 1, 1 to 2, 2 to 2)),
    "T" to listOf(listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2), listOf(0 to 1, 1 to 1, 1 to 2, 2 to 1), listOf(1 to 0, 1 to 1, 1 to 2, 2 to 1), listOf(0 to 1, 1 to 0, 1 to 1, 2 to 1)),
    "Z" to listOf(listOf(0 to 0, 0 to 1, 1 to 1, 1 to 2), listOf(0 to 2, 1 to 1, 1 to 2, 2 to 1)),
)

private fun predictTetrisInput(
    arena: com.sudokuarena.domain.TetrisArenaState,
    playerId: String?,
    action: String,
    sequence: Long,
): com.sudokuarena.domain.TetrisArenaState {
    if (playerId == null || action !in setOf("LEFT", "RIGHT", "ROTATE", "SOFT_DROP")) return arena
    return arena.copy(players = arena.players.map { player ->
        if (player.id != playerId || player.settledBoard.size != 20) return@map player
        val rotations = predictiveTetrominoes[player.current] ?: return@map player
        var row = player.pieceRow
        var col = player.pieceCol
        var rotation = player.pieceRotation.mod(rotations.size)
        when (action) {
            "LEFT" -> col -= 1
            "RIGHT" -> col += 1
            "SOFT_DROP" -> row += 1
            "ROTATE" -> rotation = (rotation + 1) % rotations.size
        }
        fun collides(testRow: Int, testCol: Int, testRotation: Int): Boolean = rotations[testRotation].any { (dy, dx) ->
            val y = testRow + dy; val x = testCol + dx
            x !in 0..9 || y >= 20 || (y >= 0 && player.settledBoard[y][x] != 0)
        }
        if (collides(row, col, rotation)) {
            if (action == "ROTATE") {
                val kick = listOf(-1, 1, 0).firstOrNull { !collides(player.pieceRow, player.pieceCol + it, rotation) }
                if (kick != null) { row = player.pieceRow; col = player.pieceCol + kick }
                else { row = player.pieceRow; col = player.pieceCol; rotation = player.pieceRotation.mod(rotations.size) }
            } else { row = player.pieceRow; col = player.pieceCol }
        }
        val rendered = player.settledBoard.map { it.toMutableList() }
        val color = listOf("I", "J", "L", "O", "S", "T", "Z").indexOf(player.current) + 1
        rotations[rotation].forEach { (dy, dx) ->
            val y = row + dy; val x = col + dx
            if (y in 0..19 && x in 0..9) rendered[y][x] = color
        }
        player.copy(board = rendered, pieceRow = row, pieceCol = col, pieceRotation = rotation, lastInputSeq = sequence)
    })
}

private fun soloPlayer(score: Int): Player = Player(
    id = "solo",
    name = "Tú",
    slot = 0,
    colorHex = "#1E88E5",
    score = score,
    blockedUntil = 0,
    energy = 0,
    teamId = "solo",
    role = "PLAYER",
    teamScore = score,
)

private fun emptyBoard(): List<List<BoardCell>> = List(9) { List(9) { BoardCell() } }

private fun friendlyRejectionMessage(code: String, fallback: String): String = when (code) {
    "CELL_OCCUPIED" -> "Otro jugador llegó primero a esa casilla"
    "CELL_CLEARING" -> "Espera a que termine la conquista"
    "INCORRECT_VALUE" -> "Ficha incorrecta: bloqueo temporal"
    "BLOCKED" -> "Aún estás bloqueado"
    "WAITING_FOR_PLAYERS" -> "Esperando al menos un rival"
    else -> fallback
}

private fun sectionLabel(section: ConqueredSection): String = when (section.kind) {
    "row" -> "fila ${section.index + 1}"
    "column" -> "columna ${section.index + 1}"
    "box" -> "cuadrante ${section.index + 1}"
    else -> "sección"
}

private fun nearCompletions(board: List<List<BoardCell>>): Set<String> {
    if (board.size != 9 || board.any { it.size != 9 }) return emptySet()
    val keys = mutableSetOf<String>()
    for (index in 0..8) {
        if (board[index].count { it.value != null } == 8) keys += "row:$index"
        if ((0..8).count { row -> board[row][index].value != null } == 8) keys += "column:$index"
    }
    return keys
}
