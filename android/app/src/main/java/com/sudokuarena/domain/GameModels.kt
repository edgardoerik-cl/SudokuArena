package com.sudokuarena.domain

import kotlinx.coroutines.flow.Flow

data class BoardCell(
    val value: Int? = null,
    val ownerId: String? = null,
    val clearing: Boolean = false,
    val golden: Boolean = false,
    val given: Boolean = false,
    val ownerTeamId: String? = null,
)

data class Player(
    val id: String,
    val name: String,
    val slot: Int,
    val colorHex: String,
    val score: Int,
    val blockedUntil: Long,
    val energy: Int,
    val teamId: String,
    val role: String,
    val teamScore: Int,
    val isBot: Boolean = false,
    val shieldUntil: Long = 0,
    val combo: Int = 0,
    val maxCombo: Int = 0,
    val comboMultiplier: Int = 1,
    val botPersona: String? = null,
    val powerLoadout: List<String> = listOf("FOG", "REVEAL"),
    val avatarId: String = "ORBIT",
)

enum class TeamMode { DUEL, FFA, TWO_V_ONE, TWO_V_TWO, THREE_V_ONE }
enum class TileType { NUMBERS, COLORS }
enum class BotDifficulty { EASY, MEDIUM, HARD }
enum class PuzzleDifficulty { EASY, MEDIUM, HARD, EXPERT }
enum class GameType {
    SUDOKU, MINESWEEPER, WORD_SEARCH, CROSSWORD, TIC_TAC_TOE,
    DOTS_AND_BOXES, KAKURO, MATHDOKU, HITORI, CHESS_TACTICS,
    NURIKABE, BRIDGES, TETRIS_ARENA, HANGMAN, ARROWS_ESCAPE, PACMAN_ARENA,
    CROSS_LETTERS, SECRET_CODE, CAPITAL_ARENA, NEXUS_ZERO, CHECKERS, DEMOLITION_ARCADE,
    MEMORY_NEON, MERGE_2048, TOWER_DEFENSE, REACTOR_CHAIN,
}
enum class RoomPhase { LOBBY, RPS, PLAYING, PAUSED, SUDDEN_DEATH, FINISHED }

data class RoomConfig(
    val gameType: GameType = GameType.SUDOKU,
    val powersEnabled: Boolean = true,
    val teamMode: TeamMode = TeamMode.FFA,
    val tileType: TileType = TileType.NUMBERS,
    val botDifficulty: BotDifficulty = BotDifficulty.MEDIUM,
    val puzzleDifficulty: PuzzleDifficulty = PuzzleDifficulty.MEDIUM,
)

data class GenericCell(
    val value: Any? = null,
    val isRevealed: Boolean = false,
    val ownerId: String? = null,
    val isBlocked: Boolean = false,
    val meta: Map<String, Any?> = emptyMap(),
)

data class GenericBoardState(
    val gameId: String,
    val gameType: GameType,
    val revision: Long,
    val serverTime: Long,
    val rows: Int,
    val columns: Int,
    val board: List<List<GenericCell>>,
    val completed: Boolean,
    val meta: Map<String, Any?> = emptyMap(),
)

data class TetrisPlayerState(
    val id: String,
    val name: String,
    val colorHex: String,
    val board: List<List<Int>>,
    val next: String,
    val score: Int,
    val lines: Int,
    val gameOver: Boolean,
    val impact: Int = 0,
    val current: String = "O",
    val hold: String? = null,
    val canHold: Boolean = true,
    val cleanBombUsed: Boolean = false,
    val abilityEnergy: Int = 0,
    val bombsUsed: Int = 0,
    val garbageSent: Int = 0,
)
data class TetrisArenaState(
    val serverTime: Long,
    val tick: Long,
    val completed: Boolean,
    val players: List<TetrisPlayerState>,
)
data class PacmanActorState(
    val id: String,
    val x: Float,
    val y: Float,
    val direction: String,
    val lives: Int = 0,
    val score: Int = 0,
    val colorHex: String? = null,
    val name: String? = null,
    val mode: String? = null,
)
data class PacmanArenaState(
    val serverTime: Long,
    val tick: Long,
    val completed: Boolean,
    val tilemap: List<List<Int>>,
    val pills: Set<String>,
    val powerPills: Set<String>,
    val players: List<PacmanActorState>,
    val ghosts: List<PacmanActorState>,
    val status: String = "WAITING",
)

data class DemolitionBrickState(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val hp: Int,
    val color: Int,
)
data class DemolitionBallState(val id: String, val x: Float, val y: Float, val vx: Float, val vy: Float)
data class DemolitionDropState(val id: String, val x: Float, val y: Float, val type: String)

data class DemolitionPlayerState(
    val id: String,
    val name: String,
    val colorHex: String,
    val paddleX: Float,
    val ballX: Float,
    val ballY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val lives: Int,
    val score: Int,
    val level: Int,
    val bricks: List<DemolitionBrickState>,
    val balls: List<DemolitionBallState> = emptyList(),
    val drops: List<DemolitionDropState> = emptyList(),
    val laserUntil: Long = 0,
    val speedUntil: Long = 0,
)

data class DemolitionArenaState(
    val serverTime: Long,
    val tick: Long,
    val completed: Boolean,
    val players: List<DemolitionPlayerState>,
)

data class RoomState(
    val roomCode: String,
    val hostPlayerId: String,
    val config: RoomConfig,
    val phase: RoomPhase,
    val startedAt: Long?,
    val endsAt: Long?,
    val suddenDeath: Boolean = false,
    val rematchVotes: Int = 0,
    val pauseRequesterId: String? = null,
    val pauseVotes: Int = 0,
    val pauseNoVotes: Int = 0,
    val pauseRequired: Int = 0,
    val resumeCountdownEndsAt: Long? = null,
)

data class MatchResultEntry(
    val rank: Int,
    val playerId: String,
    val name: String,
    val score: Int,
    val teamId: String,
    val teamScore: Int,
    val role: String,
    val isBot: Boolean = false,
    val maxCombo: Int = 0,
)

data class GameChatMessage(
    val id: String,
    val playerId: String,
    val message: String,
    val sentAt: Long,
)

enum class BoardEventType { MIRROR_HOUR, GOLDEN_CELLS }

data class ActiveBoardEvent(
    val type: BoardEventType,
    val startedAt: Long,
    val endsAt: Long,
)

data class GameSnapshot(
    val gameId: String,
    val revision: Long,
    val serverTime: Long,
    val board: List<List<BoardCell>>,
    val players: List<Player>,
    val boardEvent: ActiveBoardEvent?,
)

data class ConqueredSection(
    val kind: String,
    val index: Int,
)

sealed interface RealtimeEvent {
    data object Connected : RealtimeEvent
    data object Disconnected : RealtimeEvent
    data class Joined(
        val playerId: String,
        val roomCode: String,
        val roomState: RoomState,
        val snapshot: GameSnapshot,
    ) : RealtimeEvent
    data class RoomError(val code: String, val message: String) : RealtimeEvent
    data class RoomStateUpdated(val roomState: RoomState) : RealtimeEvent
    data class MatchFinished(val results: List<MatchResultEntry>, val finishedAt: Long) : RealtimeEvent
    data class SuddenDeath(val endsAt: Long) : RealtimeEvent
    data class StateUpdated(val snapshot: GameSnapshot) : RealtimeEvent
    data class MoveAccepted(
        val requestId: String,
        val revision: Long,
        val combo: Int = 1,
        val comboMultiplier: Int = 1,
        val comboBonus: Int = 0,
    ) : RealtimeEvent
    data class MoveRejected(val requestId: String, val code: String, val message: String) : RealtimeEvent
    data class Penalty(val requestId: String, val blockedUntil: Long, val reason: String) : RealtimeEvent
    data class SectionConquered(
        val playerId: String,
        val sections: List<ConqueredSection>,
        val bonus: Int,
    ) : RealtimeEvent
    data class PowerReceived(
        val attackerId: String,
        val type: String,
        val reflected: Boolean = false,
        val reflectedBy: String? = null,
    ) : RealtimeEvent
    data class PowerUsed(val type: String, val reflected: Boolean = false) : RealtimeEvent
    data class PowerReflected(val attackerId: String) : RealtimeEvent
    data class PowerRejected(val message: String) : RealtimeEvent
    data class BoardEventStarted(val event: ActiveBoardEvent) : RealtimeEvent
    data class BoardEventEnded(val type: BoardEventType?) : RealtimeEvent
    data class ReactionReceived(
        val reactionId: String,
        val playerId: String,
        val emojiId: String,
    ) : RealtimeEvent
    data class GenericStateUpdated(val state: GenericBoardState) : RealtimeEvent
    data class GenericMoveAccepted(val requestId: String, val points: Int, val completed: Boolean) : RealtimeEvent
    data class GenericMoveRejected(val requestId: String, val code: String, val message: String) : RealtimeEvent
    data class LetterRackUpdated(val letters: List<String>, val activePlayerId: String?, val turnEndsAt: Long) : RealtimeEvent
    data class SecretRoleUpdated(
        val team: String, val role: String, val key: List<String>,
        val currentTeam: String, val clue: String?, val clueCount: Int,
    ) : RealtimeEvent
    data class SecretChatMessage(val playerId: String, val message: String, val penalized: Boolean) : RealtimeEvent
    data class SecretChatLocked(val blockedUntil: Long) : RealtimeEvent
    data class GlobalChatReceived(val message: GameChatMessage) : RealtimeEvent
    data class RpsStarted(val round: Int, val endsAt: Long) : RealtimeEvent
    data class RpsResult(val round: Int, val choices: Map<String, String>, val winnerId: String?, val tie: Boolean) : RealtimeEvent
    data class TetrisStateUpdated(val state: TetrisArenaState) : RealtimeEvent
    data class PacmanStateUpdated(val state: PacmanArenaState) : RealtimeEvent
    data class DemolitionStateUpdated(val state: DemolitionArenaState) : RealtimeEvent
    data class Failure(val message: String) : RealtimeEvent
}

/** Puerto de dominio: presentación no depende de Socket.IO ni de JSONObject. */
interface GameRealtimeGateway {
    val events: Flow<RealtimeEvent>
    fun connect()
    fun disconnect()
    fun createRoom()
    fun joinRoom(roomCode: String)
    fun configureRoom(config: RoomConfig)
    fun startRoom()
    fun fillWithAi()
    fun requestRematch()
    fun setPowerLoadout(powers: List<String>)
    fun makeMove(requestId: String, row: Int, col: Int, value: Any?)
    fun place(requestId: String, row: Int, column: Int, value: Int, clientRevision: Long)
    fun usePower(
        type: String,
        targetPlayerId: String? = null,
        row: Int? = null,
        column: Int? = null,
        requestId: String? = null,
    )
    fun sendReaction(emojiId: String)
    fun sendSecretChat(message: String)
    fun sendGlobalChat(message: String)
    fun chooseRps(choice: String)
    fun sendTetrisInput(action: String)
    fun sendPacmanInput(direction: String)
    fun sendDemolitionInput(paddleX: Float)
    fun requestPause()
    fun respondPause(accepted: Boolean)
    fun resumePausedGame()
}
