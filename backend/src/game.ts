import { randomUUID } from "node:crypto";
import {
  BOARD_SIZE,
  CELL_POINTS,
  COMBO_WINDOW_MS,
  ENERGY_PER_HIT,
  FOG_POWER_COST,
  REFLECT_POWER_COST,
  REVEAL_POWER_COST,
  REFLECT_DURATION_MS,
  GOLDEN_CELL_BONUS,
  MAX_PLAYERS,
  MAX_ENERGY,
  MAX_COMBO_MULTIPLIER,
  MIRROR_CELL_POINTS,
  MIRROR_PENALTY_MS,
  PENALTY_MS,
  PLAYER_COLORS,
  SECTION_POINTS,
  SOLUTION
} from "./constants.js";
import type {
  ClearPlan,
  ActiveBoardEvent,
  BoardEventType,
  ConqueredSection,
  GameState,
  PlaceProposal,
  PlaceResult,
  PlayerState,
  PowerResult,
  PublicCell,
  RoomConfig,
  MatchResultEntry,
  PlayerRole,
  ActivePower
} from "./types.js";

interface InternalCell {
  value: number | null;
  ownerId: string | null;
  clearTokens: Set<string>;
  golden: boolean;
  ownerTeamId: string | null;
  given: boolean;
}

export class ArenaGame {
  private readonly board: InternalCell[][] = Array.from({ length: BOARD_SIZE }, () =>
    Array.from({ length: BOARD_SIZE }, () => ({
      value: null,
      ownerId: null,
      clearTokens: new Set<string>(),
      golden: false,
      ownerTeamId: null,
      given: false
    }))
  );

  private readonly players = new Map<string, PlayerState>();
  private readonly pendingSections = new Set<string>();
  private readonly processedRequests = new Map<string, Set<string>>();
  private readonly teamScores = new Map<string, number>();
  private readonly lastCorrectAt = new Map<string, number>();
  private revision = 0;
  private activeBoardEvent: ActiveBoardEvent | null = null;
  private configuration: RoomConfig = {
    gameType: "SUDOKU",
    powersEnabled: true,
    teamMode: "FFA",
    tileType: "NUMBERS",
    botDifficulty: "MEDIUM",
    puzzleDifficulty: "MEDIUM"
  };

  constructor(
    readonly gameId = "arena-main",
    private readonly solution: readonly (readonly number[])[] = SOLUTION
  ) {}

  get playerCount(): number {
    return this.players.size;
  }

  get humanPlayerCount(): number {
    return [...this.players.values()].filter((player) => !player.isBot).length;
  }

  hasPlayer(id: string): boolean {
    return this.players.has(id);
  }

  canPlayerAct(id: string, now = Date.now()): boolean {
    const player = this.players.get(id);
    return player !== undefined && player.blockedUntil <= now;
  }

  addPlayer(id: string, rawName: string, isBot = false, avatarId = "ORBIT"): PlayerState | null {
    if (this.players.size >= MAX_PLAYERS) return null;

    const usedSlots = new Set([...this.players.values()].map((player) => player.slot));
    const slot = PLAYER_COLORS.findIndex((_, index) => !usedSlots.has(index));
    const player: PlayerState = {
      id,
      name: sanitizeName(rawName, slot + 1),
      slot,
      color: PLAYER_COLORS[slot] ?? PLAYER_COLORS[0],
      score: 0,
      blockedUntil: 0,
      energy: 0,
      teamId: `PLAYER:${id}`,
      role: "PLAYER",
      teamScore: 0,
      isBot,
      shieldUntil: 0,
      combo: 0,
      maxCombo: 0,
      comboMultiplier: 1,
      botPersona: isBot ? botPersonaForSlot(slot) : null,
      powerLoadout: defaultLoadout(isBot ? botPersonaForSlot(slot) : null),
      avatarId: isBot ? botAvatarForSlot(slot) : sanitizeAvatar(avatarId)
    };
    this.players.set(id, player);
    this.processedRequests.set(id, new Set());
    this.teamScores.set(player.teamId, 0);
    this.revision += 1;
    return { ...player };
  }

  setPowerLoadout(playerId: string, powers: readonly ActivePower[]): boolean {
    const player = this.players.get(playerId);
    const unique = [...new Set(powers)];
    if (!player || unique.length !== 2 || unique.some((power) => !isActivePower(power))) return false;
    player.powerLoadout = unique;
    this.revision += 1;
    return true;
  }

  applyGenericSuccess(playerId: string, basePoints: number, energyGain: number, now = Date.now()): boolean {
    const player = this.players.get(playerId);
    if (!player || player.blockedUntil > now) return false;
    const previousCorrectAt = this.lastCorrectAt.get(playerId) ?? 0;
    player.combo = now - previousCorrectAt <= COMBO_WINDOW_MS ? player.combo + 1 : 1;
    player.maxCombo = Math.max(player.maxCombo, player.combo);
    player.comboMultiplier = Math.min(MAX_COMBO_MULTIPLIER, 1 + Math.floor((player.combo - 1) / 3));
    this.lastCorrectAt.set(playerId, now);
    const bossMultiplier = player.role === "BOSS" ? 2 : 1;
    const points = basePoints * player.comboMultiplier * bossMultiplier;
    player.score += points;
    player.energy = Math.min(MAX_ENERGY, player.energy + energyGain * bossMultiplier);
    this.awardTeam(player.teamId, points);
    this.revision += 1;
    return true;
  }

  applyGenericPenalty(playerId: string, blockedUntil: number): boolean {
    const player = this.players.get(playerId);
    if (!player) return false;
    player.blockedUntil = blockedUntil;
    player.combo = 0;
    player.comboMultiplier = 1;
    this.lastCorrectAt.delete(playerId);
    this.revision += 1;
    return true;
  }

  consumeGenericRevealPower(playerId: string, now = Date.now()): { accepted: boolean; message: string } {
    const player = this.players.get(playerId);
    if (!player) return { accepted: false, message: "Jugador no registrado" };
    if (!this.configuration.powersEnabled) return { accepted: false, message: "Los poderes están desactivados" };
    if (!player.powerLoadout.includes("REVEAL")) return { accepted: false, message: "Ojo de Lince no está equipado" };
    if (player.blockedUntil > now) return { accepted: false, message: "Estás temporalmente bloqueado" };
    if (player.energy < REVEAL_POWER_COST) return { accepted: false, message: "Necesitas 50% de energía" };
    player.energy -= REVEAL_POWER_COST;
    this.revision += 1;
    return { accepted: true, message: "Ojo de Lince activado" };
  }

  startMatch(config: RoomConfig, hostPlayerId: string): void {
    this.configuration = { ...config };
    this.teamScores.clear();
    for (const player of this.players.values()) {
      const assignment = teamAssignment(player, config.teamMode, hostPlayerId);
      player.teamId = assignment.teamId;
      player.role = assignment.role;
      player.score = 0;
      player.teamScore = 0;
      player.energy = 0;
      player.blockedUntil = 0;
      player.shieldUntil = 0;
      player.combo = 0;
      player.maxCombo = 0;
      player.comboMultiplier = 1;
      this.lastCorrectAt.delete(player.id);
      this.teamScores.set(player.teamId, 0);
    }
    this.seedInitialSudokuClues(config.puzzleDifficulty);
    this.revision += 1;
  }

  /** Reinicia la arena conservando jugadores, colores y slots para una revancha. */
  resetMatch(config: RoomConfig, hostPlayerId: string): void {
    for (const row of this.board) {
      for (const cell of row) {
        cell.value = null;
        cell.ownerId = null;
        cell.ownerTeamId = null;
        cell.golden = false;
        cell.given = false;
        cell.clearTokens.clear();
      }
    }
    this.pendingSections.clear();
    this.activeBoardEvent = null;
    for (const requests of this.processedRequests.values()) requests.clear();
    this.startMatch(config, hostPlayerId);
  }

  matchResults(): MatchResultEntry[] {
    return [...this.players.values()]
      .sort((left, right) => right.teamScore - left.teamScore || right.score - left.score || left.slot - right.slot)
      .map((player, index) => ({
        rank: index + 1,
        playerId: player.id,
        name: player.name,
        score: player.score,
        teamId: player.teamId,
        teamScore: player.teamScore,
        role: player.role,
        isBot: player.isBot,
        maxCombo: player.maxCombo
      }));
  }

  /**
   * Genera una intención de Bot; `place` sigue siendo la única autoridad que
   * valida la casilla, aplica carreras, puntos y penalizaciones.
   */
  createBotProposal(playerId: string, accuracy: number): PlaceProposal | null {
    const player = this.players.get(playerId);
    if (!player?.isBot) return null;
    const candidates = this.emptyPlayableCells();
    if (candidates.length === 0) return null;
    shuffle(candidates);
    const { row, column } = candidates[0]!;
    const solutionValue = this.solution[row]![column]!;
    const isCorrect = Math.random() < Math.max(0, Math.min(1, accuracy));
    const value = isCorrect
      ? solutionValue
      : ((solutionValue + 1 + Math.floor(Math.random() * 8) - 1) % 9) + 1;
    return { requestId: `bot-${randomUUID()}`, row, column, value, clientRevision: this.revision };
  }

  removePlayer(id: string): boolean {
    if (!this.players.delete(id)) return false;
    this.processedRequests.delete(id);

    // Las jugadas permanecen válidas, pero pasan a color neutral.
    for (const row of this.board) {
      for (const cell of row) {
        if (cell.ownerId === id) cell.ownerId = null;
      }
    }
    this.revision += 1;
    return true;
  }

  snapshot(now = Date.now()): GameState {
    return {
      gameId: this.gameId,
      revision: this.revision,
      serverTime: now,
      board: this.board.map((row) => row.map(toPublicCell)),
      players: [...this.players.values()]
        .sort((a, b) => a.slot - b.slot)
        .map((player) => ({ ...player })),
      boardEvent: this.activeBoardEvent ? { ...this.activeBoardEvent } : null
    };
  }

  startBoardEvent(type: BoardEventType, now = Date.now(), durationMs = 10_000): ActiveBoardEvent | null {
    if (this.activeBoardEvent) return null;
    this.activeBoardEvent = { type, startedAt: now, endsAt: now + durationMs };
    if (type === "GOLDEN_CELLS") {
      const candidates = this.emptyPlayableCells();
      shuffle(candidates);
      for (const { row, column } of candidates.slice(0, 2)) {
        this.board[row]![column]!.golden = true;
      }
    }
    this.revision += 1;
    return { ...this.activeBoardEvent };
  }

  endBoardEvent(expectedType?: BoardEventType): boolean {
    if (!this.activeBoardEvent || (expectedType && this.activeBoardEvent.type !== expectedType)) return false;
    for (const row of this.board) for (const cell of row) cell.golden = false;
    this.activeBoardEvent = null;
    this.revision += 1;
    return true;
  }

  useFogPower(playerId: string, targetPlayerId: unknown, now = Date.now()): PowerResult {
    const player = this.players.get(playerId);
    if (!player) return powerReject("PLAYER_NOT_FOUND", "Jugador no registrado");
    if (!this.configuration.powersEnabled) return powerReject("POWER_DISABLED", "Los poderes están desactivados");
    if (!player.powerLoadout.includes("FOG")) return powerReject("POWER_NOT_EQUIPPED", "Niebla no está equipada");
    if (typeof targetPlayerId !== "string" || targetPlayerId.length === 0) {
      return powerReject("INVALID_TARGET", "Objetivo inválido");
    }
    if (targetPlayerId === playerId) return powerReject("SELF_TARGET", "No puedes atacarte a ti mismo");
    const target = this.players.get(targetPlayerId);
    if (!target) return powerReject("TARGET_NOT_FOUND", "El rival ya no está conectado");
    if (!["FFA", "DUEL"].includes(this.configuration.teamMode) && target.teamId === player.teamId) {
      return powerReject("SAME_TEAM", "No puedes atacar a un compañero");
    }
    if (player.energy < FOG_POWER_COST) {
      return powerReject("NOT_ENOUGH_ENERGY", "Necesitas 100% de energía");
    }
    player.energy -= FOG_POWER_COST;
    const reflected = target.shieldUntil > now;
    const recipientPlayerId = reflected ? playerId : target.id;
    this.revision += 1;
    return {
      accepted: true,
      attackerId: playerId,
      targetPlayerId: target.id,
      recipientPlayerId,
      reflected,
      type: "FOG"
    };
  }

  useReflectPower(playerId: string, now = Date.now()): PowerResult {
    const player = this.players.get(playerId);
    if (!player) return powerReject("PLAYER_NOT_FOUND", "Jugador no registrado");
    if (!this.configuration.powersEnabled) return powerReject("POWER_DISABLED", "Los poderes están desactivados");
    if (!player.powerLoadout.includes("REFLECT")) return powerReject("POWER_NOT_EQUIPPED", "Escudo no está equipado");
    if (player.blockedUntil > now) return powerReject("PLAYER_BLOCKED", "No puedes activar poderes mientras estás bloqueado");
    if (player.energy < REFLECT_POWER_COST) return powerReject("NOT_ENOUGH_ENERGY", "Necesitas 100% de energía");
    player.energy -= REFLECT_POWER_COST;
    player.shieldUntil = now + REFLECT_DURATION_MS;
    this.revision += 1;
    return { accepted: true, playerId, shieldUntil: player.shieldUntil, type: "REFLECT" };
  }

  useRevealPower(
    playerId: string,
    row: unknown,
    column: unknown,
    requestId: unknown,
    now = Date.now()
  ): PowerResult {
    const player = this.players.get(playerId);
    if (!player) return powerReject("PLAYER_NOT_FOUND", "Jugador no registrado");
    if (!this.configuration.powersEnabled) return powerReject("POWER_DISABLED", "Los poderes están desactivados");
    if (!player.powerLoadout.includes("REVEAL")) return powerReject("POWER_NOT_EQUIPPED", "Ojo de Lince no está equipado");
    if (player.blockedUntil > now) return powerReject("PLAYER_BLOCKED", "No puedes activar poderes mientras estás bloqueado");
    if (player.energy < REVEAL_POWER_COST) return powerReject("NOT_ENOUGH_ENERGY", "Necesitas 50% de energía");
    if (
      !Number.isInteger(row) ||
      !Number.isInteger(column) ||
      Number(row) < 0 ||
      Number(row) >= BOARD_SIZE ||
      Number(column) < 0 ||
      Number(column) >= BOARD_SIZE
    ) {
      return powerReject("INVALID_CELL", "Selecciona una casilla válida");
    }
    const target = this.board[Number(row)]![Number(column)]!;
    if (target.value !== null || target.clearTokens.size > 0) {
      return powerReject("CELL_UNAVAILABLE", "La casilla seleccionada no está disponible");
    }
    player.energy -= REVEAL_POWER_COST;
    const placement = this.place(
      playerId,
      {
        requestId: typeof requestId === "string" && requestId.length > 0 ? requestId : `reveal-${randomUUID()}`,
        row: Number(row),
        column: Number(column),
        value: this.solution[Number(row)]![Number(column)]!
      },
      now,
      { grantEnergy: false }
    );
    if (!placement.accepted) {
      player.energy = Math.min(MAX_ENERGY, player.energy + REVEAL_POWER_COST);
      return powerReject("CELL_UNAVAILABLE", placement.message);
    }
    return { accepted: true, playerId, placement, type: "REVEAL" };
  }

  place(
    playerId: string,
    proposal: PlaceProposal,
    now = Date.now(),
    options: { grantEnergy?: boolean } = {}
  ): PlaceResult {
    const requestId = typeof proposal?.requestId === "string" ? proposal.requestId : "";
    if (!isValidProposal(proposal)) {
      return reject(requestId, "INVALID_PAYLOAD", "Jugada fuera de rango o mal formada");
    }

    const player = this.players.get(playerId);
    if (!player) return reject(requestId, "PLAYER_NOT_FOUND", "Jugador no registrado");

    const requests = this.processedRequests.get(playerId)!;
    if (requests.has(requestId)) {
      return reject(requestId, "DUPLICATE_REQUEST", "La solicitud ya fue procesada");
    }
    rememberRequest(requests, requestId);

    if (player.blockedUntil > now) {
      return {
        ...reject(requestId, "BLOCKED", "Jugador temporalmente bloqueado"),
        blockedUntil: player.blockedUntil
      };
    }

    const cell = this.board[proposal.row]![proposal.column]!;
    if (cell.clearTokens.size > 0) {
      return reject(requestId, "CELL_CLEARING", "La sección está siendo limpiada");
    }
    if (cell.value !== null) {
      return reject(requestId, "CELL_OCCUPIED", "La casilla ya está ocupada");
    }

    if (this.solution[proposal.row]![proposal.column] !== proposal.value) {
      const penaltyMs = this.activeBoardEvent?.type === "MIRROR_HOUR" ? MIRROR_PENALTY_MS : PENALTY_MS;
      player.blockedUntil = now + penaltyMs;
      player.combo = 0;
      player.comboMultiplier = 1;
      this.lastCorrectAt.delete(playerId);
      this.revision += 1;
      return {
        ...reject(requestId, "INCORRECT_VALUE", "Número incorrecto", true),
        blockedUntil: player.blockedUntil
      };
    }

    // Sección crítica sin await: lectura, validación y escritura son atómicas
    // respecto de los demás callbacks en este proceso Node.
    cell.value = proposal.value;
    cell.ownerId = playerId;
    cell.ownerTeamId = player.teamId;
    const previousCorrectAt = this.lastCorrectAt.get(playerId) ?? 0;
    player.combo = now - previousCorrectAt <= COMBO_WINDOW_MS ? player.combo + 1 : 1;
    player.maxCombo = Math.max(player.maxCombo, player.combo);
    player.comboMultiplier = Math.min(MAX_COMBO_MULTIPLIER, 1 + Math.floor((player.combo - 1) / 3));
    this.lastCorrectAt.set(playerId, now);
    const bossMultiplier = player.role === "BOSS" ? 2 : 1;
    const baseCellPoints = (this.activeBoardEvent?.type === "MIRROR_HOUR" ? MIRROR_CELL_POINTS : CELL_POINTS) * bossMultiplier;
    const comboBonus = baseCellPoints * (player.comboMultiplier - 1);
    const cellPoints = baseCellPoints + comboBonus;
    const goldenBonus = cell.golden ? GOLDEN_CELL_BONUS : 0;
    cell.golden = false;
    player.score += cellPoints + goldenBonus;
    if (this.configuration.powersEnabled && options.grantEnergy !== false) {
      player.energy = Math.min(MAX_ENERGY, player.energy + ENERGY_PER_HIT * bossMultiplier);
    }

    const sections = this.completedSections(proposal.row, proposal.column);
    const bonus = SECTION_POINTS * sections.length * sections.length;
    player.score += bonus;
    this.awardTeam(player.teamId, cellPoints + goldenBonus + bonus);

    const clearPlan = sections.length > 0 ? this.markForClearing(sections) : null;
    this.revision += 1;

    return {
      accepted: true,
      requestId,
      revision: this.revision,
      sections,
      bonus,
      cellPoints,
      goldenBonus,
      combo: player.combo,
      comboMultiplier: player.comboMultiplier,
      comboBonus,
      clearPlan
    };
  }

  executeClear(plan: ClearPlan): boolean {
    let changed = false;
    for (const { row, column } of plan.coordinates) {
      const cell = this.board[row]![column]!;
      if (!cell.clearTokens.delete(plan.token)) continue;
      // Aunque la casilla ya estuviera vacía por otra sección solapada, quitar
      // el último token cambia el campo público `clearing` y exige snapshot.
      changed = true;
      // Una casilla con otro token sigue bloqueada, pero la primera sección que
      // vence ya la vacía. Así no puede escribirse hasta terminar ambos timers.
      if (cell.given) continue;
      cell.value = null;
      cell.ownerId = null;
      cell.ownerTeamId = null;
      cell.golden = false;
    }
    for (const key of plan.sectionKeys) this.pendingSections.delete(key);
    if (changed) this.revision += 1;
    return changed;
  }

  private completedSections(row: number, column: number): ConqueredSection[] {
    const box = Math.floor(row / 3) * 3 + Math.floor(column / 3);
    const candidates: ConqueredSection[] = [
      { kind: "row", index: row },
      { kind: "column", index: column },
      { kind: "box", index: box }
    ];
    return candidates.filter((section) => {
      const key = sectionKey(section);
      return !this.pendingSections.has(key) && this.sectionCoordinates(section).every(
        ({ row: r, column: c }) => this.board[r]![c]!.value !== null
      );
    });
  }

  private markForClearing(sections: ConqueredSection[]): ClearPlan {
    const token = randomUUID();
    const unique = new Map<string, { row: number; column: number }>();
    const sectionKeys = sections.map(sectionKey);
    for (const key of sectionKeys) this.pendingSections.add(key);
    for (const section of sections) {
      for (const coordinate of this.sectionCoordinates(section)) {
        if (!this.board[coordinate.row]![coordinate.column]!.given) {
          unique.set(`${coordinate.row}:${coordinate.column}`, coordinate);
        }
      }
    }
    const coordinates = [...unique.values()];
    for (const { row, column } of coordinates) this.board[row]![column]!.clearTokens.add(token);
    return { token, coordinates, sectionKeys };
  }

  private sectionCoordinates(section: ConqueredSection): Array<{ row: number; column: number }> {
    if (section.kind === "row") {
      return Array.from({ length: BOARD_SIZE }, (_, column) => ({ row: section.index, column }));
    }
    if (section.kind === "column") {
      return Array.from({ length: BOARD_SIZE }, (_, row) => ({ row, column: section.index }));
    }
    const startRow = Math.floor(section.index / 3) * 3;
    const startColumn = (section.index % 3) * 3;
    return Array.from({ length: 9 }, (_, offset) => ({
      row: startRow + Math.floor(offset / 3),
      column: startColumn + (offset % 3)
    }));
  }

  private emptyPlayableCells(): Array<{ row: number; column: number }> {
    const cells: Array<{ row: number; column: number }> = [];
    for (let row = 0; row < BOARD_SIZE; row += 1) {
      for (let column = 0; column < BOARD_SIZE; column += 1) {
        const cell = this.board[row]![column]!;
        if (cell.value === null && cell.clearTokens.size === 0) cells.push({ row, column });
      }
    }
    return cells;
  }

  private awardTeam(teamId: string, points: number): void {
    const total = (this.teamScores.get(teamId) ?? 0) + points;
    this.teamScores.set(teamId, total);
    for (const player of this.players.values()) {
      if (player.teamId === teamId) player.teamScore = total;
    }
  }

  private seedInitialSudokuClues(difficulty: RoomConfig["puzzleDifficulty"]): void {
    const clueCount = difficulty === "EASY" ? 40 : difficulty === "MEDIUM" ? 32 : difficulty === "HARD" ? 26 : 22;
    const cells = Array.from({ length: BOARD_SIZE * BOARD_SIZE }, (_, index) => ({
      row: Math.floor(index / BOARD_SIZE),
      column: index % BOARD_SIZE
    }));
    shuffle(cells);
    for (const { row, column } of cells.slice(0, clueCount)) {
      const target = this.board[row]![column]!;
      target.value = this.solution[row]![column]!;
      target.ownerId = null;
      target.ownerTeamId = null;
      target.given = true;
    }
  }
}

function toPublicCell(cell: InternalCell): PublicCell {
  return {
    value: cell.value,
    ownerId: cell.ownerId,
    clearing: cell.clearTokens.size > 0,
    golden: cell.golden,
    ownerTeamId: cell.ownerTeamId,
    given: cell.given
  };
}

function isValidProposal(proposal: PlaceProposal): boolean {
  return (
    proposal !== null &&
    typeof proposal === "object" &&
    typeof proposal.requestId === "string" &&
    proposal.requestId.length >= 1 &&
    proposal.requestId.length <= 100 &&
    Number.isInteger(proposal.row) &&
    proposal.row >= 0 &&
    proposal.row < BOARD_SIZE &&
    Number.isInteger(proposal.column) &&
    proposal.column >= 0 &&
    proposal.column < BOARD_SIZE &&
    Number.isInteger(proposal.value) &&
    proposal.value >= 1 &&
    proposal.value <= 9
  );
}

function reject(
  requestId: string,
  code: Extract<PlaceResult, { accepted: false }>["code"],
  message: string,
  stateChanged = false
): Extract<PlaceResult, { accepted: false }> {
  return { accepted: false, requestId, code, message, stateChanged };
}

function rememberRequest(requests: Set<string>, requestId: string): void {
  requests.add(requestId);
  if (requests.size <= 256) return;
  const oldest = requests.values().next().value as string | undefined;
  if (oldest !== undefined) requests.delete(oldest);
}

function sectionKey(section: ConqueredSection): string {
  return `${section.kind}:${section.index}`;
}

function sanitizeName(rawName: string, fallbackNumber: number): string {
  const clean = typeof rawName === "string" ? rawName.trim().replace(/\s+/g, " ").slice(0, 24) : "";
  return clean || `Jugador ${fallbackNumber}`;
}

function powerReject(
  code: Extract<PowerResult, { accepted: false }>["code"],
  message: string
): Extract<PowerResult, { accepted: false }> {
  return { accepted: false, code, message };
}

function shuffle<T>(values: T[]): void {
  for (let index = values.length - 1; index > 0; index -= 1) {
    const target = Math.floor(Math.random() * (index + 1));
    [values[index], values[target]] = [values[target]!, values[index]!];
  }
}

function teamAssignment(
  player: PlayerState,
  mode: RoomConfig["teamMode"],
  hostPlayerId: string
): { teamId: string; role: PlayerRole } {
  if (mode === "TWO_V_TWO") {
    return { teamId: player.slot % 2 === 0 ? "TEAM_A" : "TEAM_B", role: "TEAMMATE" };
  }
  if (mode === "THREE_V_ONE" || mode === "TWO_V_ONE") {
    return player.id === hostPlayerId
      ? { teamId: "BOSS", role: "BOSS" }
      : { teamId: "RAIDERS", role: "RAIDER" };
  }
  return { teamId: `PLAYER:${player.id}`, role: "PLAYER" };
}

function botAvatarForSlot(slot: number): string {
  return ["ROBOT", "BRAIN", "NINJA", "ASTRO"][slot % 4]!;
}

function sanitizeAvatar(value: string): string {
  return ["ORBIT", "NOVA", "PIXEL", "NINJA", "ASTRO", "BRAIN", "ROBOT", "FOX"].includes(value)
    ? value
    : "ORBIT";
}

function botPersonaForSlot(slot: number): PlayerState["botPersona"] {
  const personas: NonNullable<PlayerState["botPersona"]>[] = ["CALCULATOR", "TRICKSTER", "GUARDIAN"];
  return personas[slot % personas.length]!;
}

function defaultLoadout(persona: PlayerState["botPersona"]): ActivePower[] {
  if (persona === "CALCULATOR") return ["REVEAL", "REFLECT"];
  if (persona === "GUARDIAN") return ["REFLECT", "FOG"];
  return ["FOG", "REVEAL"];
}

function isActivePower(value: unknown): value is ActivePower {
  return value === "FOG" || value === "REFLECT" || value === "REVEAL";
}
