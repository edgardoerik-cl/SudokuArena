package com.sudokuarena.domain

import kotlinx.coroutines.flow.Flow

data class BoardCell(
    val value: Int? = null,
    val ownerId: String? = null,
    val clearing: Boolean = false,
    val golden: Boolean = false,
)

data class Player(
    val id: String,
    val name: String,
    val slot: Int,
    val colorHex: String,
    val score: Int,
    val blockedUntil: Long,
    val energy: Int,
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
    data class Joined(val playerId: String, val snapshot: GameSnapshot) : RealtimeEvent
    data class StateUpdated(val snapshot: GameSnapshot) : RealtimeEvent
    data class MoveAccepted(val requestId: String, val revision: Long) : RealtimeEvent
    data class MoveRejected(val requestId: String, val code: String, val message: String) : RealtimeEvent
    data class Penalty(val requestId: String, val blockedUntil: Long, val reason: String) : RealtimeEvent
    data class SectionConquered(
        val playerId: String,
        val sections: List<ConqueredSection>,
        val bonus: Int,
    ) : RealtimeEvent
    data class PowerReceived(val attackerId: String, val type: String) : RealtimeEvent
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
    fun place(requestId: String, row: Int, column: Int, value: Int, clientRevision: Long)
    fun usePower(targetPlayerId: String)
    fun sendReaction(emojiId: String)
}
