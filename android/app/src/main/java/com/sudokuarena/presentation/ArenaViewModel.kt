package com.sudokuarena.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sudokuarena.domain.ActiveBoardEvent
import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.BoardEventType
import com.sudokuarena.domain.ConqueredSection
import com.sudokuarena.domain.GameRealtimeGateway
import com.sudokuarena.domain.GameSnapshot
import com.sudokuarena.domain.Player
import com.sudokuarena.domain.RealtimeEvent
import java.util.UUID
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

data class CellPosition(val row: Int, val column: Int)

enum class HapticCue { CLICK, DANGER, CRESCENDO }

data class ReactionUi(
    val reactionId: String,
    val emojiId: String,
)

data class ArenaUiState(
    val connected: Boolean = false,
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
    val message: String? = null,
) {
    val canPlay: Boolean
        get() = connected && selected != null && pendingRequestId == null &&
            penaltyRemainingMs == 0L && fogSwipesRemaining == 0

    val ownPlayer: Player?
        get() = players.firstOrNull { it.id == playerId }
}

class ArenaViewModel(
    private val gateway: GameRealtimeGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ArenaUiState())
    val state: StateFlow<ArenaUiState> = mutableState.asStateFlow()

    private val mutableHaptics = MutableSharedFlow<HapticCue>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val haptics: SharedFlow<HapticCue> = mutableHaptics.asSharedFlow()

    private var blockedUntil = 0L
    private var serverClockOffsetMs = 0L
    private var nearCompletionKeys: Set<String> = emptySet()

    init {
        viewModelScope.launch { gateway.events.collect(::handleEvent) }
        viewModelScope.launch {
            while (isActive) {
                val serverNow = System.currentTimeMillis() + serverClockOffsetMs
                mutableState.update { current ->
                    current.copy(
                        penaltyRemainingMs = (blockedUntil - serverNow).coerceAtLeast(0),
                        boardEventRemainingMs = current.boardEvent
                            ?.let { (it.endsAt - serverNow).coerceAtLeast(0) }
                            ?: 0,
                    )
                }
                delay(100)
            }
        }
        gateway.connect()
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
        val requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(pendingRequestId = requestId, message = null) }
        // No hay escritura optimista: el snapshot del servidor resuelve carreras.
        gateway.place(requestId, selected.row, selected.column, value, current.revision)
    }

    fun useFog(targetPlayerId: String) {
        val current = mutableState.value
        if (current.ownPlayer?.energy != 100 || targetPlayerId == current.playerId) return
        gateway.usePower(targetPlayerId)
        mutableState.update { it.copy(message = "Lanzando niebla…") }
    }

    fun sendReaction(emojiId: String) {
        if (emojiId !in ALLOWED_REACTIONS) return
        gateway.sendReaction(emojiId)
    }

    fun cleanFogSwipe() {
        mutableState.update { current ->
            val remaining = (current.fogSwipesRemaining - 1).coerceAtLeast(0)
            current.copy(
                fogSwipesRemaining = remaining,
                message = if (remaining == 0) "¡Niebla despejada!" else current.message,
            )
        }
    }

    private fun handleEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Connected -> mutableState.update { it.copy(connected = true, message = null) }
            RealtimeEvent.Disconnected -> mutableState.update {
                it.copy(connected = false, pendingRequestId = null, message = "Reconectando…")
            }
            is RealtimeEvent.Joined -> {
                mutableState.update { it.copy(playerId = event.playerId) }
                applySnapshot(event.snapshot)
            }
            is RealtimeEvent.StateUpdated -> applySnapshot(event.snapshot)
            is RealtimeEvent.MoveAccepted -> mutableState.update {
                if (it.pendingRequestId == event.requestId) it.copy(pendingRequestId = null, selected = null) else it
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
            is RealtimeEvent.PowerReceived -> {
                if (event.type == "FOG") {
                    mutableHaptics.tryEmit(HapticCue.DANGER)
                    mutableState.update {
                        it.copy(
                            selected = null,
                            fogSwipesRemaining = FOG_SWIPES,
                            message = "¡Ataque de niebla! Desliza rápido para limpiar la tinta",
                        )
                    }
                }
            }
            is RealtimeEvent.PowerRejected -> mutableState.update { it.copy(message = event.message) }
            is RealtimeEvent.BoardEventStarted -> mutableState.update {
                it.copy(boardEvent = event.event, boardEventRemainingMs = event.event.endsAt - serverNow())
            }
            is RealtimeEvent.BoardEventEnded -> mutableState.update {
                it.copy(boardEvent = null, boardEventRemainingMs = 0)
            }
            is RealtimeEvent.ReactionReceived -> showReaction(event)
            is RealtimeEvent.Failure -> mutableState.update { it.copy(message = event.message) }
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

        val currentNearCompletionKeys = nearCompletions(snapshot.board)
        if ((currentNearCompletionKeys - nearCompletionKeys).isNotEmpty()) {
            mutableHaptics.tryEmit(HapticCue.CRESCENDO)
        }
        nearCompletionKeys = currentNearCompletionKeys

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
                message = current.message.takeUnless { it == "Lanzando niebla…" },
            )
        }
    }

    private fun serverNow(): Long = System.currentTimeMillis() + serverClockOffsetMs

    override fun onCleared() {
        gateway.disconnect()
        super.onCleared()
    }

    companion object {
        private const val FOG_SWIPES = 6
        private val ALLOWED_REACTIONS = setOf("LAUGH", "CRY", "ANGRY", "SURPRISED")

        fun factory(gateway: GameRealtimeGateway): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ArenaViewModel(gateway) as T
            }
    }
}

private fun emptyBoard(): List<List<BoardCell>> = List(9) { List(9) { BoardCell() } }

private fun friendlyRejectionMessage(code: String, fallback: String): String = when (code) {
    "CELL_OCCUPIED" -> "Otro jugador llegó primero a esa casilla"
    "CELL_CLEARING" -> "Espera a que termine la conquista"
    "INCORRECT_VALUE" -> "Número incorrecto: bloqueo temporal"
    "BLOCKED" -> "Aún estás bloqueado"
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
