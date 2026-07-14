import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { Server } from "socket.io";
import { BOARD_EVENT_DURATION_MS, BOARD_EVENT_INTERVAL_MS, APP_VERSION, CLEAR_DELAY_MS, MATCH_DURATION_MS, SUDDEN_DEATH_DURATION_MS, createRandomSolution } from "./constants.js";
import { ArenaGame } from "./game.js";
import { LeaderboardStore, sanitizeNickname } from "./leaderboard.js";
const port = Number(process.env.PORT ?? 3000);
const allowedOrigin = process.env.CORS_ORIGIN ?? "*";
const requestedMatchDuration = Number(process.env.MATCH_DURATION_MS ?? MATCH_DURATION_MS);
const matchDurationMs = Number.isFinite(requestedMatchDuration) && requestedMatchDuration > 0
    ? requestedMatchDuration
    : MATCH_DURATION_MS;
const requestedSuddenDeathDuration = Number(process.env.SUDDEN_DEATH_DURATION_MS ?? SUDDEN_DEATH_DURATION_MS);
const suddenDeathDurationMs = Number.isFinite(requestedSuddenDeathDuration) && requestedSuddenDeathDuration > 0
    ? requestedSuddenDeathDuration
    : SUDDEN_DEATH_DURATION_MS;
const rooms = new Map();
const disconnectTimers = new Map();
const leaderboard = new LeaderboardStore();
const soloChallenges = new Map();
const httpServer = createServer((request, response) => void handleHttp(request, response));
const io = new Server(httpServer, {
    cors: { origin: allowedOrigin },
    transports: ["websocket", "polling"]
});
const reactionEmojis = new Set(["LAUGH", "CRY", "ANGRY", "SURPRISED"]);
const botNames = ["Bot_Androide", "Bot_Pro", "Bot_Neón", "Bot_Lógico", "Bot_Turbo", "Bot_Arena"];
const boardEventInterval = setInterval(() => {
    for (const room of rooms.values())
        if (room.phase === "PLAYING")
            startRandomBoardEvent(room);
}, BOARD_EVENT_INTERVAL_MS);
io.on("connection", (socket) => {
    const requestedName = String(socket.handshake.auth.name ?? socket.handshake.query.name ?? "");
    const clientId = normalizeClientId(socket.handshake.auth.clientId);
    const playerId = clientId ? `human:${clientId}` : socket.id;
    socket.data.playerId = playerId;
    socket.join(playerId);
    let lastReactionAt = 0;
    socket.on("room:create", () => {
        if (socket.data.roomCode)
            return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
        const room = createRoom(playerId);
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
        if (room.phase !== "LOBBY" && !room.game.hasPlayer(playerId)) {
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        }
        joinRoom(socket, room, requestedName);
    });
    socket.on("room:configure", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.hostPlayerId !== playerId)
            return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede configurar");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        const teamMode = normalizeTeamMode(payload?.teamMode);
        // tileType ausente conserva la opción actual para clientes 0.6 instalados.
        const tileType = payload?.tileType === undefined
            ? room.config.tileType
            : normalizeTileType(payload.tileType);
        const botDifficulty = payload?.botDifficulty === undefined
            ? room.config.botDifficulty
            : normalizeBotDifficulty(payload.botDifficulty);
        if (!teamMode || !tileType || !botDifficulty || typeof payload?.powersEnabled !== "boolean") {
            return emitRoomError(socket, "INVALID_CONFIG", "Configuración de sala inválida");
        }
        room.config = { powersEnabled: payload.powersEnabled, teamMode, tileType, botDifficulty };
        emitRoomState(room);
    });
    socket.on("fill_with_ai", () => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.hostPlayerId !== playerId)
            return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede añadir Bots");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        fillRoomWithBots(room);
    });
    socket.on("player:loadout", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "El equipamiento ya está cerrado");
        const powers = Array.isArray(payload?.powers) ? payload.powers : [];
        if (!room.game.setPowerLoadout(playerId, powers)) {
            return emitRoomError(socket, "INVALID_LOADOUT", "Elige exactamente dos poderes distintos");
        }
        emitState(room);
    });
    socket.on("room:start", () => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.hostPlayerId !== playerId)
            return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede iniciar");
        if (room.phase !== "LOBBY")
            return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
        const minimumError = validatePlayerCount(room);
        if (minimumError)
            return emitRoomError(socket, "INVALID_PLAYER_COUNT", minimumError);
        startMatch(room);
    });
    socket.on("room:rematch", () => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.phase !== "FINISHED")
            return emitRoomError(socket, "MATCH_NOT_FINISHED", "La partida aún no termina");
        room.rematchVotes.add(playerId);
        emitRoomState(room);
        const humans = room.game.snapshot().players.filter((player) => !player.isBot);
        if (humans.length > 0 && humans.every((player) => room.rematchVotes.has(player.id)))
            startMatch(room, true);
    });
    socket.on("player:place", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH") {
            socket.emit("move:rejected", {
                requestId: typeof payload?.requestId === "string" ? payload.requestId : "",
                code: "MATCH_NOT_PLAYING",
                message: "La partida todavía no está en curso"
            });
            return;
        }
        // Humanos y Bots atraviesan exactamente el mismo pipeline autoritativo.
        processPlacement(room, playerId, payload, socket);
    });
    socket.on("use_power", (payload) => {
        const room = roomFor(socket);
        if (!room)
            return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
        if (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH") {
            return emitRoomError(socket, "MATCH_NOT_PLAYING", "La partida no está en curso");
        }
        const powerType = payload?.type ?? "FOG";
        if (powerType !== "FOG" && powerType !== "REFLECT" && powerType !== "REVEAL") {
            socket.emit("power_rejected", { code: "INVALID_POWER", message: "Poder no permitido" });
            return;
        }
        const result = powerType === "REFLECT"
            ? room.game.useReflectPower(playerId)
            : powerType === "REVEAL"
                ? room.game.useRevealPower(playerId, payload?.row, payload?.column, payload?.requestId)
                : room.game.useFogPower(playerId, payload?.targetPlayerId);
        if (!result.accepted) {
            socket.emit("power_rejected", { code: result.code, message: result.message });
            return;
        }
        if (result.type === "FOG") {
            socket.emit("power_used", {
                type: result.type,
                targetPlayerId: result.targetPlayerId,
                reflected: result.reflected
            });
            applyFogDelivery(room, result.attackerId, result.recipientPlayerId, result.reflected ? result.targetPlayerId : undefined);
            emitState(room);
            return;
        }
        if (result.type === "REFLECT") {
            socket.emit("power_used", { type: result.type, shieldUntil: result.shieldUntil });
            emitState(room);
            return;
        }
        socket.emit("power_used", { type: result.type, row: payload.row, column: payload.column });
        publishAcceptedPlacement(room, playerId, result.placement);
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
            playerId,
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
        config: { powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" },
        phase: "LOBBY",
        startedAt: null,
        endsAt: null,
        bots: new Map(),
        rematchVotes: new Set(),
        suddenDeath: false
    };
    rooms.set(code, room);
    return room;
}
function joinRoom(socket, room, playerName) {
    const playerId = String(socket.data.playerId ?? socket.id);
    const previousDisconnect = disconnectTimers.get(playerId);
    if (previousDisconnect) {
        clearTimeout(previousDisconnect);
        disconnectTimers.delete(playerId);
    }
    const existingPlayer = room.game.snapshot().players.find((entry) => entry.id === playerId);
    const player = existingPlayer ?? room.game.addPlayer(playerId, playerName);
    if (!player) {
        emitRoomError(socket, "ROOM_FULL", "La sala ya tiene 4 jugadores");
        return;
    }
    socket.data.roomCode = room.code;
    socket.join(room.code);
    socket.join(playerId);
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
    const playerId = String(socket.data.playerId ?? socket.id);
    delete socket.data.roomCode;
    const timer = setTimeout(() => removeDisconnectedPlayer(room, playerId), 15_000);
    disconnectTimers.set(playerId, timer);
}
function removeDisconnectedPlayer(room, playerId) {
    disconnectTimers.delete(playerId);
    if (room.game.removePlayer(playerId))
        emitState(room);
    if (room.game.humanPlayerCount === 0) {
        if (room.boardEventTimeout)
            clearTimeout(room.boardEventTimeout);
        if (room.matchTimeout)
            clearTimeout(room.matchTimeout);
        clearBotTimers(room);
        rooms.delete(room.code);
    }
    else if (room.hostPlayerId === playerId) {
        room.hostPlayerId = room.game.snapshot().players.find((player) => !player.isBot).id;
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
        endsAt: room.endsAt,
        suddenDeath: room.suddenDeath,
        rematchVotes: room.rematchVotes.size
    };
}
function emitRoomError(socket, code, message) {
    socket.emit("room:error", { code, message });
}
function normalizeRoomCode(value) {
    const code = typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
    return /^\d{4}$/.test(code) ? code : null;
}
function normalizeClientId(value) {
    const clientId = typeof value === "string" ? value.trim() : "";
    return /^[a-zA-Z0-9_-]{8,80}$/.test(clientId) ? clientId : null;
}
function normalizeTeamMode(value) {
    return value === "FFA" || value === "TWO_V_TWO" || value === "THREE_V_ONE" ? value : null;
}
function normalizeTileType(value) {
    return value === "NUMBERS" || value === "COLORS" ? value : null;
}
function normalizeBotDifficulty(value) {
    return value === "EASY" || value === "MEDIUM" || value === "HARD" ? value : null;
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
function startMatch(room, rematch = false) {
    room.phase = "PLAYING";
    room.suddenDeath = false;
    room.rematchVotes.clear();
    room.startedAt = Date.now();
    room.endsAt = room.startedAt + matchDurationMs;
    if (rematch)
        room.game.resetMatch(room.config, resolveBossPlayerId(room));
    else
        room.game.startMatch(room.config, resolveBossPlayerId(room));
    emitRoomState(room);
    emitState(room);
    io.to(room.code).emit("game:started", { startedAt: room.startedAt, endsAt: room.endsAt });
    for (const botId of room.bots.keys())
        scheduleBotAction(room, botId);
    room.matchTimeout = setTimeout(() => finishMatch(room), matchDurationMs);
}
function finishMatch(room, force = false) {
    if (rooms.get(room.code) !== room || (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH"))
        return;
    if (!force && room.phase === "PLAYING" && hasTopScoreTie(room)) {
        room.phase = "SUDDEN_DEATH";
        room.suddenDeath = true;
        room.endsAt = Date.now() + suddenDeathDurationMs;
        emitRoomState(room);
        io.to(room.code).emit("game:sudden-death", { endsAt: room.endsAt });
        room.matchTimeout = setTimeout(() => finishMatch(room, true), suddenDeathDurationMs);
        return;
    }
    room.phase = "FINISHED";
    room.suddenDeath = false;
    room.endsAt = Date.now();
    if (room.boardEventTimeout)
        clearTimeout(room.boardEventTimeout);
    room.boardEventTimeout = null;
    clearBotTimers(room);
    room.game.endBoardEvent();
    const results = room.game.matchResults();
    const winningTeam = results[0]?.teamId;
    for (const winner of results.filter((entry) => entry.teamId === winningTeam && !entry.isBot)) {
        void leaderboard.recordMultiplayerWin(winner.name).catch((error) => {
            console.error("No se pudo registrar la victoria", error);
        });
    }
    emitRoomState(room);
    emitState(room);
    io.to(room.code).emit("game:finished", { results, finishedAt: room.endsAt });
    room.matchTimeout = null;
}
function fillRoomWithBots(room) {
    // Con un solo humano en FFA crea un duelo 1v1; los modos por equipo llenan 4.
    const targetPlayers = room.config.teamMode === "FFA" && room.game.playerCount === 1 ? 2 : 4;
    const usedNames = new Set(room.game.snapshot().players.map((player) => player.name));
    while (room.game.playerCount < targetPlayers) {
        const id = `bot:${randomUUID()}`;
        const baseName = botNames.find((name) => !usedNames.has(name)) ?? `Bot_${room.game.playerCount + 1}`;
        const player = room.game.addPlayer(id, baseName, true);
        if (!player)
            break;
        usedNames.add(player.name);
        room.bots.set(id, { timer: null, disabledUntil: 0, lastProgressAt: Date.now(), failedActions: 0 });
    }
    emitState(room);
    emitRoomState(room);
}
function resolveBossPlayerId(room) {
    if (room.config.teamMode !== "THREE_V_ONE")
        return room.hostPlayerId;
    const players = room.game.snapshot().players;
    const humans = players.filter((player) => !player.isBot);
    // Tres humanos reciben un Jefe IA; con un humano, éste es Jefe contra tres Bots.
    if (humans.length === 3)
        return players.find((player) => player.isBot)?.id ?? room.hostPlayerId;
    return room.hostPlayerId;
}
function processPlacement(room, playerId, payload, responder) {
    // El event loop conserva orden estricto tanto para sockets como para timers IA.
    const result = room.game.place(playerId, payload);
    if (!result.accepted) {
        responder?.emit("move:rejected", {
            requestId: result.requestId,
            code: result.code,
            message: result.message
        });
        if (responder && result.blockedUntil !== undefined) {
            responder.emit("player:penalty", {
                requestId: result.requestId,
                blockedUntil: result.blockedUntil,
                reason: result.code
            });
        }
        if (result.stateChanged)
            emitState(room);
        return result;
    }
    publishAcceptedPlacement(room, playerId, result, responder);
    if (room.phase === "SUDDEN_DEATH")
        finishMatch(room, true);
    return result;
}
function hasTopScoreTie(room) {
    const results = room.game.matchResults();
    if (results.length < 2)
        return false;
    const top = results[0].teamScore;
    return new Set(results.filter((entry) => entry.teamScore === top).map((entry) => entry.teamId)).size > 1;
}
function publishAcceptedPlacement(room, playerId, result, responder) {
    responder?.emit("move:accepted", {
        requestId: result.requestId,
        revision: result.revision,
        cellPoints: result.cellPoints,
        goldenBonus: result.goldenBonus,
        combo: result.combo,
        comboMultiplier: result.comboMultiplier,
        comboBonus: result.comboBonus
    });
    emitState(room);
    if (!result.clearPlan)
        return;
    const clearAt = Date.now() + CLEAR_DELAY_MS;
    io.to(room.code).emit("game:section-conquered", {
        playerId,
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
function scheduleBotAction(room, botId) {
    const runtime = room.bots.get(botId);
    if (!runtime || (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH"))
        return;
    if (runtime.timer)
        clearTimeout(runtime.timer);
    const player = room.game.snapshot().players.find((entry) => entry.id === botId);
    const profile = botProfile(room.config.botDifficulty, player?.botPersona ?? null);
    const availableAt = Math.max(runtime.disabledUntil, player?.blockedUntil ?? 0);
    const thinkTime = randomBetween(profile.minDelayMs, profile.maxDelayMs);
    const delay = Math.max(thinkTime, availableAt - Date.now() + 80);
    runtime.timer = setTimeout(() => {
        runtime.timer = null;
        if (rooms.get(room.code) !== room || (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH"))
            return;
        if (!runBotSupportPower(room, botId, runtime)) {
            runBotPower(room, botId);
            const proposal = room.game.createBotProposal(botId, profile.accuracy);
            if (proposal) {
                const result = processPlacement(room, botId, proposal);
                if (result.accepted) {
                    runtime.lastProgressAt = Date.now();
                    runtime.failedActions = 0;
                }
                else if (result.code !== "BLOCKED") {
                    runtime.failedActions += 1;
                }
            }
        }
        scheduleBotAction(room, botId);
    }, delay);
}
function runBotSupportPower(room, botId, runtime) {
    if (!room.config.powersEnabled)
        return false;
    const now = Date.now();
    const snapshot = room.game.snapshot(now);
    const bot = snapshot.players.find((player) => player.id === botId);
    if (!bot)
        return false;
    const rivals = snapshot.players.filter((player) => player.id !== botId && (room.config.teamMode === "FFA" || player.teamId !== bot.teamId));
    const threatThreshold = bot.botPersona === "GUARDIAN" ? 75 : 100;
    if (bot.energy >= 100 && bot.shieldUntil <= now && rivals.some((player) => player.energy >= threatThreshold)) {
        const shield = room.game.useReflectPower(botId, now);
        if (shield.accepted && shield.type === "REFLECT") {
            emitState(room);
            return true;
        }
    }
    const stuck = runtime.failedActions >= 2 || now - runtime.lastProgressAt >= 7_000;
    if (bot.energy >= 50 && stuck) {
        const target = room.game.createBotProposal(botId, 1);
        if (target) {
            const reveal = room.game.useRevealPower(botId, target.row, target.column, `bot-reveal-${randomUUID()}`, now);
            if (reveal.accepted && reveal.type === "REVEAL") {
                publishAcceptedPlacement(room, botId, reveal.placement);
                runtime.lastProgressAt = now;
                runtime.failedActions = 0;
                return true;
            }
        }
    }
    return false;
}
function applyFogDelivery(room, attackerId, recipientPlayerId, reflectedBy) {
    const targetedBot = room.bots.get(recipientPlayerId);
    if (targetedBot)
        targetedBot.disabledUntil = Date.now() + 4_000;
    io.to(recipientPlayerId).emit("power_received", {
        type: "FOG",
        attackerId,
        reflected: reflectedBy !== undefined,
        reflectedBy
    });
    if (reflectedBy)
        io.to(reflectedBy).emit("power_reflected", { attackerId });
}
function runBotPower(room, botId) {
    if (!room.config.powersEnabled)
        return;
    const snapshot = room.game.snapshot();
    const bot = snapshot.players.find((player) => player.id === botId);
    const fogChance = bot?.botPersona === "TRICKSTER" ? 0.48 : 0.22;
    if (Math.random() > fogChance)
        return;
    if (!bot || bot.energy < 100)
        return;
    const targets = snapshot.players.filter((player) => !player.isBot && player.id !== botId && (room.config.teamMode === "FFA" || player.teamId !== bot.teamId));
    const target = targets[Math.floor(Math.random() * targets.length)];
    if (!target)
        return;
    const result = room.game.useFogPower(botId, target.id);
    if (!result.accepted || result.type !== "FOG")
        return;
    applyFogDelivery(room, botId, result.recipientPlayerId, result.reflected ? result.targetPlayerId : undefined);
    emitState(room);
}
function botProfile(difficulty, persona) {
    const base = difficulty === "EASY"
        ? { minDelayMs: 2_400, maxDelayMs: 4_200, accuracy: 0.72 }
        : difficulty === "HARD"
            ? { minDelayMs: 700, maxDelayMs: 1_600, accuracy: 0.96 }
            : { minDelayMs: 1_300, maxDelayMs: 2_700, accuracy: 0.86 };
    if (persona === "CALCULATOR")
        return { ...base, minDelayMs: Math.round(base.minDelayMs * 1.12), accuracy: Math.min(0.99, base.accuracy + 0.03) };
    if (persona === "TRICKSTER")
        return { ...base, minDelayMs: Math.round(base.minDelayMs * 0.86), maxDelayMs: Math.round(base.maxDelayMs * 0.9), accuracy: Math.max(0.65, base.accuracy - 0.04) };
    if (persona === "GUARDIAN")
        return { ...base, minDelayMs: Math.round(base.minDelayMs * 1.05), maxDelayMs: Math.round(base.maxDelayMs * 1.05) };
    return base;
}
function clearBotTimers(room) {
    for (const runtime of room.bots.values()) {
        if (runtime.timer)
            clearTimeout(runtime.timer);
        runtime.timer = null;
    }
}
function randomBetween(minimum, maximum) {
    return minimum + Math.floor(Math.random() * (maximum - minimum + 1));
}
async function handleHttp(request, response) {
    try {
        if (request.method === "OPTIONS") {
            response.writeHead(204, corsHeaders()).end();
            return;
        }
        if (request.method === "GET" && request.url === "/health") {
            sendJson(response, 200, {
                ok: true,
                version: APP_VERSION,
                rooms: rooms.size,
                players: [...rooms.values()].reduce((total, room) => total + room.game.playerCount, 0)
            });
            return;
        }
        if (request.method === "GET" && request.url === "/api/leaderboards") {
            sendJson(response, 200, await leaderboard.topTen());
            return;
        }
        if (request.method === "POST" && request.url === "/api/solo/challenge") {
            const cutoff = Date.now() - 14_400_000;
            for (const [existingToken, createdAt] of soloChallenges) {
                if (createdAt < cutoff)
                    soloChallenges.delete(existingToken);
            }
            const token = randomUUID();
            soloChallenges.set(token, Date.now());
            sendJson(response, 200, { token, expiresInMs: 14_400_000 });
            return;
        }
        if (request.method === "POST" && request.url === "/api/leaderboards/solo") {
            const payload = await readJsonBody(request);
            const challengeToken = typeof payload.challengeToken === "string" ? payload.challengeToken : "";
            const challengeStartedAt = soloChallenges.get(challengeToken);
            soloChallenges.delete(challengeToken);
            if (!challengeStartedAt || Date.now() - challengeStartedAt > 14_400_000) {
                sendJson(response, 403, { error: "Desafío inválido, vencido o ya utilizado" });
                return;
            }
            const nickname = sanitizeNickname(payload.nickname);
            const elapsedMs = Number(payload.elapsedMs);
            if (!Number.isInteger(elapsedMs) || elapsedMs < 30_000 || elapsedMs > 86_400_000) {
                sendJson(response, 400, { error: "Tiempo inválido" });
                return;
            }
            await leaderboard.recordSolo(nickname, elapsedMs);
            sendJson(response, 200, { ok: true });
            return;
        }
        sendJson(response, 404, { error: "Not found" });
    }
    catch (error) {
        sendJson(response, 400, { error: error instanceof Error ? error.message : "Solicitud inválida" });
    }
}
async function readJsonBody(request) {
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
        const buffer = Buffer.from(chunk);
        size += buffer.length;
        if (size > 4_096)
            throw new Error("Payload demasiado grande");
        chunks.push(buffer);
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}
function sendJson(response, status, payload) {
    response.writeHead(status, { "content-type": "application/json; charset=utf-8", ...corsHeaders() });
    response.end(JSON.stringify(payload));
}
function corsHeaders() {
    return {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET,POST,OPTIONS",
        "access-control-allow-headers": "content-type"
    };
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
        clearBotTimers(room);
    }
    io.close(() => process.exit(0));
}
process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
//# sourceMappingURL=server.js.map