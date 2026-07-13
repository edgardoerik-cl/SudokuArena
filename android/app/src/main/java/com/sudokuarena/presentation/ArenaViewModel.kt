package com.sudokuarena.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.GameRealtimeGateway
import com.sudokuarena.domain.Player
import com.sudokuarena.domain.RealtimeEvent
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CellPosition(val row: Int, val column: Int)

data class ArenaUiState(
    val connected: Boolean = false,
    val playerId: String? = null,
    val revision: Long = 0,
    val board: List<List<BoardCell>> = emptyBoard(),
    val players: List<Player> = emptyList(),
    val selected: CellPosition? = null,
    val pendingRequestId: String? = null,
    val penaltyRemainingMs: Long = 0,
    val message: String? = null,
) {
    val canPlay: Boolean
        get() = connected && selected != null && pendingRequestId == null && penaltyRemainingMs == 0L
}

class ArenaViewModel(
    private val gateway: GameRealtimeGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ArenaUiState())
    val state: StateFlow<ArenaUiState> = mutableState.asStateFlow()

    private var blockedUntil = 0L
    private var serverClockOffsetMs = 0L

    init {
        viewModelScope.launch { gateway.events.collect(::handleEvent) }
        viewModelScope.launch {
            while (isActive) {
                val serverNow = System.currentTimeMillis() + serverClockOffsetMs
                mutableState.update { it.copy(penaltyRemainingMs = (blockedUntil - serverNow).coerceAtLeast(0)) }
                delay(100)
            }
        }
        gateway.connect()
    }

    fun select(row: Int, column: Int) {
        val cell = mutableState.value.board.getOrNull(row)?.getOrNull(column) ?: return
        if (cell.value == null && !cell.clearing && mutableState.value.penaltyRemainingMs == 0L) {
            mutableState.update { it.copy(selected = CellPosition(row, column), message = null) }
        }
    }

    fun place(value: Int) {
        val current = mutableState.value
        val selected = current.selected ?: return
        if (!current.canPlay || value !in 1..9) return

        val requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(pendingRequestId = requestId, message = null) }
        // No hay escritura optimista: esperamos al snapshot autoritativo. Esto
        // hace que una carrera se resuelva igual en todos los dispositivos.
        gateway.place(requestId, selected.row, selected.column, value, current.revision)
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun handleEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Connected -> mutableState.update { it.copy(connected = true, message = null) }
            RealtimeEvent.Disconnected -> mutableState.update {
                it.copy(connected = false, pendingRequestId = null, message = "Reconectando…")
            }
            is RealtimeEvent.Joined -> {
                applySnapshot(event.snapshot)
                mutableState.update { it.copy(playerId = event.playerId) }
            }
            is RealtimeEvent.StateUpdated -> applySnapshot(event.snapshot)
            is RealtimeEvent.MoveAccepted -> mutableState.update {
                if (it.pendingRequestId == event.requestId) it.copy(pendingRequestId = null, selected = null) else it
            }
            is RealtimeEvent.MoveRejected -> mutableState.update {
                if (it.pendingRequestId == event.requestId) {
                    it.copy(pendingRequestId = null, message = rejectionMessage(event.code, event.message))
                } else it
            }
            is RealtimeEvent.Penalty -> {
                blockedUntil = event.blockedUntil
                mutableState.update { it.copy(pendingRequestId = null, selected = null) }
            }
            is RealtimeEvent.Failure -> mutableState.update { it.copy(message = event.message) }
        }
    }

    private fun applySnapshot(snapshot: com.sudokuarena.domain.GameSnapshot) {
        serverClockOffsetMs = snapshot.serverTime - System.currentTimeMillis()
        val ownId = mutableState.value.playerId
        blockedUntil = snapshot.players.firstOrNull { it.id == ownId }?.blockedUntil ?: blockedUntil
        mutableState.update { current ->
            val selected = current.selected?.takeIf { position ->
                snapshot.board[position.row][position.column].let { it.value == null && !it.clearing }
            }
            current.copy(
                revision = snapshot.revision,
                board = snapshot.board,
                players = snapshot.players,
                selected = selected,
            )
        }
    }

    override fun onCleared() {
        gateway.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(gateway: GameRealtimeGateway): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ArenaViewModel(gateway) as T
            }
    }
}

private fun emptyBoard(): List<List<BoardCell>> =
    List(9) { List(9) { BoardCell() } }

private fun rejectionMessage(code: String, fallback: String): String = when (code) {
    "CELL_OCCUPIED" -> "Otro jugador llegó primero a esa casilla"
    "CELL_CLEARING" -> "Espera a que termine la conquista"
    "INCORRECT_VALUE" -> "Número incorrecto: bloqueo de 3 segundos"
    "BLOCKED" -> "Aún estás bloqueado"
    else -> fallback
}
