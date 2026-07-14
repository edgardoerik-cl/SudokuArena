package com.sudokuarena.data

import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.ActiveBoardEvent
import com.sudokuarena.domain.BoardEventType
import com.sudokuarena.domain.ConqueredSection
import com.sudokuarena.domain.GameRealtimeGateway
import com.sudokuarena.domain.GameSnapshot
import com.sudokuarena.domain.Player
import com.sudokuarena.domain.RealtimeEvent
import com.sudokuarena.domain.RoomConfig
import com.sudokuarena.domain.RoomPhase
import com.sudokuarena.domain.RoomState
import com.sudokuarena.domain.TeamMode
import com.sudokuarena.domain.TileType
import com.sudokuarena.domain.BotDifficulty
import com.sudokuarena.domain.MatchResultEntry
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject

class SocketGameClient(
    serverUrl: String,
    playerName: String,
) : GameRealtimeGateway {
    private val mutableEvents = MutableSharedFlow<RealtimeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RealtimeEvent> = mutableEvents

    private val socket: Socket = IO.socket(
        serverUrl,
        IO.Options.builder()
            .setTransports(arrayOf(WebSocket.NAME))
            .setReconnection(true)
            .setReconnectionAttempts(10)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(5_000)
            .setAuth(mapOf("name" to playerName))
            .build(),
    )

    init {
        // Los callbacks ocurren fuera del hilo principal. SharedFlow es seguro
        // para emisión concurrente y el ViewModel vuelve a su propio scope.
        socket.on(Socket.EVENT_CONNECT) { emit(RealtimeEvent.Connected) }
        socket.on(Socket.EVENT_DISCONNECT) { emit(RealtimeEvent.Disconnected) }
        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            emit(RealtimeEvent.Failure(args.firstOrNull()?.toString() ?: "Error de conexión"))
        }
        socket.on("game:joined") { args -> parseSafely(args) { payload ->
            RealtimeEvent.Joined(
                playerId = payload.getString("playerId"),
                roomCode = payload.getString("roomCode"),
                roomState = parseRoomState(payload.getJSONObject("roomState")),
                snapshot = parseSnapshot(payload.getJSONObject("state")),
            )
        } }
        socket.on("room:state") { args -> parseSafely(args) { payload ->
            RealtimeEvent.RoomStateUpdated(parseRoomState(payload))
        } }
        socket.on("game:finished") { args -> parseSafely(args) { payload ->
            val results = payload.getJSONArray("results").mapObjects { value ->
                val result = value as JSONObject
                MatchResultEntry(
                    rank = result.getInt("rank"),
                    playerId = result.getString("playerId"),
                    name = result.getString("name"),
                    score = result.getInt("score"),
                    teamId = result.getString("teamId"),
                    teamScore = result.getInt("teamScore"),
                    role = result.getString("role"),
                    isBot = result.optBoolean("isBot", false),
                )
            }
            RealtimeEvent.MatchFinished(results, payload.getLong("finishedAt"))
        } }
        socket.on("room:error") { args -> parseSafely(args) { payload ->
            RealtimeEvent.RoomError(
                code = payload.getString("code"),
                message = payload.getString("message"),
            )
        } }
        socket.on("game:state") { args -> parseSafely(args) { RealtimeEvent.StateUpdated(parseSnapshot(it)) } }
        socket.on("move:accepted") { args -> parseSafely(args) { payload ->
            RealtimeEvent.MoveAccepted(payload.getString("requestId"), payload.getLong("revision"))
        } }
        socket.on("move:rejected") { args -> parseSafely(args) { payload ->
            RealtimeEvent.MoveRejected(
                requestId = payload.optString("requestId"),
                code = payload.getString("code"),
                message = payload.getString("message"),
            )
        } }
        socket.on("player:penalty") { args -> parseSafely(args) { payload ->
            RealtimeEvent.Penalty(
                requestId = payload.optString("requestId"),
                blockedUntil = payload.getLong("blockedUntil"),
                reason = payload.getString("reason"),
            )
        } }
        socket.on("game:section-conquered") { args -> parseSafely(args) { payload ->
            val sections = payload.getJSONArray("sections").mapObjects { value ->
                val section = value as JSONObject
                ConqueredSection(
                    kind = section.getString("kind"),
                    index = section.getInt("index"),
                )
            }
            RealtimeEvent.SectionConquered(
                playerId = payload.getString("playerId"),
                sections = sections,
                bonus = payload.getInt("bonus"),
            )
        } }
        socket.on("power_received") { args -> parseSafely(args) { payload ->
            RealtimeEvent.PowerReceived(
                attackerId = payload.getString("attackerId"),
                type = payload.getString("type"),
                reflected = payload.optBoolean("reflected", false),
                reflectedBy = payload.optString("reflectedBy").takeIf(String::isNotBlank),
            )
        } }
        socket.on("power_used") { args -> parseSafely(args) { payload ->
            RealtimeEvent.PowerUsed(
                type = payload.getString("type"),
                reflected = payload.optBoolean("reflected", false),
            )
        } }
        socket.on("power_reflected") { args -> parseSafely(args) { payload ->
            RealtimeEvent.PowerReflected(payload.getString("attackerId"))
        } }
        socket.on("power_rejected") { args -> parseSafely(args) { payload ->
            RealtimeEvent.PowerRejected(payload.getString("message"))
        } }
        socket.on("board_event_start") { args -> parseSafely(args) { payload ->
            RealtimeEvent.BoardEventStarted(parseBoardEvent(payload))
        } }
        socket.on("board_event_end") { args -> parseSafely(args) { payload ->
            RealtimeEvent.BoardEventEnded(
                payload.optString("eventType").takeIf(String::isNotBlank)?.let(BoardEventType::valueOf),
            )
        } }
        socket.on("reaction_received") { args -> parseSafely(args) { payload ->
            RealtimeEvent.ReactionReceived(
                reactionId = payload.getString("reactionId"),
                playerId = payload.getString("playerId"),
                emojiId = payload.getString("emojiId"),
            )
        } }
        socket.on("reaction_rejected") { args ->
            val message = (args.firstOrNull() as? JSONObject)?.optString("message") ?: "Reacción no permitida"
            emit(RealtimeEvent.Failure(message))
        }
        socket.on("arena:full") { args ->
            val message = (args.firstOrNull() as? JSONObject)?.optString("message") ?: "Arena llena"
            emit(RealtimeEvent.Failure(message))
        }
    }

    override fun connect() {
        if (!socket.connected()) socket.connect()
    }

    override fun disconnect() {
        socket.disconnect()
    }

    override fun createRoom() {
        socket.emit("room:create")
    }

    override fun joinRoom(roomCode: String) {
        socket.emit("room:join", JSONObject().put("roomCode", roomCode))
    }

    override fun configureRoom(config: RoomConfig) {
        socket.emit(
            "room:configure",
            JSONObject()
                .put("powersEnabled", config.powersEnabled)
                .put("teamMode", config.teamMode.name)
                .put("tileType", config.tileType.name)
                .put("botDifficulty", config.botDifficulty.name),
        )
    }

    override fun startRoom() {
        socket.emit("room:start")
    }

    override fun fillWithAi() {
        socket.emit("fill_with_ai")
    }

    override fun place(
        requestId: String,
        row: Int,
        column: Int,
        value: Int,
        clientRevision: Long,
    ) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("row", row)
            .put("column", column)
            .put("value", value)
            .put("clientRevision", clientRevision)
        socket.emit("player:place", payload)
    }

    override fun usePower(
        type: String,
        targetPlayerId: String?,
        row: Int?,
        column: Int?,
        requestId: String?,
    ) {
        val payload = JSONObject().put("type", type)
        targetPlayerId?.let { payload.put("targetPlayerId", it) }
        row?.let { payload.put("row", it) }
        column?.let { payload.put("column", it) }
        requestId?.let { payload.put("requestId", it) }
        socket.emit("use_power", payload)
    }

    override fun sendReaction(emojiId: String) {
        socket.emit("send_reaction", JSONObject().put("emojiId", emojiId))
    }

    private fun parseSafely(args: Array<out Any>, parser: (JSONObject) -> RealtimeEvent) {
        runCatching { parser(args.first() as JSONObject) }
            .onSuccess(::emit)
            .onFailure { emit(RealtimeEvent.Failure("Payload inválido: ${it.message}")) }
    }

    private fun emit(event: RealtimeEvent) {
        mutableEvents.tryEmit(event)
    }
}

private fun parseSnapshot(json: JSONObject): GameSnapshot {
    val boardJson = json.getJSONArray("board")
    require(boardJson.length() == 9) { "El tablero debe tener 9 filas" }
    val board = boardJson.mapObjects { rowJson ->
        val row = rowJson as JSONArray
        require(row.length() == 9) { "Cada fila debe tener 9 casillas" }
        row.mapObjects { cellValue ->
            val cell = cellValue as JSONObject
            BoardCell(
                value = if (cell.isNull("value")) null else cell.getInt("value"),
                ownerId = if (cell.isNull("ownerId")) null else cell.getString("ownerId"),
                clearing = cell.getBoolean("clearing"),
                golden = cell.optBoolean("golden", false),
                given = false,
                ownerTeamId = if (cell.isNull("ownerTeamId")) null else cell.optString("ownerTeamId"),
            )
        }
    }
    val players = json.getJSONArray("players").mapObjects { value ->
        val player = value as JSONObject
        Player(
            id = player.getString("id"),
            name = player.getString("name"),
            slot = player.getInt("slot"),
            colorHex = player.getString("color"),
            score = player.getInt("score"),
            blockedUntil = player.getLong("blockedUntil"),
            energy = player.optInt("energy", 0),
            teamId = player.optString("teamId", "PLAYER:${player.getString("id")}"),
            role = player.optString("role", "PLAYER"),
            teamScore = player.optInt("teamScore", player.getInt("score")),
            isBot = player.optBoolean("isBot", false),
            shieldUntil = player.optLong("shieldUntil", 0),
        )
    }
    return GameSnapshot(
        gameId = json.getString("gameId"),
        revision = json.getLong("revision"),
        serverTime = json.getLong("serverTime"),
        board = board,
        players = players,
        boardEvent = json.optJSONObject("boardEvent")?.let(::parseBoardEvent),
    )
}

private fun parseBoardEvent(json: JSONObject): ActiveBoardEvent = ActiveBoardEvent(
    type = BoardEventType.valueOf(json.getString(if (json.has("type")) "type" else "eventType")),
    startedAt = json.getLong("startedAt"),
    endsAt = json.getLong("endsAt"),
)

private fun parseRoomState(json: JSONObject): RoomState {
    val config = json.getJSONObject("config")
    return RoomState(
        roomCode = json.getString("roomCode"),
        hostPlayerId = json.getString("hostPlayerId"),
        config = RoomConfig(
            powersEnabled = config.getBoolean("powersEnabled"),
            teamMode = TeamMode.valueOf(config.getString("teamMode")),
            tileType = TileType.valueOf(config.optString("tileType", "NUMBERS")),
            botDifficulty = BotDifficulty.valueOf(config.optString("botDifficulty", "MEDIUM")),
        ),
        phase = RoomPhase.valueOf(json.getString("phase")),
        startedAt = if (json.isNull("startedAt")) null else json.getLong("startedAt"),
        endsAt = if (json.isNull("endsAt")) null else json.getLong("endsAt"),
    )
}

private fun <T> JSONArray.mapObjects(transform: (Any) -> T): List<T> =
    List(length()) { index -> transform(get(index)) }
