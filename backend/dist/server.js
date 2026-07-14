import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { Server } from "socket.io";
import { ArenaGame } from "./game.js";
import { BOARD_EVENT_DURATION_MS, BOARD_EVENT_INTERVAL_MS, CLEAR_DELAY_MS } from "./constants.js";
const port = Number(process.env.PORT ?? 3000);
const allowedOrigin = process.env.CORS_ORIGIN ?? "*";
const httpServer = createServer((request, response) => {
    if (request.url === "/health") {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ ok: true, players: game.playerCount, boardEvent: game.snapshot().boardEvent }));
        return;
    }
    response.writeHead(404).end();
});
const io = new Server(httpServer, {
    cors: { origin: allowedOrigin },
    transports: ["websocket", "polling"]
});
const game = new ArenaGame();
const reactionEmojis = new Set(["LAUGH", "CRY", "ANGRY", "SURPRISED"]);
let boardEventTimeout = null;
const boardEventInterval = setInterval(startRandomBoardEvent, BOARD_EVENT_INTERVAL_MS);
function startRandomBoardEvent() {
    const type = Math.random() < 0.5 ? "MIRROR_HOUR" : "GOLDEN_CELLS";
    const event = game.startBoardEvent(type, Date.now(), BOARD_EVENT_DURATION_MS);
    if (!event)
        return;
    io.emit("board_event_start", { eventType: event.type, startedAt: event.startedAt, endsAt: event.endsAt });
    io.emit("game:state", game.snapshot());
    boardEventTimeout = setTimeout(() => {
        if (!game.endBoardEvent(event.type))
            return;
        io.emit("board_event_end", { eventType: event.type });
        io.emit("game:state", game.snapshot());
        boardEventTimeout = null;
    }, BOARD_EVENT_DURATION_MS);
}
io.on("connection", (socket) => {
    let lastReactionAt = 0;
    const requestedName = String(socket.handshake.auth.name ?? socket.handshake.query.name ?? "");
    const player = game.addPlayer(socket.id, requestedName);
    if (!player) {
        socket.emit("arena:full", { message: "La arena ya tiene 4 jugadores" });
        socket.disconnect(true);
        return;
    }
    socket.emit("game:joined", { playerId: player.id, state: game.snapshot() });
    io.emit("game:state", game.snapshot());
    socket.on("player:place", (payload) => {
        // No introducir await en este bloque: el orden de llegada al event loop es
        // el árbitro y game.place escribe antes de atender el próximo callback.
        const result = game.place(socket.id, payload);
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
                io.emit("game:state", game.snapshot());
            return;
        }
        socket.emit("move:accepted", {
            requestId: result.requestId,
            revision: result.revision,
            cellPoints: result.cellPoints,
            goldenBonus: result.goldenBonus
        });
        io.emit("game:state", game.snapshot());
        if (result.clearPlan) {
            const clearAt = Date.now() + CLEAR_DELAY_MS;
            io.emit("game:section-conquered", {
                playerId: socket.id,
                sections: result.sections,
                bonus: result.bonus,
                clearAt
            });
            const plan = result.clearPlan;
            setTimeout(() => {
                if (game.executeClear(plan))
                    io.emit("game:state", game.snapshot());
            }, CLEAR_DELAY_MS);
        }
    });
    socket.on("use_power", (payload) => {
        const result = game.useFogPower(socket.id, payload?.targetPlayerId);
        if (!result.accepted) {
            socket.emit("power_rejected", { code: result.code, message: result.message });
            return;
        }
        socket.emit("power_used", { type: result.type, targetPlayerId: result.targetPlayerId });
        io.to(result.targetPlayerId).emit("power_received", {
            type: result.type,
            attackerId: result.attackerId
        });
        io.emit("game:state", game.snapshot());
    });
    socket.on("send_reaction", (payload) => {
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
        io.emit("reaction_received", {
            reactionId: randomUUID(),
            playerId: socket.id,
            emojiId: payload.emojiId,
            sentAt: now
        });
    });
    socket.on("disconnect", () => {
        if (game.removePlayer(socket.id))
            io.emit("game:state", game.snapshot());
    });
});
httpServer.listen(port, "0.0.0.0", () => {
    console.log(`Sudoku Arena escuchando en :${port}`);
});
function shutdown() {
    clearInterval(boardEventInterval);
    if (boardEventTimeout)
        clearTimeout(boardEventTimeout);
    io.close(() => httpServer.close(() => process.exit(0)));
}
process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
//# sourceMappingURL=server.js.map