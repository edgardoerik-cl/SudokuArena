import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { Server, type Socket } from "socket.io";
import {
  BOARD_EVENT_DURATION_MS,
  BOARD_EVENT_INTERVAL_MS,
  APP_VERSION,
  CLEAR_DELAY_MS,
  MATCH_DURATION_MS,
  createRandomSolution
} from "./constants.js";
import { ArenaGame } from "./game.js";
import { LeaderboardStore, sanitizeNickname } from "./leaderboard.js";
import type {
  BotDifficulty,
  BoardEventType,
  PlaceProposal,
  PlaceResult,
  PowerProposal,
  ReactionEmoji,
  ReactionProposal,
  RoomConfig,
  RoomPhase,
  RoomState,
  TeamMode,
  TileType
} from "./types.js";

interface BotRuntime {
  timer: NodeJS.Timeout | null;
  disabledUntil: number;
  lastProgressAt: number;
  failedActions: number;
}

interface RoomRuntime {
  code: string;
  game: ArenaGame;
  boardEventTimeout: NodeJS.Timeout | null;
  matchTimeout: NodeJS.Timeout | null;
  hostPlayerId: string;
  config: RoomConfig;
  phase: RoomPhase;
  startedAt: number | null;
  endsAt: number | null;
  bots: Map<string, BotRuntime>;
}

const port = Number(process.env.PORT ?? 3000);
const allowedOrigin = process.env.CORS_ORIGIN ?? "*";
const requestedMatchDuration = Number(process.env.MATCH_DURATION_MS ?? MATCH_DURATION_MS);
const matchDurationMs = Number.isFinite(requestedMatchDuration) && requestedMatchDuration > 0
  ? requestedMatchDuration
  : MATCH_DURATION_MS;
const rooms = new Map<string, RoomRuntime>();
const leaderboard = new LeaderboardStore();

const httpServer = createServer((request, response) => void handleHttp(request, response));

const io = new Server(httpServer, {
  cors: { origin: allowedOrigin },
  transports: ["websocket", "polling"]
});
const reactionEmojis = new Set<ReactionEmoji>(["LAUGH", "CRY", "ANGRY", "SURPRISED"]);
const botNames = ["Bot_Androide", "Bot_Pro", "Bot_Neón", "Bot_Lógico", "Bot_Turbo", "Bot_Arena"];

const boardEventInterval = setInterval(() => {
  for (const room of rooms.values()) if (room.phase === "PLAYING") startRandomBoardEvent(room);
}, BOARD_EVENT_INTERVAL_MS);

io.on("connection", (socket) => {
  const requestedName = String(socket.handshake.auth.name ?? socket.handshake.query.name ?? "");
  let lastReactionAt = 0;

  socket.on("room:create", () => {
    if (socket.data.roomCode) return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
    const room = createRoom(socket.id);
    socket.emit("room:created", { roomCode: room.code });
    joinRoom(socket, room, requestedName);
  });

  socket.on("room:join", (payload: { roomCode?: unknown }) => {
    if (socket.data.roomCode) return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
    const roomCode = normalizeRoomCode(payload?.roomCode);
    if (!roomCode) return emitRoomError(socket, "INVALID_CODE", "Ingresa un código de 4 dígitos");
    const room = rooms.get(roomCode);
    if (!room) return emitRoomError(socket, "ROOM_NOT_FOUND", "La sala no existe o ya terminó");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    joinRoom(socket, room, requestedName);
  });

  socket.on("room:configure", (payload: Partial<RoomConfig>) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.hostPlayerId !== socket.id) return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede configurar");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
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
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.hostPlayerId !== socket.id) return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede añadir Bots");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    fillRoomWithBots(room);
  });

  socket.on("room:start", () => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.hostPlayerId !== socket.id) return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede iniciar");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    const minimumError = validatePlayerCount(room);
    if (minimumError) return emitRoomError(socket, "INVALID_PLAYER_COUNT", minimumError);
    startMatch(room);
  });

  socket.on("player:place", (payload: PlaceProposal) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "PLAYING") {
      socket.emit("move:rejected", {
        requestId: typeof payload?.requestId === "string" ? payload.requestId : "",
        code: "MATCH_NOT_PLAYING",
        message: "La partida todavía no está en curso"
      });
      return;
    }
    // Humanos y Bots atraviesan exactamente el mismo pipeline autoritativo.
    processPlacement(room, socket.id, payload, socket);
  });

  socket.on("use_power", (payload: PowerProposal) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "PLAYING") return emitRoomError(socket, "MATCH_NOT_PLAYING", "La partida no está en curso");
    const powerType = payload?.type ?? "FOG";
    if (powerType !== "FOG" && powerType !== "REFLECT" && powerType !== "REVEAL") {
      socket.emit("power_rejected", { code: "INVALID_POWER", message: "Poder no permitido" });
      return;
    }
    const result = powerType === "REFLECT"
      ? room.game.useReflectPower(socket.id)
      : powerType === "REVEAL"
        ? room.game.useRevealPower(socket.id, payload?.row, payload?.column, payload?.requestId)
        : room.game.useFogPower(socket.id, payload?.targetPlayerId);
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
    publishAcceptedPlacement(room, socket.id, result.placement);
  });

  socket.on("send_reaction", (payload: ReactionProposal) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
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

function createRoom(hostPlayerId: string): RoomRuntime {
  let code: string;
  do code = String(Math.floor(1_000 + Math.random() * 9_000)); while (rooms.has(code));
  const room: RoomRuntime = {
    code,
    game: new ArenaGame(`arena-${code}`, createRandomSolution()),
    boardEventTimeout: null,
    matchTimeout: null,
    hostPlayerId,
    config: { powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" },
    phase: "LOBBY",
    startedAt: null,
    endsAt: null,
    bots: new Map()
  };
  rooms.set(code, room);
  return room;
}

function joinRoom(socket: Socket, room: RoomRuntime, playerName: string): void {
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

function leaveCurrentRoom(socket: Socket): void {
  const room = roomFor(socket);
  if (!room) return;
  delete socket.data.roomCode;
  if (room.game.removePlayer(socket.id)) emitState(room);
  if (room.game.humanPlayerCount === 0) {
    if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
    if (room.matchTimeout) clearTimeout(room.matchTimeout);
    clearBotTimers(room);
    rooms.delete(room.code);
  } else if (room.hostPlayerId === socket.id) {
    room.hostPlayerId = room.game.snapshot().players.find((player) => !player.isBot)!.id;
    emitRoomState(room);
  }
}

function roomFor(socket: Socket): RoomRuntime | null {
  const code = socket.data.roomCode;
  return typeof code === "string" ? rooms.get(code) ?? null : null;
}

function emitState(room: RoomRuntime): void {
  io.to(room.code).emit("game:state", room.game.snapshot());
}

function emitRoomState(room: RoomRuntime): void {
  io.to(room.code).emit("room:state", toRoomState(room));
}

function toRoomState(room: RoomRuntime): RoomState {
  return {
    roomCode: room.code,
    hostPlayerId: room.hostPlayerId,
    config: { ...room.config },
    phase: room.phase,
    startedAt: room.startedAt,
    endsAt: room.endsAt
  };
}

function emitRoomError(socket: Socket, code: string, message: string): void {
  socket.emit("room:error", { code, message });
}

function normalizeRoomCode(value: unknown): string | null {
  const code = typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
  return /^\d{4}$/.test(code) ? code : null;
}

function normalizeTeamMode(value: unknown): TeamMode | null {
  return value === "FFA" || value === "TWO_V_TWO" || value === "THREE_V_ONE" ? value : null;
}

function normalizeTileType(value: unknown): TileType | null {
  return value === "NUMBERS" || value === "COLORS" ? value : null;
}

function normalizeBotDifficulty(value: unknown): BotDifficulty | null {
  return value === "EASY" || value === "MEDIUM" || value === "HARD" ? value : null;
}

function validatePlayerCount(room: RoomRuntime): string | null {
  const count = room.game.playerCount;
  if (room.config.teamMode === "FFA" && count < 2) return "Se necesitan al menos 2 jugadores";
  if (room.config.teamMode === "TWO_V_TWO" && count !== 4) return "El modo 2 vs 2 requiere exactamente 4 jugadores";
  if (room.config.teamMode === "THREE_V_ONE" && count !== 4) return "El modo 3 vs 1 requiere exactamente 4 jugadores";
  return null;
}

function startMatch(room: RoomRuntime): void {
  room.phase = "PLAYING";
  room.startedAt = Date.now();
  room.endsAt = room.startedAt + matchDurationMs;
  room.game.startMatch(room.config, resolveBossPlayerId(room));
  emitRoomState(room);
  emitState(room);
  io.to(room.code).emit("game:started", { startedAt: room.startedAt, endsAt: room.endsAt });
  for (const botId of room.bots.keys()) scheduleBotAction(room, botId);
  room.matchTimeout = setTimeout(() => finishMatch(room), matchDurationMs);
}

function finishMatch(room: RoomRuntime): void {
  if (rooms.get(room.code) !== room || room.phase !== "PLAYING") return;
  room.phase = "FINISHED";
  room.endsAt = Date.now();
  if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
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

function fillRoomWithBots(room: RoomRuntime): void {
  // Con un solo humano en FFA crea un duelo 1v1; los modos por equipo llenan 4.
  const targetPlayers = room.config.teamMode === "FFA" && room.game.playerCount === 1 ? 2 : 4;
  const usedNames = new Set(room.game.snapshot().players.map((player) => player.name));
  while (room.game.playerCount < targetPlayers) {
    const id = `bot:${randomUUID()}`;
    const baseName = botNames.find((name) => !usedNames.has(name)) ?? `Bot_${room.game.playerCount + 1}`;
    const player = room.game.addPlayer(id, baseName, true);
    if (!player) break;
    usedNames.add(player.name);
    room.bots.set(id, { timer: null, disabledUntil: 0, lastProgressAt: Date.now(), failedActions: 0 });
  }
  emitState(room);
  emitRoomState(room);
}

function resolveBossPlayerId(room: RoomRuntime): string {
  if (room.config.teamMode !== "THREE_V_ONE") return room.hostPlayerId;
  const players = room.game.snapshot().players;
  const humans = players.filter((player) => !player.isBot);
  // Tres humanos reciben un Jefe IA; con un humano, éste es Jefe contra tres Bots.
  if (humans.length === 3) return players.find((player) => player.isBot)?.id ?? room.hostPlayerId;
  return room.hostPlayerId;
}

function processPlacement(room: RoomRuntime, playerId: string, payload: PlaceProposal, responder?: Socket): PlaceResult {
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
    if (result.stateChanged) emitState(room);
    return result;
  }

  publishAcceptedPlacement(room, playerId, result, responder);
  return result;
}

function publishAcceptedPlacement(
  room: RoomRuntime,
  playerId: string,
  result: Extract<PlaceResult, { accepted: true }>,
  responder?: Socket
): void {
  responder?.emit("move:accepted", {
    requestId: result.requestId,
    revision: result.revision,
    cellPoints: result.cellPoints,
    goldenBonus: result.goldenBonus
  });
  emitState(room);
  if (!result.clearPlan) return;

  const clearAt = Date.now() + CLEAR_DELAY_MS;
  io.to(room.code).emit("game:section-conquered", {
    playerId,
    sections: result.sections,
    bonus: result.bonus,
    clearAt
  });
  const plan = result.clearPlan;
  setTimeout(() => {
    if (room.game.executeClear(plan) && rooms.get(room.code) === room) emitState(room);
  }, CLEAR_DELAY_MS);
}

function scheduleBotAction(room: RoomRuntime, botId: string): void {
  const runtime = room.bots.get(botId);
  if (!runtime || room.phase !== "PLAYING") return;
  if (runtime.timer) clearTimeout(runtime.timer);
  const profile = botProfile(room.config.botDifficulty);
  const player = room.game.snapshot().players.find((entry) => entry.id === botId);
  const availableAt = Math.max(runtime.disabledUntil, player?.blockedUntil ?? 0);
  const thinkTime = randomBetween(profile.minDelayMs, profile.maxDelayMs);
  const delay = Math.max(thinkTime, availableAt - Date.now() + 80);
  runtime.timer = setTimeout(() => {
    runtime.timer = null;
    if (rooms.get(room.code) !== room || room.phase !== "PLAYING") return;
    if (!runBotSupportPower(room, botId, runtime)) {
      runBotPower(room, botId);
      const proposal = room.game.createBotProposal(botId, profile.accuracy);
      if (proposal) {
        const result = processPlacement(room, botId, proposal);
        if (result.accepted) {
          runtime.lastProgressAt = Date.now();
          runtime.failedActions = 0;
        } else if (result.code !== "BLOCKED") {
          runtime.failedActions += 1;
        }
      }
    }
    scheduleBotAction(room, botId);
  }, delay);
}

function runBotSupportPower(room: RoomRuntime, botId: string, runtime: BotRuntime): boolean {
  if (!room.config.powersEnabled) return false;
  const now = Date.now();
  const snapshot = room.game.snapshot(now);
  const bot = snapshot.players.find((player) => player.id === botId);
  if (!bot) return false;
  const rivals = snapshot.players.filter((player) =>
    player.id !== botId && (room.config.teamMode === "FFA" || player.teamId !== bot.teamId)
  );

  if (bot.energy >= 100 && bot.shieldUntil <= now && rivals.some((player) => player.energy >= 100)) {
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

function applyFogDelivery(
  room: RoomRuntime,
  attackerId: string,
  recipientPlayerId: string,
  reflectedBy?: string
): void {
  const targetedBot = room.bots.get(recipientPlayerId);
  if (targetedBot) targetedBot.disabledUntil = Date.now() + 4_000;
  io.to(recipientPlayerId).emit("power_received", {
    type: "FOG",
    attackerId,
    reflected: reflectedBy !== undefined,
    reflectedBy
  });
  if (reflectedBy) io.to(reflectedBy).emit("power_reflected", { attackerId });
}

function runBotPower(room: RoomRuntime, botId: string): void {
  if (!room.config.powersEnabled || Math.random() > 0.22) return;
  const snapshot = room.game.snapshot();
  const bot = snapshot.players.find((player) => player.id === botId);
  if (!bot || bot.energy < 100) return;
  const targets = snapshot.players.filter((player) =>
    !player.isBot && player.id !== botId && (room.config.teamMode === "FFA" || player.teamId !== bot.teamId)
  );
  const target = targets[Math.floor(Math.random() * targets.length)];
  if (!target) return;
  const result = room.game.useFogPower(botId, target.id);
  if (!result.accepted || result.type !== "FOG") return;
  applyFogDelivery(room, botId, result.recipientPlayerId, result.reflected ? result.targetPlayerId : undefined);
  emitState(room);
}

function botProfile(difficulty: BotDifficulty): { minDelayMs: number; maxDelayMs: number; accuracy: number } {
  if (difficulty === "EASY") return { minDelayMs: 2_400, maxDelayMs: 4_200, accuracy: 0.72 };
  if (difficulty === "HARD") return { minDelayMs: 700, maxDelayMs: 1_600, accuracy: 0.96 };
  return { minDelayMs: 1_300, maxDelayMs: 2_700, accuracy: 0.86 };
}

function clearBotTimers(room: RoomRuntime): void {
  for (const runtime of room.bots.values()) {
    if (runtime.timer) clearTimeout(runtime.timer);
    runtime.timer = null;
  }
}

function randomBetween(minimum: number, maximum: number): number {
  return minimum + Math.floor(Math.random() * (maximum - minimum + 1));
}

async function handleHttp(
  request: import("node:http").IncomingMessage,
  response: import("node:http").ServerResponse
): Promise<void> {
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
    if (request.method === "POST" && request.url === "/api/leaderboards/solo") {
      const payload = await readJsonBody(request);
      const nickname = sanitizeNickname(payload.nickname);
      const elapsedMs = Number(payload.elapsedMs);
      if (!Number.isInteger(elapsedMs) || elapsedMs < 1_000 || elapsedMs > 86_400_000) {
        sendJson(response, 400, { error: "Tiempo inválido" });
        return;
      }
      await leaderboard.recordSolo(nickname, elapsedMs);
      sendJson(response, 200, { ok: true });
      return;
    }
    sendJson(response, 404, { error: "Not found" });
  } catch (error) {
    sendJson(response, 400, { error: error instanceof Error ? error.message : "Solicitud inválida" });
  }
}

async function readJsonBody(request: import("node:http").IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.from(chunk);
    size += buffer.length;
    if (size > 4_096) throw new Error("Payload demasiado grande");
    chunks.push(buffer);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
}

function sendJson(response: import("node:http").ServerResponse, status: number, payload: unknown): void {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", ...corsHeaders() });
  response.end(JSON.stringify(payload));
}

function corsHeaders(): Record<string, string> {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type"
  };
}

function startRandomBoardEvent(room: RoomRuntime): void {
  const type: BoardEventType = Math.random() < 0.5 ? "MIRROR_HOUR" : "GOLDEN_CELLS";
  const event = room.game.startBoardEvent(type, Date.now(), BOARD_EVENT_DURATION_MS);
  if (!event) return;

  io.to(room.code).emit("board_event_start", {
    eventType: event.type,
    startedAt: event.startedAt,
    endsAt: event.endsAt
  });
  emitState(room);
  room.boardEventTimeout = setTimeout(() => {
    if (rooms.get(room.code) !== room || !room.game.endBoardEvent(event.type)) return;
    io.to(room.code).emit("board_event_end", { eventType: event.type });
    emitState(room);
    room.boardEventTimeout = null;
  }, BOARD_EVENT_DURATION_MS);
}

httpServer.listen(port, "0.0.0.0", () => {
  console.log(`Sudoku Arena escuchando en :${port}`);
});

function shutdown(): void {
  clearInterval(boardEventInterval);
  for (const room of rooms.values()) {
    if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
    if (room.matchTimeout) clearTimeout(room.matchTimeout);
    clearBotTimers(room);
  }
  io.close(() => process.exit(0));
}

process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
