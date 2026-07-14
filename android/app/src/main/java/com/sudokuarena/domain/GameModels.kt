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
)

enum class TeamMode { FFA, TWO_V_TWO, THREE_V_ONE }
enum class TileType { NUMBERS, COLORS }
enum class BotDifficulty { EASY, MEDIUM, HARD }
enum class RoomPhase { LOBBY, PLAYING, SUDDEN_DEATH, FINISHED }

data class RoomConfig(
    val powersEnabled: Boolean = true,
    val teamMode: TeamMode = TeamMode.FFA,
    val tileType: TileType = TileType.NUMBERS,
    val botDifficulty: BotDifficulty = BotDifficulty.MEDIUM,
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
    fun place(requestId: String, row: Int, column: Int, value: Int, clientRevision: Long)
    fun usePower(
        type: String,
        targetPlayerId: String? = null,
        row: Int? = null,
        column: Int? = null,
        requestId: String? = null,
    )
    fun sendReaction(emojiId: String)
}
