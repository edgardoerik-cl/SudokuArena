import { createServer } from "node:http";
import { Server } from "socket.io";
import { ArenaGame } from "./game.js";
import { CLEAR_DELAY_MS } from "./constants.js";
const port = Number(process.env.PORT ?? 3000);
const allowedOrigin = process.env.CORS_ORIGIN ?? "*";
const httpServer = createServer((request, response) => {
    if (request.url === "/health") {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ ok: true }));
        return;
    }
    response.writeHead(404).end();
});
const io = new Server(httpServer, {
    cors: { origin: allowedOrigin },
    transports: ["websocket", "polling"]
});
const game = new ArenaGame();
io.on("connection", (socket) => {
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
            revision: result.revision
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
    socket.on("disconnect", () => {
        if (game.removePlayer(socket.id))
            io.emit("game:state", game.snapshot());
    });
});
httpServer.listen(port, "0.0.0.0", () => {
    console.log(`Sudoku Arena escuchando en :${port}`);
});
//# sourceMappingURL=server.js.map