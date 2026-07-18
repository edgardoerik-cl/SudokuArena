package com.sudokuarena.data

import com.sudokuarena.domain.BoardCell
import com.sudokuarena.domain.ActiveBoardEvent
import com.sudokuarena.domain.BoardEventType
import com.sudokuarena.domain.ConqueredSection
import com.sudokuarena.domain.GameRealtimeGateway
import com.sudokuarena.domain.GameSnapshot
import com.sudokuarena.domain.GameChatMessage
import com.sudokuarena.domain.Player
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
import com.sudokuarena.domain.GenericCell
import com.sudokuarena.domain.AbyssActor
import com.sudokuarena.domain.AbyssItem
import com.sudokuarena.domain.AbyssObstacle
import com.sudokuarena.domain.AbyssProjectile
import com.sudokuarena.domain.AbyssState
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
    clientId: String,
    avatarId: String,
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
            .setAuth(mapOf("name" to playerName, "clientId" to clientId, "avatarId" to avatarId))
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
                    maxCombo = result.optInt("maxCombo", 0),
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
        socket.on("generic:state") { args -> parseSafely(args) { RealtimeEvent.GenericStateUpdated(parseGenericState(it)) } }
        socket.on("abyss:state") { args -> parseSafely(args) { RealtimeEvent.AbyssStateUpdated(parseAbyssState(it)) } }
        socket.on("letters:rack") { args -> parseSafely(args) { payload ->
            RealtimeEvent.LetterRackUpdated(
                letters = payload.optJSONArray("letters")?.let { array -> List(array.length()) { index -> array.optString(index) } }.orEmpty(),
                activePlayerId = payload.nullableString("activePlayerId"),
                turnEndsAt = payload.optLong("turnEndsAt", 0L),
            )
        } }
        socket.on("secret:role-state") { args -> parseSafely(args) { payload ->
            val clue = payload.optJSONObject("clue")
            RealtimeEvent.SecretRoleUpdated(
                team = payload.optString("team"), role = payload.optString("role"),
                key = payload.optJSONArray("key")?.let { array -> List(array.length()) { index -> array.optString(index) } }.orEmpty(),
                currentTeam = payload.optString("currentTeam"),
                clue = clue?.nullableString("word"),
                clueCount = clue?.optInt("remaining", 0) ?: 0,
            )
        } }
        socket.on("secret:chat-message") { args -> parseSafely(args) { payload ->
            RealtimeEvent.SecretChatMessage(payload.optString("playerId"), payload.optString("message"), payload.optBoolean("penalized"))
        } }
        socket.on("secret:chat-locked") { args -> parseSafely(args) { payload ->
            RealtimeEvent.SecretChatLocked(payload.optLong("blockedUntil"))
        } }
        socket.on("global:chat-message") { args -> parseSafely(args) { payload ->
            RealtimeEvent.GlobalChatReceived(
                GameChatMessage(
                    id = payload.getString("id"),
                    playerId = payload.getString("playerId"),
                    message = payload.getString("message"),
                    sentAt = payload.optLong("sentAt", System.currentTimeMillis()),
                ),
            )
        } }
        socket.on("rps:started") { args -> parseSafely(args) { payload ->
            RealtimeEvent.RpsStarted(payload.optInt("round", 1), payload.optLong("endsAt"))
        } }
        socket.on("rps:result") { args -> parseSafely(args) { payload ->
            val choices = payload.optJSONObject("choices")?.toKotlinMap()
                ?.mapValues { it.value.toString() }.orEmpty()
            RealtimeEvent.RpsResult(
                round = payload.optInt("round", 1),
                choices = choices,
                winnerId = payload.nullableString("winnerId"),
                tie = payload.optBoolean("tie"),
            )
        } }
        socket.on("generic:move-accepted") { args -> parseSafely(args) { payload ->
            RealtimeEvent.GenericMoveAccepted(
                requestId = payload.getString("requestId"),
                points = payload.optInt("points", 0),
                completed = payload.optBoolean("completed", false),
            )
        } }
        socket.on("generic:move-rejected") { args -> parseSafely(args) { payload ->
            RealtimeEvent.GenericMoveRejected(
                requestId = payload.optString("requestId"),
                code = payload.optString("code", "INVALID_MOVE"),
                message = payload.optString("message", "Movimiento no permitido"),
            )
        } }
        socket.on("game:sudden-death") { args -> parseSafely(args) { payload ->
            RealtimeEvent.SuddenDeath(payload.getLong("endsAt"))
        } }
        socket.on("move:accepted") { args -> parseSafely(args) { payload ->
            RealtimeEvent.MoveAccepted(
                requestId = payload.getString("requestId"),
                revision = payload.getLong("revision"),
                combo = payload.optInt("combo", 1),
                comboMultiplier = payload.optInt("comboMultiplier", 1),
                comboBonus = payload.optInt("comboBonus", 0),
            )
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
                reflectedBy = payload.nullableString("reflectedBy"),
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
                .put("gameType", config.gameType.name)
                .put("teamMode", config.teamMode.name)
                .put("tileType", config.tileType.name)
                .put("botDifficulty", config.botDifficulty.name)
                .put("puzzleDifficulty", config.puzzleDifficulty.name),
        )
    }

    override fun startRoom() {
        socket.emit("room:start")
    }

    override fun fillWithAi() {
        socket.emit("fill_with_ai")
    }

    override fun requestRematch() {
        socket.emit("room:rematch")
    }

    override fun setPowerLoadout(powers: List<String>) {
        socket.emit("player:loadout", JSONObject().put("powers", JSONArray(powers)))
    }

    override fun makeMove(requestId: String, row: Int, col: Int, value: Any?) {
        socket.emit(
            "make_move",
            JSONObject()
                .put("requestId", requestId)
                .put("row", row)
                .put("col", col)
                .put("val", JSONObject.wrap(value)),
        )
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

    override fun sendSecretChat(message: String) {
        socket.emit("secret:chat-send", JSONObject().put("message", message))
    }

    override fun sendGlobalChat(message: String) {
        socket.emit("global:chat-send", JSONObject().put("message", message))
    }

    override fun sendAbyssInput(
        sequence: Long,
        moveX: Float,
        moveY: Float,
        aimX: Float,
        aimY: Float,
        shooting: Boolean,
    ) {
        socket.emit(
            "abyss:input",
            JSONObject()
                .put("sequence", sequence)
                .put("moveX", moveX)
                .put("moveY", moveY)
                .put("aimX", aimX)
                .put("aimY", aimY)
                .put("shooting", shooting),
        )
    }

    override fun chooseRps(choice: String) {
        socket.emit("rps:choose", JSONObject().put("choice", choice))
    }

    override fun requestPause() { socket.emit("pause:request") }

    override fun respondPause(accepted: Boolean) {
        socket.emit("pause:respond", JSONObject().put("accepted", accepted))
    }

    override fun resumePausedGame() { socket.emit("pause:resume") }

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
                given = cell.optBoolean("given", false),
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
            combo = player.optInt("combo", 0),
            maxCombo = player.optInt("maxCombo", 0),
            comboMultiplier = player.optInt("comboMultiplier", 1),
            botPersona = player.nullableString("botPersona"),
            powerLoadout = player.optJSONArray("powerLoadout")?.mapObjects { it.toString() }
                ?: listOf("FOG", "REVEAL"),
            avatarId = player.optString("avatarId", "ORBIT"),
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
            gameType = GameType.valueOf(config.optString("gameType", "SUDOKU")),
            powersEnabled = config.getBoolean("powersEnabled"),
            teamMode = TeamMode.valueOf(config.getString("teamMode")),
            tileType = TileType.valueOf(config.optString("tileType", "NUMBERS")),
            botDifficulty = BotDifficulty.valueOf(config.optString("botDifficulty", "MEDIUM")),
            puzzleDifficulty = PuzzleDifficulty.valueOf(config.optString("puzzleDifficulty", "MEDIUM")),
        ),
        phase = RoomPhase.valueOf(json.getString("phase")),
        startedAt = if (json.isNull("startedAt")) null else json.getLong("startedAt"),
        endsAt = if (json.isNull("endsAt")) null else json.getLong("endsAt"),
        suddenDeath = json.optBoolean("suddenDeath", false),
        rematchVotes = json.optInt("rematchVotes", 0),
        // JSONObject.NULL se serializa como la cadena "null" en algunas
        // versiones de org.json. Nunca debe activar la UI de pausa.
        pauseRequesterId = json.nullableString("pauseRequesterId"),
        pauseVotes = json.optInt("pauseVotes", 0),
        pauseNoVotes = json.optInt("pauseNoVotes", 0),
        pauseRequired = json.optInt("pauseRequired", 0),
        resumeCountdownEndsAt = if (json.isNull("resumeCountdownEndsAt")) null else json.optLong("resumeCountdownEndsAt"),
    )
}

private fun parseGenericState(json: JSONObject): GenericBoardState {
    val boardJson = json.getJSONArray("board")
    val board = boardJson.mapObjects { rawRow ->
        (rawRow as JSONArray).mapObjects { rawCell ->
            val cell = rawCell as JSONObject
            GenericCell(
                value = cell.opt("value").takeUnless { it == JSONObject.NULL },
                isRevealed = cell.optBoolean("isRevealed", false),
                // Causa raíz del bloqueo global: JSONObject.NULL podía terminar
                // convertido en "null", haciendo que toda celda pareciera ocupada.
                ownerId = cell.nullableString("ownerId"),
                isBlocked = cell.optBoolean("isBlocked", false),
                meta = cell.optJSONObject("meta")?.toKotlinMap().orEmpty(),
            )
        }
    }
    return GenericBoardState(
        gameId = json.getString("gameId"),
        gameType = GameType.valueOf(json.getString("gameType")),
        revision = json.getLong("revision"),
        serverTime = json.getLong("serverTime"),
        rows = json.getInt("rows"),
        columns = json.getInt("columns"),
        board = board,
        completed = json.optBoolean("completed", false),
        meta = json.optJSONObject("meta")?.toKotlinMap().orEmpty(),
    )
}

private fun parseAbyssState(json: JSONObject): AbyssState {
    fun number(value: JSONObject, key: String) = value.optDouble(key, 0.0).toFloat()
    val actors = json.optJSONArray("actors")?.mapObjects { raw ->
        val item = raw as JSONObject
        AbyssActor(
            id = item.optString("id"),
            kind = item.optString("kind"),
            x = number(item, "x"),
            y = number(item, "y"),
            hp = number(item, "hp"),
            maxHp = number(item, "maxHp"),
            colorHex = item.nullableString("colorHex"),
            name = item.nullableString("name"),
        )
    }.orEmpty()
    val projectiles = json.optJSONArray("projectiles")?.mapObjects { raw ->
        val item = raw as JSONObject
        AbyssProjectile(item.optString("id"), number(item, "x"), number(item, "y"))
    }.orEmpty()
    val items = json.optJSONArray("items")?.mapObjects { raw ->
        val item = raw as JSONObject
        AbyssItem(item.optString("id"), number(item, "x"), number(item, "y"), item.optString("type"))
    }.orEmpty()
    val obstacles = json.optJSONObject("room")?.optJSONArray("obstacles")?.mapObjects { raw ->
        val item = raw as JSONObject
        AbyssObstacle(number(item, "x"), number(item, "y"), number(item, "width"), number(item, "height"))
    }.orEmpty()
    return AbyssState(
        serverTime = json.optLong("serverTime"),
        tick = json.optLong("tick"),
        level = json.optInt("level", 1),
        maxLevel = json.optInt("maxLevel", 20),
        bossLevel = json.optBoolean("bossLevel"),
        completed = json.optBoolean("completed"),
        actors = actors,
        projectiles = projectiles,
        items = items,
        obstacles = obstacles,
    )
}

private fun JSONObject.toKotlinMap(): Map<String, Any?> = keys().asSequence().associateWith { key -> jsonValueToKotlin(opt(key)) }

private fun JSONObject.nullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.toKotlinMap()
    is JSONArray -> List(value.length()) { index -> jsonValueToKotlin(value.opt(index)) }
    else -> value
}

private fun <T> JSONArray.mapObjects(transform: (Any) -> T): List<T> =
    List(length()) { index -> transform(get(index)) }
