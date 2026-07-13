package com.sudokuarena.domain

import kotlinx.coroutines.flow.Flow

data class BoardCell(
    val value: Int? = null,
    val ownerId: String? = null,
    val clearing: Boolean = false,
)

data class Player(
    val id: String,
    val name: String,
    val slot: Int,
    val colorHex: String,
    val score: Int,
    val blockedUntil: Long,
)

data class GameSnapshot(
    val gameId: String,
    val revision: Long,
    val serverTime: Long,
    val board: List<List<BoardCell>>,
    val players: List<Player>,
)

sealed interface RealtimeEvent {
    data object Connected : RealtimeEvent
    data object Disconnected : RealtimeEvent
    data class Joined(val playerId: String, val snapshot: GameSnapshot) : RealtimeEvent
    data class StateUpdated(val snapshot: GameSnapshot) : RealtimeEvent
    data class MoveAccepted(val requestId: String, val revision: Long) : RealtimeEvent
    data class MoveRejected(val requestId: String, val code: String, val message: String) : RealtimeEvent
    data class Penalty(val requestId: String, val blockedUntil: Long, val reason: String) : RealtimeEvent
    data class Failure(val message: String) : RealtimeEvent
}

/** Puerto de dominio: presentación no depende de Socket.IO ni de JSONObject. */
interface GameRealtimeGateway {
    val events: Flow<RealtimeEvent>
    fun connect()
    fun disconnect()
    fun place(requestId: String, row: Int, column: Int, value: Int, clientRevision: Long)
}
