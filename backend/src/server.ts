import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { Server, type Socket } from "socket.io";
import {
  BOARD_EVENT_DURATION_MS,
  BOARD_EVENT_INTERVAL_MS,
  APP_VERSION,
  CLEAR_DELAY_MS,
  MATCH_DURATION_MS,
  SUDDEN_DEATH_DURATION_MS,
  createRandomSolution
} from "./constants.js";
import { ArenaGame } from "./game.js";
import { PacmanArenaEngine, TetrisArenaEngine, type PacmanDirection, type TetrisAction } from "./action/arcadeEngines.js";
import { LeaderboardStore, sanitizeNickname } from "./leaderboard.js";
import { GenericPuzzleEngine } from "./puzzles/engine.js";
import { GAME_TYPES, type GameType, type GenericMove, type PuzzleDifficulty } from "./puzzles/types.js";
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
  fogReadyAt: number;
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
  rematchVotes: Set<string>;
  suddenDeath: boolean;
  genericEngine: GenericPuzzleEngine | null;
  tetrisEngine: TetrisArenaEngine | null;
  tetrisTick: NodeJS.Timeout | null;
  pacmanEngine: PacmanArenaEngine | null;
  pacmanTick: NodeJS.Timeout | null;
  pauseRequesterId: string | null;
  pauseVotes: Set<string>;
  pauseNoVotes: Set<string>;
  pausedAt: number | null;
  pausedRemainingMs: number;
  pausedPhase: "PLAYING" | "SUDDEN_DEATH" | null;
  resumeCountdownEndsAt: number | null;
  resumeTimer: NodeJS.Timeout | null;
  totalPausedMs: number;
  secretChatBlockedUntil: Map<string, number>;
  rpsChoices: Map<string, "ROCK" | "PAPER" | "SCISSORS">;
  rpsRound: number;
  rpsTimer: NodeJS.Timeout | null;
  rpsIsRematch: boolean;
}

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
const rpsEnabled = process.env.RPS_ENABLED !== "false";
const rooms = new Map<string, RoomRuntime>();
const disconnectTimers = new Map<string, NodeJS.Timeout>();
const leaderboard = new LeaderboardStore();
const soloChallenges = new Map<string, number>();

const httpServer = createServer((request, response) => void handleHttp(request, response));

const io = new Server(httpServer, {
  cors: { origin: allowedOrigin },
  transports: ["websocket", "polling"]
});
const reactionEmojis = new Set<ReactionEmoji>(["LAUGH", "CRY", "ANGRY", "SURPRISED"]);
const botNames = ["Bot_Androide", "Bot_Pro", "Bot_Neón", "Bot_Lógico", "Bot_Turbo", "Bot_Arena"];

const boardEventInterval = setInterval(() => {
  for (const room of rooms.values()) {
    if (room.phase === "PLAYING" && room.config.gameType === "SUDOKU") startRandomBoardEvent(room);
  }
}, BOARD_EVENT_INTERVAL_MS);

io.on("connection", (socket) => {
  const requestedName = String(socket.handshake.auth.name ?? socket.handshake.query.name ?? "");
  const requestedAvatar = normalizeAvatar(socket.handshake.auth.avatarId);
  const clientId = normalizeClientId(socket.handshake.auth.clientId);
  const playerId = clientId ? `human:${clientId}` : socket.id;
  socket.data.playerId = playerId;
  socket.join(playerId);
  let lastReactionAt = 0;
  let lastGlobalChatAt = 0;

  socket.on("room:create", () => {
    if (socket.data.roomCode) return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
    const room = createRoom(playerId);
    socket.emit("room:created", { roomCode: room.code });
    joinRoom(socket, room, requestedName, requestedAvatar);
  });

  socket.on("room:join", (payload: { roomCode?: unknown }) => {
    if (socket.data.roomCode) return emitRoomError(socket, "ALREADY_IN_ROOM", "Ya estás dentro de una sala");
    const roomCode = normalizeRoomCode(payload?.roomCode);
    if (!roomCode) return emitRoomError(socket, "INVALID_CODE", "Ingresa un código de 4 dígitos");
    const room = rooms.get(roomCode);
    if (!room) return emitRoomError(socket, "ROOM_NOT_FOUND", "La sala no existe o ya terminó");
    if (room.phase !== "LOBBY" && !room.game.hasPlayer(playerId)) {
      return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    }
    joinRoom(socket, room, requestedName, requestedAvatar);
  });

  socket.on("room:configure", (payload: Partial<RoomConfig>) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.hostPlayerId !== playerId) return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede configurar");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    const teamMode = normalizeTeamMode(payload?.teamMode);
    // tileType ausente conserva la opción actual para clientes 0.6 instalados.
    const tileType = payload?.tileType === undefined
      ? room.config.tileType
      : normalizeTileType(payload.tileType);
    const botDifficulty = payload?.botDifficulty === undefined
      ? room.config.botDifficulty
      : normalizeBotDifficulty(payload.botDifficulty);
    const puzzleDifficulty = payload?.puzzleDifficulty === undefined
      ? room.config.puzzleDifficulty
      : normalizePuzzleDifficulty(payload.puzzleDifficulty);
    if (!teamMode || !tileType || !botDifficulty || !puzzleDifficulty || typeof payload?.powersEnabled !== "boolean") {
      return emitRoomError(socket, "INVALID_CONFIG", "Configuración de sala inválida");
    }
    const gameType = payload?.gameType === undefined ? room.config.gameType : normalizeGameType(payload.gameType);
    if (!gameType) return emitRoomError(socket, "INVALID_GAME", "Tipo de juego no permitido");
    room.config = {
      gameType,
      powersEnabled: payload.powersEnabled,
      teamMode: gameType === "TETRIS_ARENA" || gameType === "PACMAN_ARENA" ? "FFA" : teamMode,
      tileType,
      botDifficulty,
      puzzleDifficulty,
    };
    emitRoomState(room);
  });

  socket.on("fill_with_ai", () => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.hostPlayerId !== playerId) return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede añadir Bots");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    fillRoomWithBots(room);
  });

  socket.on("player:loadout", (payload: { powers?: unknown }) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "El equipamiento ya está cerrado");
    const powers = Array.isArray(payload?.powers) ? payload.powers : [];
    if (!room.game.setPowerLoadout(playerId, powers)) {
      return emitRoomError(socket, "INVALID_LOADOUT", "Elige exactamente dos poderes distintos");
    }
    emitState(room);
  });

  socket.on("room:start", () => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.hostPlayerId !== playerId) return emitRoomError(socket, "HOST_ONLY", "Sólo el host puede iniciar");
    if (room.phase !== "LOBBY") return emitRoomError(socket, "MATCH_STARTED", "La partida ya comenzó");
    const minimumError = validatePlayerCount(room);
    if (minimumError) return emitRoomError(socket, "INVALID_PLAYER_COUNT", minimumError);
    if (rpsEnabled) startRps(room);
    else startMatch(room);
  });

  socket.on("rps:choose", (payload: { choice?: unknown }) => {
    const room = roomFor(socket);
    if (!room || room.phase !== "RPS") return;
    const choice = normalizeRpsChoice(payload?.choice);
    if (!choice || !room.game.hasPlayer(playerId)) return;
    room.rpsChoices.set(playerId, choice);
    io.to(room.code).emit("rps:progress", { chosen: room.rpsChoices.size, total: room.game.playerCount });
  });

  socket.on("room:rematch", () => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "FINISHED") return emitRoomError(socket, "MATCH_NOT_FINISHED", "La partida aún no termina");
    room.rematchVotes.add(playerId);
    const humans = room.game.snapshot().players.filter((player) => !player.isBot);
    if (humans.length <= 1) {
      if (rpsEnabled) startRps(room, true);
      else startMatch(room, true);
      return;
    }
    emitRoomState(room);
    if (humans.every((player) => room.rematchVotes.has(player.id))) {
      if (rpsEnabled) startRps(room, true);
      else startMatch(room, true);
    }
  });

  socket.on("pause:request", () => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH") return emitRoomError(socket, "MATCH_NOT_PLAYING", "La partida no está activa");
    if (room.pauseRequesterId) return emitRoomError(socket, "PAUSE_PENDING", "Ya existe una solicitud de pausa");
    room.pauseRequesterId = playerId;
    room.pauseVotes = new Set([playerId]);
    room.pauseNoVotes.clear();
    io.to(room.code).emit("pause:requested", { requesterId: playerId, expiresAt: Date.now() + 15_000 });
    emitRoomState(room);
    maybeActivatePause(room);
    setTimeout(() => {
      if (rooms.get(room.code) === room && room.pauseRequesterId === playerId && room.phase !== "PAUSED") cancelPauseRequest(room, "La solicitud de pausa expiró");
    }, 15_000);
  });

  socket.on("pause:respond", (payload: { accepted?: unknown }) => {
    const room = roomFor(socket);
    if (!room || !room.pauseRequesterId || room.phase === "PAUSED") return;
    room.pauseVotes.delete(playerId);
    room.pauseNoVotes.delete(playerId);
    if (payload?.accepted === true) room.pauseVotes.add(playerId);
    else room.pauseNoVotes.add(playerId);
    emitRoomState(room);
    maybeActivatePause(room);
  });

  socket.on("pause:resume", () => {
    const room = roomFor(socket);
    if (!room || room.phase !== "PAUSED") return emitRoomError(socket, "NOT_PAUSED", "La partida no está pausada");
    if (room.pauseRequesterId !== playerId) return emitRoomError(socket, "REQUESTER_ONLY", "Sólo quien solicitó la pausa puede continuar");
    if (room.resumeCountdownEndsAt) return;
    room.resumeCountdownEndsAt = Date.now() + 3_000;
    emitRoomState(room);
    io.to(room.code).emit("pause:resuming", { endsAt: room.resumeCountdownEndsAt });
    room.resumeTimer = setTimeout(() => resumeRoom(room), 3_000);
  });

  socket.on("player:place", (payload: PlaceProposal) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
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

  socket.on("make_move", (payload: GenericMove) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH") {
      socket.emit("generic:move-rejected", { requestId: payload?.requestId ?? "", code: "MATCH_NOT_PLAYING", message: "La partida no está en curso" });
      return;
    }
    if (!room.genericEngine) {
      socket.emit("generic:move-rejected", { requestId: payload?.requestId ?? "", code: "USE_SUDOKU_MOVE", message: "Sudoku utiliza player:place" });
      return;
    }
    processGenericMove(room, playerId, payload, socket);
  });

  socket.on("tetris:input", (payload: { action?: TetrisAction }) => {
    const room = roomFor(socket);
    if (!room || room.phase !== "PLAYING" || room.config.gameType !== "TETRIS_ARENA" || !room.tetrisEngine || !payload?.action) return;
    room.tetrisEngine.input(playerId, payload.action);
  });

  socket.on("pacman:input", (payload: { direction?: PacmanDirection }) => {
    const room = roomFor(socket);
    if (!room || room.phase !== "PLAYING" || room.config.gameType !== "PACMAN_ARENA" || !room.pacmanEngine || !payload?.direction) return;
    room.pacmanEngine.input(playerId, payload.direction);
  });

  socket.on("use_power", (payload: PowerProposal) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    if (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH") {
      return emitRoomError(socket, "MATCH_NOT_PLAYING", "La partida no está en curso");
    }
    const powerType = payload?.type ?? "FOG";
    if (powerType !== "FOG" && powerType !== "REFLECT" && powerType !== "REVEAL") {
      socket.emit("power_rejected", { code: "INVALID_POWER", message: "Poder no permitido" });
      return;
    }
    if (powerType === "REVEAL" && room.genericEngine) {
      const row = Number(payload?.row);
      const col = Number(payload?.column);
      const revealMove = room.genericEngine.revealMove(row, col);
      if (!revealMove) {
        socket.emit("power_rejected", { code: "INVALID_CELL", message: "Selecciona una casilla disponible" });
        return;
      }
      const consumed = room.game.consumeGenericRevealPower(playerId);
      if (!consumed.accepted) {
        socket.emit("power_rejected", { code: "GENERIC_REVEAL_REJECTED", message: consumed.message });
        return;
      }
      const revealResult = room.genericEngine.makeMove(playerId, revealMove, room.game, Date.now(), { rewardEnergy: false });
      socket.emit("power_used", { type: "REVEAL", row, column: col });
      emitGenericState(room);
      emitState(room);
      if (revealResult.completed) finishMatch(room, true);
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
      playerId,
      emojiId: payload.emojiId,
      sentAt: now
    });
  });

  socket.on("global:chat-send", (payload: { message?: unknown }) => {
    const room = roomFor(socket);
    if (!room) return emitRoomError(socket, "NOT_IN_ROOM", "Primero debes entrar a una sala");
    const now = Date.now();
    if (now - lastGlobalChatAt < 650) return;
    const message = String(payload?.message ?? "")
      .replace(/[\u0000-\u001F\u007F]/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 160);
    if (!message) return;
    lastGlobalChatAt = now;
    io.to(room.code).emit("global:chat-message", {
      id: randomUUID(),
      playerId,
      message,
      sentAt: now,
    });
  });

  socket.on("secret:chat-send", (payload: { message?: unknown }) => {
    const room = roomFor(socket);
    if (!room || room.config.gameType !== "SECRET_CODE" || !room.genericEngine) return;
    const now = Date.now();
    const blockedUntil = room.secretChatBlockedUntil.get(playerId) ?? 0;
    if (blockedUntil > now) {
      socket.emit("secret:chat-locked", { blockedUntil });
      return;
    }
    const raw = String(payload?.message ?? "").trim().slice(0, 120);
    if (!raw) return;
    const message = normalizeChat(raw);
    const tokens = raw.split(/\s+/).map(normalizeChat).filter((token) => token.length >= 3);
    const forbidden = room.genericEngine.secretWords().some((word) => {
      const candidate = normalizeChat(word);
      return candidate.length >= 3 && (message.includes(candidate) || tokens.some((token) => candidate.includes(token)));
    });
    const output = forbidden ? "••••••" : raw;
    if (forbidden) {
      const until = now + 10_000;
      room.secretChatBlockedUntil.set(playerId, until);
      socket.emit("secret:chat-locked", { blockedUntil: until });
    }
    const team = room.genericEngine.secretTeamFor(playerId);
    for (const recipient of room.game.snapshot().players) {
      if (room.genericEngine.secretTeamFor(recipient.id) === team) {
        io.to(recipient.id).emit("secret:chat-message", { playerId, message: output, sentAt: now, penalized: forbidden });
      }
    }
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
    config: { gameType: "SUDOKU", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM", puzzleDifficulty: "MEDIUM" },
    phase: "LOBBY",
    startedAt: null,
    endsAt: null,
    bots: new Map(),
    rematchVotes: new Set(),
    suddenDeath: false,
    genericEngine: null,
    tetrisEngine: null,
    tetrisTick: null,
    pacmanEngine: null,
    pacmanTick: null,
    pauseRequesterId: null,
    pauseVotes: new Set(),
    pauseNoVotes: new Set(),
    pausedAt: null,
    pausedRemainingMs: 0,
    pausedPhase: null,
    resumeCountdownEndsAt: null,
    resumeTimer: null,
    totalPausedMs: 0,
    secretChatBlockedUntil: new Map(),
    rpsChoices: new Map(),
    rpsRound: 0,
    rpsTimer: null,
    rpsIsRematch: false,
  };
  rooms.set(code, room);
  return room;
}

function joinRoom(socket: Socket, room: RoomRuntime, playerName: string, avatarId: string): void {
  const playerId = String(socket.data.playerId ?? socket.id);
  const previousDisconnect = disconnectTimers.get(playerId);
  if (previousDisconnect) {
    clearTimeout(previousDisconnect);
    disconnectTimers.delete(playerId);
  }
  const existingPlayer = room.game.snapshot().players.find((entry) => entry.id === playerId);
  const player = existingPlayer ?? room.game.addPlayer(playerId, playerName, false, avatarId);
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

function leaveCurrentRoom(socket: Socket): void {
  const room = roomFor(socket);
  if (!room) return;
  const playerId = String(socket.data.playerId ?? socket.id);
  delete socket.data.roomCode;
  const timer = setTimeout(() => removeDisconnectedPlayer(room, playerId), 15_000);
  disconnectTimers.set(playerId, timer);
}

function removeDisconnectedPlayer(room: RoomRuntime, playerId: string): void {
  disconnectTimers.delete(playerId);
  if (room.game.removePlayer(playerId)) emitState(room);
  if (room.game.humanPlayerCount === 0) {
    if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
    if (room.matchTimeout) clearTimeout(room.matchTimeout);
    if (room.resumeTimer) clearTimeout(room.resumeTimer);
    if (room.rpsTimer) clearTimeout(room.rpsTimer);
    if (room.tetrisTick) clearInterval(room.tetrisTick);
    if (room.pacmanTick) clearInterval(room.pacmanTick);
    clearBotTimers(room);
    rooms.delete(room.code);
    return;
  }

  const nextHumanId = room.game.snapshot().players.find((player) => !player.isBot)!.id;
  if (room.hostPlayerId === playerId) room.hostPlayerId = nextHumanId;

  if (room.pauseRequesterId === playerId) {
    if (room.phase === "PAUSED") {
      // Evita una pausa huérfana: el control para reanudar pasa al primer humano conectado.
      room.pauseRequesterId = nextHumanId;
      room.pauseVotes = new Set([nextHumanId]);
      room.pauseNoVotes.clear();
    } else {
      cancelPauseRequest(room, "Quien solicitó la pausa abandonó la sala");
      return;
    }
  }

  if (room.phase === "PAUSED" || room.pauseRequesterId) {
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
    endsAt: room.endsAt,
    suddenDeath: room.suddenDeath,
    rematchVotes: room.rematchVotes.size,
    pauseRequesterId: room.pauseRequesterId,
    pauseVotes: room.pauseVotes.size,
    pauseNoVotes: room.pauseNoVotes.size,
    pauseRequired: humanPlayers(room).length,
    resumeCountdownEndsAt: room.resumeCountdownEndsAt
  };
}

function emitRoomError(socket: Socket, code: string, message: string): void {
  socket.emit("room:error", { code, message });
}

function normalizeRoomCode(value: unknown): string | null {
  const code = typeof value === "string" || typeof value === "number" ? String(value).trim() : "";
  return /^\d{4}$/.test(code) ? code : null;
}

function normalizeClientId(value: unknown): string | null {
  const clientId = typeof value === "string" ? value.trim() : "";
  return /^[a-zA-Z0-9_-]{8,80}$/.test(clientId) ? clientId : null;
}

function normalizeTeamMode(value: unknown): TeamMode | null {
  return value === "DUEL" || value === "FFA" || value === "TWO_V_ONE" || value === "TWO_V_TWO" || value === "THREE_V_ONE" ? value : null;
}

function normalizeAvatar(value: unknown): string {
  return typeof value === "string" && ["ORBIT", "NOVA", "PIXEL", "NINJA", "ASTRO", "BRAIN", "ROBOT", "FOX"].includes(value)
    ? value
    : "ORBIT";
}

function normalizeTileType(value: unknown): TileType | null {
  return value === "NUMBERS" || value === "COLORS" ? value : null;
}

function normalizeBotDifficulty(value: unknown): BotDifficulty | null {
  return value === "EASY" || value === "MEDIUM" || value === "HARD" ? value : null;
}

function normalizeGameType(value: unknown): GameType | null {
  return typeof value === "string" && (GAME_TYPES as readonly string[]).includes(value) ? value as GameType : null;
}

function normalizePuzzleDifficulty(value: unknown): PuzzleDifficulty | null {
  return value === "EASY" || value === "MEDIUM" || value === "HARD" || value === "EXPERT" ? value : null;
}

function normalizeRpsChoice(value: unknown): "ROCK" | "PAPER" | "SCISSORS" | null {
  return value === "ROCK" || value === "PAPER" || value === "SCISSORS" ? value : null;
}

function validatePlayerCount(room: RoomRuntime): string | null {
  const count = room.game.playerCount;
  if (room.config.teamMode === "DUEL" && count !== 2) return "El modo 1 vs 1 requiere exactamente 2 jugadores";
  if (room.config.teamMode === "FFA" && count < 2) return "Se necesitan al menos 2 jugadores";
  if (room.config.teamMode === "TWO_V_ONE" && count !== 3) return "El modo 2 vs 1 requiere exactamente 3 jugadores";
  if (room.config.teamMode === "TWO_V_TWO" && count !== 4) return "El modo 2 vs 2 requiere exactamente 4 jugadores";
  if (room.config.teamMode === "THREE_V_ONE" && count !== 4) return "El modo 3 vs 1 requiere exactamente 4 jugadores";
  return null;
}

function startRps(room: RoomRuntime, rematch = false): void {
  if (room.rpsTimer) clearTimeout(room.rpsTimer);
  room.phase = "RPS";
  room.rpsRound += 1;
  room.rpsIsRematch = rematch;
  room.rpsChoices.clear();
  const choices = ["ROCK", "PAPER", "SCISSORS"] as const;
  for (const player of room.game.snapshot().players) {
    if (player.isBot) room.rpsChoices.set(player.id, choices[Math.floor(Math.random() * choices.length)]!);
  }
  const endsAt = Date.now() + 3_000;
  room.startedAt = null;
  room.endsAt = endsAt;
  emitRoomState(room);
  io.to(room.code).emit("rps:started", { round: room.rpsRound, endsAt });
  room.rpsTimer = setTimeout(() => resolveRps(room), 3_000);
}

function resolveRps(room: RoomRuntime): void {
  if (room.phase !== "RPS") return;
  if (room.rpsTimer) clearTimeout(room.rpsTimer);
  room.rpsTimer = null;
  const choices = ["ROCK", "PAPER", "SCISSORS"] as const;
  for (const player of room.game.snapshot().players) {
    if (!room.rpsChoices.has(player.id)) {
      room.rpsChoices.set(player.id, choices[Math.floor(Math.random() * choices.length)]!);
    }
  }
  const beats: Record<string, string> = { ROCK: "SCISSORS", PAPER: "ROCK", SCISSORS: "PAPER" };
  const scores = new Map<string, number>();
  for (const [playerId, choice] of room.rpsChoices) {
    let score = 0;
    for (const [otherId, otherChoice] of room.rpsChoices) {
      if (otherId !== playerId && beats[choice] === otherChoice) score += 1;
    }
    scores.set(playerId, score);
  }
  const best = Math.max(...scores.values());
  const winners = [...scores.entries()].filter(([, score]) => score === best).map(([id]) => id);
  const winnerId = winners.length === 1 ? winners[0]! : null;
  io.to(room.code).emit("rps:result", {
    round: room.rpsRound,
    choices: Object.fromEntries(room.rpsChoices),
    winnerId,
    tie: winnerId === null,
  });
  if (winnerId) {
    setTimeout(() => {
      if (rooms.get(room.code) === room && room.phase === "RPS") {
        startMatch(room, room.rpsIsRematch, winnerId);
      }
    }, 1_800);
  } else {
    setTimeout(() => {
      if (rooms.get(room.code) === room && room.phase === "RPS") startRps(room, room.rpsIsRematch);
    }, 1_500);
  }
}

function startMatch(room: RoomRuntime, rematch = false, startingPlayerId?: string): void {
  room.phase = "PLAYING";
  room.suddenDeath = false;
  room.rematchVotes.clear();
  clearPauseState(room);
  room.totalPausedMs = 0;
  room.secretChatBlockedUntil.clear();
  room.startedAt = Date.now();
  room.endsAt = room.startedAt + matchDurationMs;
  if (rematch) room.game.resetMatch(room.config, resolveBossPlayerId(room));
  else room.game.startMatch(room.config, resolveBossPlayerId(room));
  room.genericEngine = room.config.gameType === "SUDOKU" || room.config.gameType === "TETRIS_ARENA" || room.config.gameType === "PACMAN_ARENA"
    ? null
    : new GenericPuzzleEngine(room.config.gameType, `puzzle-${room.code}-${room.startedAt}`, {
        seed: `${room.code}-${room.startedAt}`,
        difficulty: room.config.puzzleDifficulty
      });
  room.tetrisEngine = room.config.gameType === "TETRIS_ARENA" ? new TetrisArenaEngine() : null;
  room.pacmanEngine = room.config.gameType === "PACMAN_ARENA" ? new PacmanArenaEngine() : null;
  room.tetrisEngine?.syncPlayers(room.game.snapshot().players);
  room.pacmanEngine?.syncPlayers(room.game.snapshot().players);
  if (startingPlayerId) room.genericEngine?.setFirstPlayer(startingPlayerId);
  emitRoomState(room);
  emitState(room);
  emitGenericState(room);
  io.to(room.code).emit("game:started", { startedAt: room.startedAt, endsAt: room.endsAt });
  if (room.tetrisEngine) startTetrisLoop(room);
  if (room.pacmanEngine) startPacmanLoop(room);
  for (const botId of room.bots.keys()) scheduleBotAction(room, botId);
  room.matchTimeout = setTimeout(() => finishMatch(room), matchDurationMs);
}

function finishMatch(room: RoomRuntime, force = false): void {
  if (rooms.get(room.code) !== room || (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH")) return;
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
  if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
  if (room.rpsTimer) clearTimeout(room.rpsTimer);
  if (room.tetrisTick) clearInterval(room.tetrisTick);
  room.tetrisTick = null;
  if (room.pacmanTick) clearInterval(room.pacmanTick);
  room.pacmanTick = null;
  room.rpsTimer = null;
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
  const elapsedMs = Math.max(1_000, room.endsAt - (room.startedAt ?? room.endsAt) - room.totalPausedMs);
  for (const result of results.filter((entry) => !entry.isBot)) {
    void leaderboard.recordGame(room.config.gameType, result.name, elapsedMs, result.score, result.teamId === winningTeam)
      .catch((error) => console.error("No se pudo registrar resultado por juego", error));
  }
  emitRoomState(room);
  emitState(room);
  io.to(room.code).emit("game:finished", { results, finishedAt: room.endsAt });
  room.matchTimeout = null;
}

function fillRoomWithBots(room: RoomRuntime): void {
  // Con un solo humano en FFA crea un duelo 1v1; los modos por equipo llenan 4.
  const targetPlayers = room.config.teamMode === "DUEL" ? 2
    : room.config.teamMode === "TWO_V_ONE" ? 3
      : room.config.teamMode === "FFA" && room.game.playerCount === 1 ? 2 : 4;
  const usedNames = new Set(room.game.snapshot().players.map((player) => player.name));
  while (room.game.playerCount < targetPlayers) {
    const id = `bot:${randomUUID()}`;
    const baseName = botNames.find((name) => !usedNames.has(name)) ?? `Bot_${room.game.playerCount + 1}`;
    const player = room.game.addPlayer(id, baseName, true);
    if (!player) break;
    usedNames.add(player.name);
    room.bots.set(id, { timer: null, disabledUntil: 0, lastProgressAt: Date.now(), failedActions: 0, fogReadyAt: Date.now() + randomBetween(25_000, 45_000) });
  }
  emitState(room);
  emitRoomState(room);
}

function resolveBossPlayerId(room: RoomRuntime): string {
  if (room.config.teamMode !== "THREE_V_ONE" && room.config.teamMode !== "TWO_V_ONE") return room.hostPlayerId;
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
  if (room.phase === "SUDDEN_DEATH") finishMatch(room, true);
  return result;
}

function processGenericMove(room: RoomRuntime, playerId: string, payload: GenericMove, responder?: Socket): void {
  const engine = room.genericEngine;
  if (!engine) return;
  const result = engine.makeMove(playerId, payload, room.game);
  if (!result.accepted) {
    responder?.emit("generic:move-rejected", result);
    if (result.penaltyMs > 0) {
      const blockedUntil = Date.now() + result.penaltyMs;
      responder?.emit("player:penalty", { requestId: result.requestId, blockedUntil, reason: result.code });
    }
    emitState(room);
    emitGenericState(room);
    return;
  }
  responder?.emit("generic:move-accepted", result);
  emitState(room);
  emitGenericState(room);
  if (result.completed || room.phase === "SUDDEN_DEATH") finishMatch(room, true);
}

function emitGenericState(room: RoomRuntime): void {
  if (!room.genericEngine) return;
  const snapshot = room.genericEngine.snapshot(room.game);
  io.to(room.code).emit("generic:state", snapshot);
  if (room.config.gameType === "CROSS_LETTERS") {
    for (const player of room.game.snapshot().players) {
      io.to(player.id).emit("letters:rack", {
        letters: room.genericEngine.rackFor(player.id),
        activePlayerId: snapshot.meta.activePlayerId,
        turnEndsAt: snapshot.meta.turnEndsAt
      });
    }
  }
  if (room.config.gameType === "SECRET_CODE") {
    for (const player of room.game.snapshot().players) {
      io.to(player.id).emit("secret:role-state", room.genericEngine.secretStateFor(player.id));
    }
  }
}

function hasTopScoreTie(room: RoomRuntime): boolean {
  const results = room.game.matchResults();
  if (results.length < 2) return false;
  const top = results[0]!.teamScore;
  return new Set(results.filter((entry) => entry.teamScore === top).map((entry) => entry.teamId)).size > 1;
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
    goldenBonus: result.goldenBonus,
    combo: result.combo,
    comboMultiplier: result.comboMultiplier,
    comboBonus: result.comboBonus
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
  if (!runtime || (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH")) return;
  if (runtime.timer) clearTimeout(runtime.timer);
  const player = room.game.snapshot().players.find((entry) => entry.id === botId);
  const profile = botProfile(room.config.botDifficulty, player?.botPersona ?? null);
  const availableAt = Math.max(runtime.disabledUntil, player?.blockedUntil ?? 0);
  const thinkTime = randomBetween(profile.minDelayMs, profile.maxDelayMs);
  const delay = Math.max(thinkTime, availableAt - Date.now() + 80);
  runtime.timer = setTimeout(() => {
    runtime.timer = null;
    if (rooms.get(room.code) !== room || (room.phase !== "PLAYING" && room.phase !== "SUDDEN_DEATH")) return;
    if (room.tetrisEngine) {
      const action = (["LEFT", "RIGHT", "ROTATE", "SOFT_DROP", "HARD_DROP"] as TetrisAction[])[Math.floor(Math.random() * 5)]!;
      room.tetrisEngine.input(botId, action);
      scheduleBotAction(room, botId);
      return;
    }
    if (room.pacmanEngine) {
      const direction = (["UP", "RIGHT", "DOWN", "LEFT"] as PacmanDirection[])[Math.floor(Math.random() * 4)]!;
      room.pacmanEngine.input(botId, direction);
      scheduleBotAction(room, botId);
      return;
    }
    if (!runBotSupportPower(room, botId, runtime)) {
      runBotPower(room, botId);
      if (room.genericEngine) {
        // También hace avanzar el reloj de turno de Letras Cruzadas antes de que
        // el Bot decida si le corresponde actuar.
        room.genericEngine.snapshot(room.game);
        const proposal = room.genericEngine.createBotMove(profile.accuracy, botId);
        if (proposal) {
          const result = room.genericEngine.makeMove(botId, proposal, room.game);
          if (result.accepted) {
            runtime.lastProgressAt = Date.now();
            runtime.failedActions = 0;
          } else if (result.code !== "CELL_LOCKED") runtime.failedActions += 1;
          emitState(room);
          emitGenericState(room);
          if (result.completed || room.phase === "SUDDEN_DEATH") finishMatch(room, true);
        }
      } else {
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
    if (room.genericEngine) {
      const target = room.genericEngine.createBotMove(1, botId);
      if (!target) return false;
      const consumed = room.game.consumeGenericRevealPower(botId, now);
      if (consumed.accepted) {
        const reveal = room.genericEngine.makeMove(botId, target, room.game, now, { rewardEnergy: false });
        if (reveal.accepted) {
          emitState(room);
          emitGenericState(room);
          runtime.lastProgressAt = now;
          runtime.failedActions = 0;
          return true;
        }
      }
      return false;
    }
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
  if (!room.config.powersEnabled) return;
  const snapshot = room.game.snapshot();
  const bot = snapshot.players.find((player) => player.id === botId);
  const runtime = room.bots.get(botId);
  if (!runtime || Date.now() < runtime.fogReadyAt) return;
  const fogChance = bot?.botPersona === "TRICKSTER" ? 0.16 : 0.07;
  if (Math.random() > fogChance) return;
  if (!bot || bot.energy < 100) return;
  const targets = snapshot.players.filter((player) =>
    !player.isBot && player.id !== botId && (room.config.teamMode === "FFA" || player.teamId !== bot.teamId)
  );
  const target = targets[Math.floor(Math.random() * targets.length)];
  if (!target) return;
  const result = room.game.useFogPower(botId, target.id);
  if (!result.accepted || result.type !== "FOG") return;
  runtime.fogReadyAt = Date.now() + randomBetween(40_000, 65_000);
  applyFogDelivery(room, botId, result.recipientPlayerId, result.reflected ? result.targetPlayerId : undefined);
  emitState(room);
}

function botProfile(
  difficulty: BotDifficulty,
  persona: "CALCULATOR" | "TRICKSTER" | "GUARDIAN" | null
): { minDelayMs: number; maxDelayMs: number; accuracy: number } {
  const base = difficulty === "EASY"
    ? { minDelayMs: 2_400, maxDelayMs: 4_200, accuracy: 0.72 }
    : difficulty === "HARD"
      ? { minDelayMs: 700, maxDelayMs: 1_600, accuracy: 0.96 }
      : { minDelayMs: 1_300, maxDelayMs: 2_700, accuracy: 0.86 };
  if (persona === "CALCULATOR") return { ...base, minDelayMs: Math.round(base.minDelayMs * 1.12), accuracy: Math.min(0.99, base.accuracy + 0.03) };
  if (persona === "TRICKSTER") return { ...base, minDelayMs: Math.round(base.minDelayMs * 0.86), maxDelayMs: Math.round(base.maxDelayMs * 0.9), accuracy: Math.max(0.65, base.accuracy - 0.04) };
  if (persona === "GUARDIAN") return { ...base, minDelayMs: Math.round(base.minDelayMs * 1.05), maxDelayMs: Math.round(base.maxDelayMs * 1.05) };
  return base;
}

function clearBotTimers(room: RoomRuntime): void {
  for (const runtime of room.bots.values()) {
    if (runtime.timer) clearTimeout(runtime.timer);
    runtime.timer = null;
  }
}

function humanPlayers(room: RoomRuntime) {
  return room.game.snapshot().players.filter((player) => !player.isBot);
}

function maybeActivatePause(room: RoomRuntime): void {
  const humans = humanPlayers(room);
  if (!room.pauseRequesterId || humans.length === 0) return;
  const yes = room.pauseVotes.size;
  const no = room.pauseNoVotes.size;
  const allVoted = yes + no >= humans.length;
  const approved = humans.length === 1 || yes > humans.length / 2 || (allVoted && yes >= no);
  if (!approved) {
    if (allVoted) cancelPauseRequest(room, "La mayoría rechazó la pausa");
    return;
  }
  room.pausedPhase = room.phase === "SUDDEN_DEATH" ? "SUDDEN_DEATH" : "PLAYING";
  room.pausedAt = Date.now();
  room.pausedRemainingMs = Math.max(1_000, (room.endsAt ?? Date.now()) - Date.now());
  room.phase = "PAUSED";
  room.endsAt = null;
  if (room.matchTimeout) clearTimeout(room.matchTimeout);
  room.matchTimeout = null;
  clearBotTimers(room);
  if (room.tetrisTick) clearInterval(room.tetrisTick);
  room.tetrisTick = null;
  if (room.pacmanTick) clearInterval(room.pacmanTick);
  room.pacmanTick = null;
  if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
  room.boardEventTimeout = null;
  room.game.endBoardEvent();
  emitRoomState(room);
  emitState(room);
  io.to(room.code).emit("pause:started", { requesterId: room.pauseRequesterId });
}

function cancelPauseRequest(room: RoomRuntime, message: string): void {
  room.pauseRequesterId = null;
  room.pauseVotes.clear();
  room.pauseNoVotes.clear();
  emitRoomState(room);
  io.to(room.code).emit("pause:cancelled", { message });
}

function resumeRoom(room: RoomRuntime): void {
  if (rooms.get(room.code) !== room || room.phase !== "PAUSED") return;
  room.phase = room.pausedPhase ?? "PLAYING";
  room.endsAt = Date.now() + room.pausedRemainingMs;
  room.matchTimeout = setTimeout(() => finishMatch(room), room.pausedRemainingMs);
  room.totalPausedMs += room.pausedAt ? Date.now() - room.pausedAt : 0;
  clearPauseState(room);
  for (const botId of room.bots.keys()) scheduleBotAction(room, botId);
  if (room.tetrisEngine) startTetrisLoop(room);
  if (room.pacmanEngine) startPacmanLoop(room);
  emitRoomState(room);
  io.to(room.code).emit("pause:ended", { endsAt: room.endsAt });
}

function startTetrisLoop(room: RoomRuntime): void {
  if (!room.tetrisEngine || room.tetrisTick) return;
  room.tetrisTick = setInterval(() => {
    if (rooms.get(room.code) !== room || room.phase !== "PLAYING" || !room.tetrisEngine) return;
    room.tetrisEngine.tick();
    const snapshot = room.tetrisEngine.snapshot();
    snapshot.players.forEach((player: { id: string; score: number }) => room.game.setGenericScore(player.id, player.score));
    io.to(room.code).volatile.emit("tetris:state", snapshot);
    if (snapshot.completed) finishMatch(room, true);
  }, 100);
}

function startPacmanLoop(room: RoomRuntime): void {
  if (!room.pacmanEngine || room.pacmanTick) return;
  room.pacmanTick = setInterval(() => {
    if (rooms.get(room.code) !== room || room.phase !== "PLAYING" || !room.pacmanEngine) return;
    room.pacmanEngine.tick();
    const snapshot = room.pacmanEngine.snapshot();
    snapshot.players.forEach((player: { id: string; score: number }) => room.game.setGenericScore(player.id, player.score));
    io.to(room.code).volatile.emit("pacman:state", snapshot);
    if (snapshot.completed) finishMatch(room, true);
  }, 100);
}

function clearPauseState(room: RoomRuntime): void {
  if (room.resumeTimer) clearTimeout(room.resumeTimer);
  room.resumeTimer = null;
  room.pauseRequesterId = null;
  room.pauseVotes.clear();
  room.pauseNoVotes.clear();
  room.pausedAt = null;
  room.pausedRemainingMs = 0;
  room.pausedPhase = null;
  room.resumeCountdownEndsAt = null;
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
    if (request.method === "GET" && request.url?.startsWith("/api/leaderboards/game")) {
      const gameType = normalizeGameType(new URL(request.url, "http://local").searchParams.get("gameType"));
      if (!gameType) return sendJson(response, 400, { error: "Tipo de juego inválido" });
      sendJson(response, 200, await leaderboard.topGame(gameType));
      return;
    }
    if (request.method === "POST" && request.url === "/api/solo/challenge") {
      const cutoff = Date.now() - 14_400_000;
      for (const [existingToken, createdAt] of soloChallenges) {
        if (createdAt < cutoff) soloChallenges.delete(existingToken);
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
      const gameType = normalizeGameType(payload.gameType) ?? "SUDOKU";
      const score = Number(payload.score ?? 0);
      if (!Number.isInteger(elapsedMs) || elapsedMs < 30_000 || elapsedMs > 86_400_000) {
        sendJson(response, 400, { error: "Tiempo inválido" });
        return;
      }
      await leaderboard.recordSolo(nickname, elapsedMs);
      await leaderboard.recordGame(gameType, nickname, elapsedMs, Number.isFinite(score) ? score : 0, false);
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

function normalizeChat(value: string): string {
  return value.toUpperCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^A-ZÑ]/g, "");
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
  console.log(`Multi Arena escuchando en :${port}`);
});

function shutdown(): void {
  clearInterval(boardEventInterval);
  for (const room of rooms.values()) {
    if (room.boardEventTimeout) clearTimeout(room.boardEventTimeout);
    if (room.matchTimeout) clearTimeout(room.matchTimeout);
    if (room.resumeTimer) clearTimeout(room.resumeTimer);
    if (room.tetrisTick) clearInterval(room.tetrisTick);
    if (room.pacmanTick) clearInterval(room.pacmanTick);
    clearBotTimers(room);
  }
  io.close(() => process.exit(0));
}

process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
