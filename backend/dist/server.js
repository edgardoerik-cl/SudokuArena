import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { Server } from "socket.io";
import { BOARD_EVENT_DURATION_MS, BOARD_EVENT_INTERVAL_MS, APP_VERSION, CLEAR_DELAY_MS, MATCH_DURATION_MS, createRandomSolution } from "./constants.js";
import { ArenaGame } from "./game.js";
const port = Number(process.env.PORT ?? 3000);
const allowedOrigin = process.env.CORS_ORIGIN ?? "*";
const requestedMatchDuration = Number(process.env.MATCH_DURATION_MS ?? MATCH_DURATION_MS);
const matchDurationMs = Number.isFinite(requestedMatchDuration) && requestedMatchDuration > 0
    ? requestedMatchDuration
    : MATCH_DURATION_MS;
const rooms = new Map();
const httpServer = createServer((request, response) => {
    if (request.url === "/health") {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({
            ok: true,
            version: APP_VERSION,
            rooms: rooms.size,
            players: [...rooms.values()].reduce((total, room) => total + room.game.playerCount, 0)
        }));
        return;
    }
    response.writeHead(404).end();
});
const io = new Server(httpServer, {
    cors: { origin: allowedOrigin },
    transports: ["websocket", "polling"]
});
const reactionEmojis = new Set(["LAUGH", "CRY", "ANGRY", "SURPRISED"]);
const boardEventInterval = setInterval(() => {
    for (const room of rooms.values())
        if (room.phase === "PLAYING")
            startRandomBoardEvent(room);
}, BOARD_EVENT_INTERVAL_MS);
io.on("connection", (socket) => {
    const requestedName = String(socket.handshake.auth.name ?? socket.handshake.query.name ?? "");
    let lastReactionAt = 0;
    socket.on("room:create", () => {
        if (socket.data.roomCode)
            return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
        const room = createRoom(socket.id);
        socket.emit("room:created", { roomCode: room.code });
        joinRoom(socket, room, requestedName);
    });
    socket.on("room:join", (payload) => {
        if (socket.data.roomCode)
            return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
        const roomCode = normalizeRoomCode(payload?.roomCode);
        if (!roomCode)
            return emitRoomError(socket, "INVALID_CODE", "Ingresa un código de 4 dígitos");
        const room = rooms.get(roomCode);
        if (!room)
            return emitRoomError(socket, "ROOM_NOT_FOUND", "La sala no existe o ya terminó");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        joinRoom(socket, room, requestedName);
    });
    socket.on("room:configure", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.hostPlayerId !== socket.id)
            return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede configurar");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        const teamMode = normalizeTeamMode(payload?.teamMode);
        if (!teamMode || typeof payload?.powersEnabled !== "boolean") {
            return emitRoomError(socket, "INVALID_CONFIG", "Configuración de sala inválida");
        }
        room.config = { powersEnabled: payload.powersEnabled, teamMode };
        emitRoomState(room);
    });
    socket.on("room:start", () => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.hostPlayerId !== socket.id)
            return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede iniciar");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        const minimumError = validatePlayerCount(room);
        if (minimumError)
            return emitRoomError(socket, "INVALID_PLAYER_COUNT", minimumError);
        startMatch(room);
    });
    socket.on("player:place", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.phase !== "PLAYING") {
            socket.emit("move:rejected", {
                requestId: typeof payload?.requestId === "string" ? payload.requestId : "",
                code: "MATCH_NOT_PLAYING",
                message: "La partida todavía no está en curso"
            });
            return;
        }
        // Sin await: el event loop conserva el orden autoritativo dentro de la sala.
        const result = room.game.place(socket.id, payload);
        if (!result.accepted) {
            socket.emit("move:rejected", {
                requestId: result.requestId,
                code: result.code,
                message: result.message
            });
            if (result.blockedUntil !== undefined) {
                socket.emit("player:penalty", {
                    requestId: result.requestId,
                    blockedUntil: result.blockedUntil,
                    reason: result.code
                });
            }
            if (result.stateChanged)
                emitState(room);
            return;
        }
        socket.emit("move:accepted", {
            requestId: result.requestId,
            revision: result.revision,
            cellPoints: result.cellPoints,
            goldenBonus: result.goldenBonus
        });
        emitState(room);
        if (result.clearPlan) {
            const clearAt = Date.now() + CLEAR_DELAY_MS;
            io.to(room.code).emit("game:section-conquered", {
                playerId: socket.id,
                sections: result.sections,
                bonus: result.bonus,
                clearAt
            });
            const plan = result.clearPlan;
            setTimeout(() => {
                if (room.game.executeClear(plan) && rooms.get(room.code) === room)
                    emitState(room);
            }, CLEAR_DELAY_MS);
        }
    });
    socket.on("use_power", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.phase !== "PLAYING")
            return emitRoomError(socket, "MATCH_NOT_PLAYING", "La partida no está en curso");
        const result = room.game.useFogPower(socket.id, payload?.targetPlayerId);
        if (!result.accepted) {
            socket.emit("power_rejected", { code: result.code, message: result.message });
            return;
        }
        socket.emit("power_used", { type: result.type, targetPlayerId: result.targetPlayerId });
        io.to(result.targetPlayerId).emit("power_received", {
            type: result.type,
            attackerId: result.attackerId
        });
        emitState(room);
    });
    socket.on("send_reaction", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (!payload || !reactionEmojis.has(payload.emojiId)) {
            socket.emit("reaction_rejected", { message: "Reacción no permitida" });
            return;
        }
        const now = Date.now();
        if (now - lastReactionAt < 500) {
            socket.emit("reaction_rejected", { message: "Espera un instante antes de reaccionar otra vez" });
            return;
        }
        lastReactionAt = now;
        io.to(room.code).emit("reaction_received", {
            reactionId: randomUUID(),
            playerId: socket.id,
            emojiId: payload.emojiId,
            sentAt: now
        });
    });
    socket.on("disconnect", () => leaveCurrentRoom(socket));
});
function createRoom(hostPlayerId) {
    let code;
    do
        code = String(Math.floor(1_000 + Math.random() * 9_000));
    while (rooms.has(code));
    const room = {
        code,
        game: new ArenaGame(`arena-${code}`, createRandomSolution()),
        boardEventTimeout: null,
        matchTimeout: null,
        hostPlayerId,
        config: { powersEnabled: true, teamMode: "FFA" },
        phase: "LOBBY",
        startedAt: null,
        endsAt: null
    };
    rooms.set(code, room);
    return room;
}
function joinRoom(socket, room, playerName) {
    const player = room.game.addPlayer(socket.id, playerName);
    if (!player) {
        emitRoomError(socket, "ROOM_FULL", "La sala ya tiene 4 jugadores");
        return;
    }
    socket.data.roomCode = room.code;
    socket.join(room.code);
    socket.emit("room:joined", { roomCode: room.code });
    socket.emit("game:joined", {
        playerId: player.id,
        roomCode: room.code,
        roomState: toRoomState(room),
        state: room.game.snapshot()
    });
    emitState(room);
    emitRoomState(room);
}
function leaveCurrentRoom(socket) {
    const room = roomFor(socket);
    if (!room)
        return;
    delete socket.data.roomCode;
    if (room.game.removePlayer(socket.id))
        emitState(room);
    if (room.game.playerCount === 0) {
        if (room.boardEventTimeout)
            clearTimeout(room.boardEventTimeout);
        if (room.matchTimeout)
            clearTimeout(room.matchTimeout);
        rooms.delete(room.code);
    }
    else if (room.hostPlayerId === socket.id) {
        room.hostPlayerId = room.game.snapshot().players[0].id;
        emitRoomState(room);
    }
}
function roomFor(socket) {
    const code = socket.data.roomCode;
    return typeof code === "string" ? rooms.get(code) ?? null : null;
}
function emitState(room) {
    io.to(room.code).emit("game:state", room.game.snapshot());
}
function emitRoomState(room) {
    io.to(room.code).emit("room:state", toRoomState(room));
}
function toRoomState(room) {
    return {
        roomCode: room.code,
        hostPlayerId: room.hostPlayerId,
        config: { ...room.config },
        phase: room.phase,
        startedAt: room.startedAt,
        endsAt: room.endsAt
    };
}
function emitRoomError(socket, code, message) {
    socket.emit("room:error", { code, message });
}
function normalizeRoomCode(value) {
    const code = typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
    return /^\d{4}$/.test(code) ? code : null;
}
function normalizeTeamMode(value) {
    return value === "FFA" || value === "TWO_V_TWO" || value === "THREE_V_ONE" ? value : null;
}
function validatePlayerCount(room) {
    const count = room.game.playerCount;
    if (room.config.teamMode === "FFA" && count < 2)
        return "Se necesitan al menos 2 jugadores";
    if (room.config.teamMode === "TWO_V_TWO" && count !== 4)
        return "El modo 2 vs 2 requiere exactamente 4 jugadores";
    if (room.config.teamMode === "THREE_V_ONE" && count !== 4)
        return "El modo 3 vs 1 requiere exactamente 4 jugadores";
    return null;
}
function startMatch(room) {
    room.phase = "PLAYING";
    room.startedAt = Date.now();
    room.endsAt = room.startedAt + matchDurationMs;
    room.game.startMatch(room.config, room.hostPlayerId);
    emitRoomState(room);
    emitState(room);
    io.to(room.code).emit("game:started", { startedAt: room.startedAt, endsAt: room.endsAt });
    room.matchTimeout = setTimeout(() => finishMatch(room), matchDurationMs);
}
function finishMatch(room) {
    if (rooms.get(room.code) !== room || room.phase !== "PLAYING")
        return;
    room.phase = "FINISHED";
    room.endsAt = Date.now();
    if (room.boardEventTimeout)
        clearTimeout(room.boardEventTimeout);
    room.boardEventTimeout = null;
    room.game.endBoardEvent();
    const results = room.game.matchResults();
    emitRoomState(room);
    emitState(room);
    io.to(room.code).emit("game:finished", { results, finishedAt: room.endsAt });
    room.matchTimeout = null;
}
function startRandomBoardEvent(room) {
    const type = Math.random() < 0.5 ? "MIRROR_HOUR" : "GOLDEN_CELLS";
    const event = room.game.startBoardEvent(type, Date.now(), BOARD_EVENT_DURATION_MS);
    if (!event)
        return;
    io.to(room.code).emit("board_event_start", {
        eventType: event.type,
        startedAt: event.startedAt,
        endsAt: event.endsAt
    });
    emitState(room);
    room.boardEventTimeout = setTimeout(() => {
        if (rooms.get(room.code) !== room || !room.game.endBoardEvent(event.type))
            return;
        io.to(room.code).emit("board_event_end", { eventType: event.type });
        emitState(room);
        room.boardEventTimeout = null;
    }, BOARD_EVENT_DURATION_MS);
}
httpServer.listen(port, "0.0.0.0", () => {
    console.log(`Sudoku Arena escuchando en :${port}`);
});
function shutdown() {
    clearInterval(boardEventInterval);
    for (const room of rooms.values()) {
        if (room.boardEventTimeout)
            clearTimeout(room.boardEventTimeout);
        if (room.matchTimeout)
            clearTimeout(room.matchTimeout);
    }
    io.close(() => process.exit(0));
}
process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
//# sourceMappingURL=server.js.map