import { randomUUID } from "node:crypto";
import type { ArenaGame } from "../game.js";
import { createPuzzleBlueprint } from "./blueprints.js";
import { SCRABBLE_SCORES } from "./blueprints.js";
import { SPANISH_DICTIONARY } from "./spanishDictionary.js";
import { attackRange, calculateDamage, cooldownFor, movementRange, skillCost, skillFor, type Piece } from "./chessTactics.js";
import type {
  CellValue,
  GameType,
  GenericBoardState,
  GenericCell,
  GenericMove,
  GenericMoveResult,
  PuzzleDifficulty
} from "./types.js";
import type { PuzzleGenerationOptions } from "./types.js";

const GENERIC_HIT_POINTS = 10;
const GENERIC_ENERGY = 25;
const STRICT_PLAYER_TURN_GAMES = new Set<GameType>([
  "MINESWEEPER", "CROSSWORD", "DOTS_AND_BOXES", "HANGMAN",
  "TIC_TAC_TOE", "CHECKERS", "CHESS_TACTICS",
]);

interface WordPlacement {
  word: string;
  startRow: number;
  startCol: number;
  endRow: number;
  endCol: number;
  rowStep: number;
  colStep: number;
}

interface TowerEnemy {
  id: string;
  kind: "SCOUT" | "BRUTE" | "PHANTOM" | "GOLEM";
  hp: number;
  maxHp: number;
  progress: number;
  speed: number;
  spawnAt: number;
  slowUntil: number;
  status: "WAITING" | "MOVING" | "DEFEATED" | "LEAKED";
}

interface TowerProjectile {
  id: string;
  towerRow: number;
  towerCol: number;
  targetId: string;
  color: string;
  damage: number;
  towerType: string;
  firedAt: number;
  arrivesAt: number;
}

/**
 * Motor matricial autoritativo. Las reglas que no son naturalmente matriciales
 * Las reglas especializadas se codifican en `val`, `cell.meta` y validadores puros.
 */
export class GenericPuzzleEngine {
  private board: GenericCell[][];
  private answers: CellValue[][];
  private meta: Record<string, unknown>;
  private revision = 0;
  private completed = false;
  private readonly processed = new Map<string, Set<string>>();
  private readonly racks = new Map<string, string[]>();
  private readonly suggestedWords = new Map<string, string>();
  private readonly letterBag = shuffleLetters([...LETTER_BAG, ...LETTER_BAG]);
  private turnOrder: string[] = [];
  private activePlayerId: string | null = null;
  private turnEndsAt = 0;
  private readonly secretAssignments = new Map<string, { team: "RED" | "BLUE"; role: "CAPTAIN" | "OPERATIVE" }>();
  private secretCurrentTeam: "RED" | "BLUE" = "BLUE";
  private secretClue: { word: string; count: number; remaining: number } | null = null;
  private secretWinnerTeam: "RED" | "BLUE" | null = null;
  private readonly capitalBalances = new Map<string, number>();
  private readonly capitalPositions = new Map<string, number>();
  private readonly capitalPropertyOwners = new Map<number, string>();
  private readonly capitalPropertyLevels = new Map<number, number>();
  private readonly capitalSkillsUsed = new Set<string>();
  private capitalStage: "ROLL" | "BUY_OR_END" | "END" = "ROLL";
  private capitalPendingProperty: number | null = null;
  private capitalDice: [number, number] = [1, 1];
  private capitalLastMove: { playerId: string; from: number; to: number } | null = null;
  private capitalEvent = "La economía neón está lista";
  private capitalCard: {
    id: string;
    playerId: string;
    title: string;
    description: string;
    kind: "BONUS" | "PENALTY" | "MOVE";
  } | null = null;
  private readonly hangmanGuesses = new Set<string>();
  private readonly hangmanErrors = new Map<string, number>();
  private readonly hangmanRevealUsed = new Set<string>();
  private readonly hangmanDiscardUsed = new Set<string>();
  private readonly hangmanLastBreathUsed = new Set<string>();
  private readonly hangmanDiscarded = new Map<string, string[]>();
  private readonly hiddenWord: string;
  private readonly arrowRemoved = new Map<string, Set<string>>();
  private readonly arrowFailedTaps = new Map<string, number>();
  private readonly arrowRotateUses = new Map<string, number>();
  private readonly arrowMissileUses = new Map<string, number>();
  private readonly arrowCombo = new Map<string, number>();
  private readonly arrowLastSuccessAt = new Map<string, number>();
  private readonly arrowBlockedUntil = new Map<string, number>();
  private arrowTimerStartedAt = Date.now();
  private readonly arrowTimerTriggered = new Map<string, Set<string>>();
  private arrowStaticGeometryChanged = false;
  private arrowStageAdvancedTo: number | null = null;
  private readonly capturedPawns = new Map<"BLUE" | "RED", number>([["BLUE", 0], ["RED", 0]]);
  private readonly memoryFirstPicks = new Map<string, { row: number; col: number }>();
  private readonly nurikabeSonarUses = new Map<string, number>();
  private readonly towerCredits = new Map<string, number>();
  private towerWave = 0;
  private towerBaseHealth = 20;
  private towerLastReport: Record<string, unknown> | null = null;
  private towerWaveActive = false;
  private towerEnemies: TowerEnemy[] = [];
  private towerProjectiles: TowerProjectile[] = [];
  private towerLastTickAt = 0;
  private towerWaveStartedAt = 0;
  private readonly towerNextShotAt = new Map<string, number>();
  private readonly towerWaveKills = new Map<string, number>();
  private readonly reactorPowerEnergy = new Map<string, number>();
  private mergeBotStep = 0;

  constructor(readonly gameType: GameType, readonly gameId: string, options: PuzzleGenerationOptions = {}) {
    const blueprint = createPuzzleBlueprint(gameType, options);
    this.board = blueprint.board;
    this.answers = blueprint.answers;
    this.meta = blueprint.meta;
    this.hiddenWord = gameType === "HANGMAN" ? blueprint.answers[0]!.map(String).join("") : "";
  }

  setFirstPlayer(playerId: string, now = Date.now()): void {
    this.activePlayerId = playerId;
    this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
  }

  setTurnOrder(playerIds: string[], now = Date.now()): void {
    this.turnOrder = [...playerIds];
    this.activePlayerId = this.turnOrder[0] ?? null;
    this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
  }

  /** El servidor usa este pulso para enviar la geometria completa de una etapa nueva. */
  consumeStaticGeometryChanged(): boolean {
    const changed = this.arrowStaticGeometryChanged;
    this.arrowStaticGeometryChanged = false;
    return changed;
  }

  snapshot(game: ArenaGame, now = Date.now()): GenericBoardState {
    if (this.gameType === "CROSS_LETTERS") this.syncLetterPlayers(game, now);
    if (STRICT_PLAYER_TURN_GAMES.has(this.gameType)) this.syncTurnPlayers(game);
    if (this.gameType === "SECRET_CODE") this.syncSecretPlayers(game);
    if (this.gameType === "CAPITAL_ARENA") this.syncCapitalPlayers(game);
    if (this.gameType === "TOWER_DEFENSE") this.syncTowerPlayers(game);
    return {
      gameId: this.gameId,
      gameType: this.gameType,
      revision: this.revision,
      serverTime: now,
      rows: this.board.length,
      columns: this.board[0]?.length ?? 0,
      board: this.board.map((row) => row.map((cell) => ({ ...cell, meta: { ...cell.meta } }))),
      players: game.snapshot(now).players,
      completed: this.completed,
      meta: {
        ...structuredClone(this.meta),
        ...(this.gameType === "CROSS_LETTERS" ? {
          activePlayerId: null,
          blitz: true,
          tilesRemaining: this.letterBag.length,
          rackCounts: Object.fromEntries([...this.racks].map(([id, rack]) => [id, rack.length]))
        } : {}),
        ...(STRICT_PLAYER_TURN_GAMES.has(this.gameType) ? { currentPlayerTurn: this.activePlayerId } : {}),
        ...(this.gameType === "SECRET_CODE" ? {
          currentPlayerTurn: this.secretActivePlayerId(),
          currentTeam: this.secretCurrentTeam,
          clue: this.secretClue,
          winnerTeam: this.secretWinnerTeam,
          remaining: this.secretRemainingCounts()
        } : {}),
        ...(this.gameType === "CAPITAL_ARENA" ? {
          currentPlayerTurn: this.activePlayerId,
          stage: this.capitalStage,
          pendingProperty: this.capitalPendingProperty,
          dice: this.capitalDice,
          lastMove: this.capitalLastMove,
          lastEvent: this.capitalEvent,
          surpriseCard: this.capitalCard,
          balances: Object.fromEntries(this.capitalBalances),
          positions: Object.fromEntries(this.capitalPositions),
          propertyOwners: Object.fromEntries(this.capitalPropertyOwners),
          propertyLevels: Object.fromEntries(this.capitalPropertyLevels),
          skillsUsed: [...this.capitalSkillsUsed],
        } : {}),
        ...(this.gameType === "HANGMAN" ? {
          guessedLetters: [...this.hangmanGuesses],
          errors: Object.fromEntries(this.hangmanErrors),
          mistakesMade: [...this.hangmanErrors.values()].reduce((sum, value) => sum + value, 0),
          maskedWord: this.board[0]!.map((cell) => cell.value?.toString() ?? "_"),
          // Compatibilidad del contrato solicitado: nunca contiene la respuesta,
          // solo letras ya descubiertas y guiones.
          hiddenWord: this.board[0]!.map((cell) => cell.value?.toString() ?? "_"),
          wrongGuesses: [...this.hangmanGuesses].filter((letter) => !this.hiddenWord.includes(letter)),
          correctGuesses: [...this.hangmanGuesses].filter((letter) => this.hiddenWord.includes(letter)),
          revealUsed: [...this.hangmanRevealUsed],
          discardUsed: [...this.hangmanDiscardUsed],
          lastBreathUsed: [...this.hangmanLastBreathUsed],
          discardedByPlayer: Object.fromEntries(this.hangmanDiscarded),
          eliminated: [...this.hangmanErrors].filter(([, errors]) => errors >= 6).map(([id]) => id),
          currentPlayerTurn: this.activePlayerId,
          ...(this.completed && this.board[0]!.some((cell) => cell.value === null)
            ? { answerOnGameOver: this.hiddenWord }
            : {}),
        } : {}),
        ...(this.gameType === "ARROWS_ESCAPE" ? {
          progress: Object.fromEntries([...this.arrowRemoved].map(([id, removed]) => [id, removed.size])),
          removedByPlayer: Object.fromEntries([...this.arrowRemoved].map(([id, removed]) => [id, [...removed]])),
          failedTaps: Object.fromEntries(this.arrowFailedTaps),
          rotateUses: Object.fromEntries(this.arrowRotateUses),
          missileUses: Object.fromEntries(this.arrowMissileUses),
          combos: Object.fromEntries(this.arrowCombo),
          blockedUntil: Object.fromEntries(this.arrowBlockedUntil),
          timerStartedAt: this.arrowTimerStartedAt,
        } : {}),
        ...(this.gameType === "MEMORY_NEON" ? {
          pairsFound: this.board.flat().filter((cell) => cell.ownerId !== null).length / 2,
          activePicks: Object.fromEntries([...this.memoryFirstPicks].map(([id, pick]) => [id, `${pick.row}:${pick.col}`])),
        } : {}),
        ...(this.gameType === "NURIKABE" ? {
          sonarUses: Object.fromEntries(this.nurikabeSonarUses),
        } : {}),
        ...(this.gameType === "TOWER_DEFENSE" ? {
          wave: this.towerWave,
          baseHealth: this.towerBaseHealth,
          credits: Object.fromEntries(this.towerCredits),
          lastWave: this.towerLastReport,
          waveActive: this.towerWaveActive,
          enemies: this.towerEnemies.map((enemy) => ({ ...enemy })),
          projectiles: this.towerProjectiles.map((projectile) => ({ ...projectile })),
          remainingEnemies: this.towerEnemies.filter((enemy) =>
            enemy.status === "WAITING" || enemy.status === "MOVING"
          ).length,
          waveDeadline: this.towerWaveStartedAt > 0 ? this.towerWaveStartedAt + 75_000 : 0,
        } : {}),
        ...(this.gameType === "REACTOR_CHAIN" ? {
          level: Math.min(100, 1 + Math.floor(Number(this.meta.reactorScore ?? 0) / 600)),
          levelProgress: Number(this.meta.reactorScore ?? 0) % 600,
        } : {})
      }
    };
  }

  rackFor(playerId: string): string[] {
    return [...(this.racks.get(playerId) ?? [])];
  }

  shuffleRack(playerId: string): string[] {
    const rack = this.racks.get(playerId) ?? [];
    for (let index = rack.length - 1; index > 0; index -= 1) {
      const target = Math.floor(Math.random() * (index + 1));
      [rack[index], rack[target]] = [rack[target]!, rack[index]!];
    }
    return [...rack];
  }

  secretStateFor(playerId: string): Record<string, unknown> | null {
    if (this.gameType !== "SECRET_CODE") return null;
    const assignment = this.secretAssignments.get(playerId);
    if (!assignment) return null;
    return {
      ...assignment,
      currentTeam: this.secretCurrentTeam,
      clue: this.secretClue,
      winnerTeam: this.secretWinnerTeam,
      key: assignment.role === "CAPTAIN" ? this.answers.flat().map(String) : null
    };
  }

  secretWords(): string[] {
    return this.gameType === "SECRET_CODE" ? this.board.flat().map((cell) => String(cell.value ?? "")) : [];
  }

  secretTeamFor(playerId: string): string | null {
    return this.secretAssignments.get(playerId)?.team ?? null;
  }

  makeMove(
    playerId: string,
    move: GenericMove,
    game: ArenaGame,
    now = Date.now(),
    options: { rewardEnergy?: boolean } = {}
  ): GenericMoveResult {
    const invalid = this.validateEnvelope(move);
    if (invalid) return this.reject(move?.requestId ?? "", "INVALID_MOVE", invalid);
    if (this.completed) return this.reject(move.requestId, "FINISHED", "El puzzle ya terminó");
    if (!game.canPlayerAct(playerId, now)) {
      return this.reject(move.requestId, "PLAYER_BLOCKED", "Jugador temporalmente bloqueado");
    }
    const requests = this.processed.get(playerId) ?? new Set<string>();
    this.processed.set(playerId, requests);
    if (requests.has(move.requestId)) return this.reject(move.requestId, "DUPLICATE", "Jugada duplicada");
    requests.add(move.requestId);

    if (this.gameType === "CROSS_LETTERS") this.syncLetterPlayers(game, now);
    if (STRICT_PLAYER_TURN_GAMES.has(this.gameType)) {
      this.syncTurnPlayers(game);
      if (this.activePlayerId !== playerId) return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno");
    }
    if (this.gameType === "SECRET_CODE") {
      this.syncSecretPlayers(game);
      if (this.secretActivePlayerId() !== playerId) return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno de equipo");
    }
    if (this.gameType === "CAPITAL_ARENA") {
      this.syncCapitalPlayers(game);
      if (this.activePlayerId !== playerId) return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno económico");
    }

    const cell = this.board[move.row]![move.col]!;
    if (cell.isBlocked || (cell.ownerId !== null && !["NURIKABE", "HITORI", "CROSS_LETTERS", "WORD_SEARCH", "CAPITAL_ARENA", "HANGMAN", "ARROWS_ESCAPE", "CHECKERS", "CHESS_TACTICS", "MERGE_2048", "TOWER_DEFENSE"].includes(this.gameType))) {
      return this.reject(move.requestId, "CELL_LOCKED", "Casilla ya resuelta");
    }

    const outcome = this.applySpecificMove(playerId, move, cell, game, now);
    if (!outcome.correct && outcome.neutral === true) {
      return this.reject(move.requestId, "INVALID_MOVE", outcome.message ?? "Entrada no válida");
    }
    if (!outcome.correct) {
      if (this.gameType === "CHESS_TACTICS" || this.gameType === "NEXUS_ZERO") {
        return this.reject(move.requestId, "INVALID_MOVE", outcome.message ?? "Movimiento inválido; conserva tu turno");
      }
      const penaltyMs = this.gameType === "MINESWEEPER" && outcome.hitMine ? 5_000 : 3_000;
      game.applyGenericPenalty(playerId, now + penaltyMs);
      // En Damas una jugada ilegal no consume el turno.
      if (STRICT_PLAYER_TURN_GAMES.has(this.gameType) && this.gameType !== "CHECKERS") this.advanceStrictTurn();
      this.revision += 1;
      return {
        accepted: false,
        requestId: move.requestId,
        code: "INCORRECT",
        message: outcome.hitMine ? "¡Mina! Congelado durante 5 segundos" : "Movimiento incorrecto",
        points: 0,
        penaltyMs,
        completed: false
      };
    }

    const points = outcome.points ?? GENERIC_HIT_POINTS;
    game.applyGenericSuccess(playerId, points, options.rewardEnergy === false || points <= 0 ? 0 : GENERIC_ENERGY, now);
    this.completed = this.isPuzzleComplete();
    const advancedStage = this.arrowStageAdvancedTo;
    this.arrowStageAdvancedTo = null;
    if (STRICT_PLAYER_TURN_GAMES.has(this.gameType) && outcome.extraTurn !== true) this.advanceStrictTurn();
    this.revision += 1;
    return {
      accepted: true,
      requestId: move.requestId,
      message: this.completed ? "100 etapas completadas"
        : advancedStage ? `Etapa ${advancedStage}/100`
          : outcome.message ?? "Movimiento aceptado",
      points,
      penaltyMs: 0,
      completed: this.completed
    };
  }

  /** Produce una intención; siempre vuelve a pasar por `makeMove`. */
  createBotMove(accuracy: number, playerId?: string): GenericMove | null {
    if (this.gameType === "CROSS_LETTERS") return this.createCrossLettersBotMove(accuracy, playerId);
    if (this.gameType === "SECRET_CODE") return this.createSecretBotMove(playerId);
    if (this.gameType === "CAPITAL_ARENA") return this.createCapitalBotMove(playerId);
    if (this.gameType === "MEMORY_NEON") {
      const effectivePlayerId = playerId ?? this.memoryFirstPicks.keys().next().value as string | undefined;
      const first = effectivePlayerId ? this.memoryFirstPicks.get(effectivePlayerId) : null;
      if (first) {
        const answer = this.answers[first.row]![first.col];
        for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
          if ((row !== first.row || col !== first.col) && this.board[row]![col]!.value === null
            && this.board[row]![col]!.ownerId === null && this.answers[row]![col] === answer) {
            return { requestId: `memory-bot-${randomUUID()}`, row, col, val: "FLIP" };
          }
        }
      }
      for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
        if (this.board[row]![col]!.value === null && this.board[row]![col]!.ownerId === null) {
          return { requestId: `memory-bot-${randomUUID()}`, row, col, val: "FLIP" };
        }
      }
      return null;
    }
    if (this.gameType === "MERGE_2048") {
      // Mantener una esquina es una estrategia simple pero consistente; si un
      // movimiento no cambia el tablero, los siguientes intentan otra dirección.
      const directions = ["LEFT", "DOWN", "LEFT", "RIGHT", "DOWN", "LEFT", "UP"];
      return {
        requestId: `merge-bot-${randomUUID()}`,
        row: 0,
        col: 0,
        val: directions[this.mergeBotStep++ % directions.length]!,
      };
    }
    if (this.gameType === "NEXUS_ZERO") {
      // El generador garantiza parejas resolubles por línea. Mantener el eje
      // horizontal evita separar una pareja verticalmente entre intentos.
      return { requestId: `nexus-bot-${randomUUID()}`, row: 0, col: 0, val: "LEFT" };
    }
    if (this.gameType === "TOWER_DEFENSE") {
      const buildable = this.board.flatMap((row, rowIndex) =>
        row.map((cell, colIndex) => ({ cell, row: rowIndex, col: colIndex }))
      ).filter(({ cell }) => cell.meta.buildable === true && cell.meta.towerType == null);
      if (buildable.length > 0 && (this.towerCredits.get(playerId ?? "") ?? 0) >= 100) {
        const target = buildable[Math.floor(Math.random() * buildable.length)]!;
        return {
          requestId: `tower-bot-${randomUUID()}`, row: target.row, col: target.col,
          val: { action: "BUILD", towerType: ["RAPID", "BLAST", "SNIPER", "FROST"][this.mergeBotStep++ % 4] },
        };
      }
      return { requestId: `tower-wave-${randomUUID()}`, row: 0, col: 0, val: { action: "START_WAVE" } };
    }
    if (this.gameType === "REACTOR_CHAIN") {
      for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
        if (this.board[row]![col]!.meta.special || this.reactorGroupSize(row, col) >= 3) {
          return { requestId: `reactor-bot-${randomUUID()}`, row, col, val: "CHAIN" };
        }
      }
      return null;
    }
    if (this.gameType === "HANGMAN") {
      const target = this.board[0]!.findIndex((cell) => cell.value === null);
      if (target < 0) return null;
      return { requestId: `hangman-bot-${randomUUID()}`, row: 0, col: target, val: this.answers[0]![target] };
    }
    if (this.gameType === "ARROWS_ESCAPE") {
      const removed = this.arrowRemoved.get(playerId ?? "bot") ?? new Set<string>();
      for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
        const key = `${row}:${col}`; if (removed.has(key)) continue;
        const shapeId = String(this.board[row]![col]!.meta.shapeId ?? key);
        if (this.canArrowShapeEscape(shapeId, removed)) {
          return { requestId: `arrows-bot-${randomUUID()}`, row, col, val: "ESCAPE" };
        }
      }
      return null;
    }
    if (STRICT_PLAYER_TURN_GAMES.has(this.gameType) && playerId && this.activePlayerId !== playerId) return null;
    if (this.gameType === "TIC_TAC_TOE") {
      let forced = this.meta.forcedMini as { row: number; col: number } | null;
      const miniWinners = (this.meta.miniWinners ?? {}) as Record<string, string>;
      if (forced) {
        const forcedKey = `${forced.row}:${forced.col}`;
        const hasFreeCell = this.board.slice(forced.row * 3, forced.row * 3 + 3)
          .some((row) => row.slice(forced!.col * 3, forced!.col * 3 + 3).some((candidate) => candidate.value === null));
        if (miniWinners[forcedKey] || !hasFreeCell) { forced = null; this.meta.forcedMini = null; }
      }
      const candidates: Array<{ row: number; col: number }> = [];
      for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
        if (forced && !miniWinners[`${forced.row}:${forced.col}`]
          && (Math.floor(row / 3) !== forced.row || Math.floor(col / 3) !== forced.col)) continue;
        if (miniWinners[`${Math.floor(row / 3)}:${Math.floor(col / 3)}`]) continue;
        if (this.board[row]![col]!.value === null) {
          candidates.push({ row, col });
        }
      }
      const candidate = candidates[Math.floor(Math.random() * candidates.length)];
      return candidate ? { requestId: `gato-bot-${randomUUID()}`, ...candidate, val: "MARK" } : null;
    }
    if (this.gameType === "CHECKERS" && playerId) {
      const team = this.activeTeam(playerId);
      const captures = this.checkersCapturesFor(team);
      const sources = captures.length ? captures : this.board.flatMap((row, rowIndex) =>
        row.map((cell, colIndex) => ({ cell, row: rowIndex, col: colIndex })).filter(({ cell }) => cell.meta.team === team)
      );
      for (const source of sources) {
        const capture = this.checkersCapturesFrom(source.row, source.col, team)[0];
        if (capture) return { requestId: `checkers-bot-${randomUUID()}`, row: source.row, col: source.col, val: { targetRow: capture.row, targetCol: capture.col } };
        const direction = team === "BLUE" ? 1 : -1;
        for (const dx of [-1, 1]) {
          const target = this.board[source.row + direction]?.[source.col + dx];
          if (target && !target.isBlocked && target.value === null) {
            return { requestId: `checkers-bot-${randomUUID()}`, row: source.row, col: source.col, val: { targetRow: source.row + direction, targetCol: source.col + dx } };
          }
        }
      }
      return null;
    }
    if (this.gameType === "CHESS_TACTICS" && playerId) {
      const team = this.activeTeam(playerId);
      for (let row = 0; row < 8; row += 1) for (let col = 0; col < 8; col += 1) {
        const piece = pieceFromCell(this.board[row]![col]!);
        if (!piece || piece.team !== team) continue;
        const attack = attackRange(piece, { row, col }).find((point) => {
          const enemy = pieceFromCell(this.board[point.row]![point.col]!);
          return enemy && enemy.team !== team;
        });
        if (attack && piece.ap >= 2) return { requestId: `chess-bot-${randomUUID()}`, row, col, val: { action: "ATTACK", targetRow: attack.row, targetCol: attack.col } };
        const target = movementRange(piece, { row, col }).find((point) => this.board[point.row]![point.col]!.value === null);
        if (target) return { requestId: `chess-bot-${randomUUID()}`, row, col, val: { action: "MOVE", targetRow: target.row, targetCol: target.col } };
      }
      return null;
    }
    if (this.gameType === "WORD_SEARCH") {
      const unresolved = this.unresolvedWordPlacements();
      const placement = unresolved[Math.floor(Math.random() * unresolved.length)];
      if (!placement) return null;
      const correct = Math.random() <= accuracy;
      return {
        requestId: `generic-bot-${randomUUID()}`,
        row: placement.startRow,
        col: placement.startCol,
        val: { word: correct ? placement.word : `${placement.word}X`, endRow: placement.endRow, endCol: placement.endCol }
      };
    }
    const candidates: Array<{ row: number; col: number }> = [];
    for (let row = 0; row < this.board.length; row += 1) {
      for (let col = 0; col < this.board[row]!.length; col += 1) {
        const cell = this.board[row]![col]!;
        if (cell.ownerId === null && !cell.isBlocked) {
          candidates.push({ row, col });
        }
      }
    }
    if (candidates.length === 0) return null;
    const correct = Math.random() <= accuracy;
    let suitable = this.gameType === "MINESWEEPER"
      ? candidates.filter(({ row, col }) => (this.answers[row]![col] === true) !== correct)
      : candidates;
    if (["HITORI", "BRIDGES"].includes(this.gameType) && correct) {
      suitable = candidates.filter(({ row, col }) => this.answers[row]![col] === true);
    }
    if (this.gameType === "DOTS_AND_BOXES" && correct) {
      const edgeCount = ({ row, col }: { row: number; col: number }) =>
        ["top", "right", "bottom", "left"].filter((side) => this.board[row]![col]!.meta[side] === true).length;
      const closing = candidates.filter((candidate) => edgeCount(candidate) === 3);
      const safe = candidates.filter((candidate) => edgeCount(candidate) <= 1);
      suitable = closing.length > 0 ? closing : safe.length > 0 ? safe : candidates;
    }
    const pool = suitable.length > 0 ? suitable : candidates;
    const target = pool[Math.floor(Math.random() * pool.length)]!;
    return {
      requestId: `generic-bot-${randomUUID()}`,
      row: target.row,
      col: target.col,
      val: this.botValue(target.row, target.col, correct)
    };
  }

  revealMove(row: number, col: number): GenericMove | null {
    if (this.gameType === "CAPITAL_ARENA") return null;
    if (!this.board[row]?.[col] || this.board[row]![col]!.ownerId !== null) return null;
    if (this.gameType === "NEXUS_ZERO") {
      const [targetRow, targetCol] = String(this.answers[row]![col]).split(":").map(Number);
      if (!this.board[targetRow!]?.[targetCol!] || this.board[targetRow!]![targetCol!]!.ownerId !== null) return null;
      return {
        requestId: `nexus-reveal-${randomUUID()}`,
        row,
        col,
        val: { targetRow, targetCol },
      };
    }
    let target = { row, col };
    if (this.gameType === "MINESWEEPER" && this.answers[row]![col] === true) {
      const safe = this.findFirstSafeCell();
      if (!safe) return null;
      target = safe;
    } else if (this.gameType === "WORD_SEARCH") {
      const placement = this.unresolvedWordPlacements()[0];
      if (!placement) return null;
      return {
        requestId: `generic-reveal-${randomUUID()}`,
        row: placement.startRow,
        col: placement.startCol,
        val: { word: placement.word, endRow: placement.endRow, endCol: placement.endCol }
      };
    } else if (["HITORI", "NURIKABE", "BRIDGES"].includes(this.gameType) && this.answers[row]![col] !== true) {
      const unresolved = this.findFirstTrueCell();
      if (!unresolved) return null;
      target = unresolved;
    }
    return {
      requestId: `generic-reveal-${randomUUID()}`,
      row: target.row,
      col: target.col,
      val: this.botValue(target.row, target.col, true)
    };
  }

  private applySpecificMove(
    playerId: string,
    move: GenericMove,
    cell: GenericCell,
    game: ArenaGame,
    now: number,
  ): { correct: boolean; hitMine?: boolean; points?: number; extraTurn?: boolean; neutral?: boolean; message?: string } {
    if (this.gameType === "CROSS_LETTERS") return this.applyCrossLettersMove(playerId, move);
    if (this.gameType === "SECRET_CODE") return this.applySecretCodeMove(playerId, move, cell);
    if (this.gameType === "CAPITAL_ARENA") return this.applyCapitalMove(playerId, move, game);
    if (this.gameType === "MERGE_2048") return this.applyMerge2048Move(playerId, move);
    if (this.gameType === "TOWER_DEFENSE") return this.applyTowerDefenseMove(playerId, move, game);
    if (this.gameType === "REACTOR_CHAIN") return this.applyReactorChainMove(playerId, move);

    if (this.gameType === "MEMORY_NEON") {
      // La pareja fallida permanece visible hasta el siguiente intento para que
      // el jugador pueda memorizarla; el próximo toque la limpia sin temporizadores.
      this.board.flat().forEach((candidate) => {
        if (candidate.meta.mismatch === true) {
          candidate.value = null;
          candidate.isRevealed = false;
          candidate.meta.mismatch = false;
        }
      });
      if (cell.ownerId !== null || cell.value !== null) {
        return { correct: false, neutral: true, message: "Carta no disponible" };
      }
      const answer = this.answers[move.row]![move.col];
      const first = this.memoryFirstPicks.get(playerId);
      cell.value = answer ?? null;
      cell.isRevealed = true;
      if (!first) {
        this.memoryFirstPicks.set(playerId, { row: move.row, col: move.col });
        return { correct: true, points: 0 };
      }
      const firstCell = this.board[first.row]![first.col]!;
      this.memoryFirstPicks.delete(playerId);
      if (this.answers[first.row]![first.col] === answer) {
        firstCell.ownerId = playerId;
        cell.ownerId = playerId;
        return { correct: true, points: 30 };
      }
      firstCell.meta.mismatch = true;
      cell.meta.mismatch = true;
      return { correct: true, points: 0 };
    }

    if (this.gameType === "HANGMAN") {
      if ((this.hangmanErrors.get(playerId) ?? 0) >= 6) {
        return { correct: false, neutral: true, points: 0, message: "Ya no te quedan vidas en esta ronda" };
      }
      const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : null;
      const action = String(payload?.action ?? "LETTER").toUpperCase();
      if (action === "REVEAL") {
        if (this.hangmanRevealUsed.has(playerId)) return { correct: false, neutral: true, message: "Revelación Clara ya fue utilizada" };
        const letter = [...this.hiddenWord].find((candidate) => !this.hangmanGuesses.has(candidate));
        if (!letter) return { correct: false, neutral: true, message: "No quedan letras por revelar" };
        this.hangmanRevealUsed.add(playerId);
        this.hangmanGuesses.add(letter);
        [...this.hiddenWord].forEach((answer, col) => {
          if (answer === letter) {
            this.board[0]![col]!.value = letter;
            this.board[0]![col]!.ownerId = playerId;
            this.board[0]![col]!.isRevealed = true;
          }
        });
        return { correct: true, points: 0, extraTurn: true };
      }
      if (action === "DISCARD") {
        if (this.hangmanDiscardUsed.has(playerId)) return { correct: false, neutral: true, message: "Descarte Táctico ya fue utilizado" };
        const alphabet = [..."ABCDEFGHIJKLMNÑOPQRSTUVWXYZ"];
        const discarded = alphabet.filter((candidate) =>
          !this.hiddenWord.includes(candidate) && !this.hangmanGuesses.has(candidate)
        ).sort(() => Math.random() - .5).slice(0, 3);
        this.hangmanDiscardUsed.add(playerId);
        this.hangmanDiscarded.set(playerId, discarded);
        return { correct: true, points: 0, extraTurn: true };
      }
      const letter = String(payload?.letter ?? move.val ?? "").trim().toUpperCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
      if (!/^[A-ZÑ]$/.test(letter)) {
        return { correct: false, neutral: true, message: "Ingresa exactamente una letra" };
      }
      if (this.hangmanGuesses.has(letter)) {
        return { correct: false, neutral: true, message: `La letra ${letter} ya fue utilizada` };
      }
      this.hangmanGuesses.add(letter);
      let hits = 0;
      [...this.hiddenWord].forEach((answer, col) => {
        if (answer === letter) {
          this.board[0]![col]!.value = letter;
          this.board[0]![col]!.ownerId = playerId;
          this.board[0]![col]!.isRevealed = true;
          hits += 1;
        }
      });
      if (hits === 0) {
        const nextErrors = (this.hangmanErrors.get(playerId) ?? 0) + 1;
        if (nextErrors >= 6 && !this.hangmanLastBreathUsed.has(playerId)) {
          this.hangmanLastBreathUsed.add(playerId);
          this.hangmanErrors.set(playerId, 5);
        } else this.hangmanErrors.set(playerId, nextErrors);
        return { correct: true, points: 0 };
      }
      return { correct: true, points: hits * 12, extraTurn: true };
    }

    if (this.gameType === "TIC_TAC_TOE") {
      const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : {};
      const action = String(payload.action ?? move.val ?? "MARK").toUpperCase();
      const playerIndex = Math.max(0, this.turnOrder.indexOf(playerId));
      const mark = playerIndex % 2 === 0 ? "X" : "O";
      if (this.meta.variant === "CLASSIC") {
        if (cell.value !== null) return { correct: false, neutral: true, message: "Casilla ocupada" };
        cell.value = mark; cell.ownerId = playerId; cell.isRevealed = true;
        const winner = this.classicTicWinner();
        if (winner) { this.meta.winnerMark = winner; this.meta.winnerPlayerId = playerId; }
        return { correct: true, points: winner ? 100 : 10 };
      }
      const usedPowers = (this.meta.ticUsedPowers ?? {}) as Record<string, string[]>;
      const mine = new Set(usedPowers[playerId] ?? []);
      if (["PUSH", "SHIELD", "BOMB"].includes(action)) {
        if (mine.has(action)) return { correct: false, neutral: true, message: "Poder ya utilizado" };
        if (action === "SHIELD") {
          if (cell.ownerId !== playerId || cell.value !== mark) return { correct: false };
          cell.meta.shielded = true;
        } else if (action === "BOMB") {
          if (cell.value === null || cell.meta.shielded === true) return { correct: false };
          cell.value = null; cell.ownerId = null; cell.isRevealed = false;
        } else {
          if (cell.value === null || cell.ownerId === playerId || cell.meta.shielded === true) return { correct: false };
          const destination = [[-1, 0], [0, 1], [1, 0], [0, -1]]
            .map(([dy, dx]) => this.board[move.row + dy!]?.[move.col + dx!])
            .find((candidate) => candidate && candidate.value === null && !candidate.isBlocked);
          if (!destination) return { correct: false, neutral: true, message: "No hay casilla adyacente libre" };
          destination.value = cell.value; destination.ownerId = cell.ownerId; destination.isRevealed = true;
          destination.meta.placedAt = cell.meta.placedAt ?? null;
          cell.value = null; cell.ownerId = null; cell.isRevealed = false;
        }
        mine.add(action); usedPowers[playerId] = [...mine]; this.meta.ticUsedPowers = usedPowers;
        return { correct: true, points: 5 };
      }
      if (cell.value !== null) return { correct: false };
      let forced = this.meta.forcedMini as { row: number; col: number } | null;
      const miniWinners = (this.meta.miniWinners ?? {}) as Record<string, string>;
      const selectedMiniKey = `${Math.floor(move.row / 3)}:${Math.floor(move.col / 3)}`;
      if (miniWinners[selectedMiniKey]) return { correct: false, neutral: true, message: "Ese mini-tablero ya fue conquistado" };
      if (forced) {
        valLoop: {
          const region = this.board.slice(forced.row * 3, forced.row * 3 + 3).flatMap((row) => row.slice(forced!.col * 3, forced!.col * 3 + 3));
          if (!miniWinners[`${forced.row}:${forced.col}`] && region.some((candidate) => candidate.value === null)) break valLoop;
          forced = null; this.meta.forcedMini = null;
        }
      }
      if (forced && !miniWinners[`${forced.row}:${forced.col}`]
        && (Math.floor(move.row / 3) !== forced.row || Math.floor(move.col / 3) !== forced.col)) {
        return { correct: false, neutral: true, message: "Debes jugar en el mini-tablero resaltado" };
      }
      cell.value = mark;
      cell.ownerId = playerId;
      cell.isRevealed = true;
      cell.meta.placedAt = Date.now();
      // Gato infinito: al poner la cuarta marca desaparece la más antigua no protegida.
      const regionStartRow = Math.floor(move.row / 3) * 3;
      const regionStartCol = Math.floor(move.col / 3) * 3;
      const owned = this.board.slice(regionStartRow, regionStartRow + 3)
        .flatMap((row) => row.slice(regionStartCol, regionStartCol + 3))
        .filter((candidate) => candidate.ownerId === playerId && candidate.value === mark)
        .sort((a, b) => Number(a.meta.placedAt ?? 0) - Number(b.meta.placedAt ?? 0));
      if (owned.length > 3) {
        const oldest = owned.find((candidate) => candidate.meta.shielded !== true) ?? owned[0]!;
        oldest.value = null; oldest.ownerId = null; oldest.isRevealed = false; oldest.meta.placedAt = null;
      }
      const miniRow = Math.floor(move.row / 3); const miniCol = Math.floor(move.col / 3);
      const miniWinner = this.ticRegionWinner(miniRow * 3, miniCol * 3);
      if (miniWinner) {
        miniWinners[`${miniRow}:${miniCol}`] = miniWinner;
        const miniOwners = (this.meta.miniOwners ?? {}) as Record<string, string>;
        miniOwners[`${miniRow}:${miniCol}`] = playerId;
        this.meta.miniOwners = miniOwners;
      }
      else {
        const currentRegionFull = this.board.slice(miniRow * 3, miniRow * 3 + 3)
          .flatMap((row) => row.slice(miniCol * 3, miniCol * 3 + 3)).every((candidate) => candidate.value !== null);
        if (currentRegionFull) miniWinners[`${miniRow}:${miniCol}`] = "DRAW";
      }
      this.meta.miniWinners = miniWinners;
      // Variante solicitada: ambos jugadores continúan dentro de la zona actual
      // hasta que alguien la conquista; entonces el siguiente elige zona libre.
      const nextMini = { row: miniRow, col: miniCol };
      const nextKey = `${nextMini.row}:${nextMini.col}`;
      const nextFull = this.board.slice(nextMini.row * 3, nextMini.row * 3 + 3)
        .flatMap((row) => row.slice(nextMini.col * 3, nextMini.col * 3 + 3)).every((candidate) => candidate.value !== null);
      this.meta.forcedMini = miniWinners[nextKey] || nextFull ? null : nextMini;
      const winner = this.ticTacToeWinner();
      if (winner) {
        this.meta.winnerMark = winner;
        if (winner !== "DRAW") this.meta.winnerPlayerId = playerId;
        this.completed = true;
      }
      return { correct: true, points: winner ? 100 : 10 };
    }

    if (this.gameType === "CHECKERS") return this.applyCheckersMove(playerId, move, cell);
    if (this.gameType === "CHESS_TACTICS") return this.applyChessTacticsMove(playerId, move, cell);

    if (this.gameType === "ARROWS_ESCAPE") {
      if ((this.arrowBlockedUntil.get(playerId) ?? 0) > now) {
        return { correct: false, neutral: true, message: "Tablero bloqueado por una pieza cronómetro" };
      }
      const removed = this.arrowRemoved.get(playerId) ?? new Set<string>();
      const shapeId = String(cell.meta.shapeId ?? `${move.row}:${move.col}`);
      const members = this.arrowShapeMembers(shapeId);
      if (!members.length || members.every(({ row, col }) => removed.has(`${row}:${col}`))) return { correct: false };
      const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : null;
      const action = String(payload?.action ?? move.val ?? "ESCAPE").toUpperCase();
      if (action === "ROTATE") {
        const used = this.arrowRotateUses.get(playerId) ?? 0;
        if (used >= Number(this.meta.rotatePowerUses ?? 2)) return { correct: false, neutral: true, message: "No quedan rotaciones" };
        const shape = (this.meta.shapes as Array<Record<string, unknown>>).find((candidate) => candidate.id === shapeId);
        if (!shape) return { correct: false, neutral: true };
        const next = ({
          UP: "RIGHT", RIGHT: "DOWN", DOWN: "LEFT", LEFT: "UP",
        } as Record<string, string>)[String(shape.direction)] ?? "UP";
        shape.direction = next;
        shape.exitVector = next === "UP" ? { x: 0, y: -1 }
          : next === "RIGHT" ? { x: 1, y: 0 }
            : next === "DOWN" ? { x: 0, y: 1 } : { x: -1, y: 0 };
        members.forEach(({ cell: member }) => { member.value = next; member.meta.arrow = next; });
        this.arrowRotateUses.set(playerId, used + 1);
        return { correct: true, points: 0 };
      }
      if (action === "MISSILE") {
        const used = this.arrowMissileUses.get(playerId) ?? 0;
        if (used >= Number(this.meta.missilePowerUses ?? 1)) return { correct: false, neutral: true, message: "Misil ya utilizado" };
        members.forEach(({ row, col }) => removed.add(`${row}:${col}`));
        this.arrowMissileUses.set(playerId, used + 1);
        this.arrowRemoved.set(playerId, removed);
        return { correct: true, points: 15 * members.length };
      }
      const shape = (this.meta.shapes as Array<Record<string, unknown>>).find((candidate) => candidate.id === shapeId);
      const triggered = this.arrowTimerTriggered.get(playerId) ?? new Set<string>();
      this.arrowTimerTriggered.set(playerId, triggered);
      const expiredTimers = (this.meta.shapes as Array<Record<string, unknown>>).filter((candidate) =>
        candidate.blockType === "TIMER"
        && !(candidate.memberKeys as string[]).every((key) => removed.has(key))
        && !triggered.has(String(candidate.id))
        && now - this.arrowTimerStartedAt >= Number(candidate.timerSeconds ?? 10) * 1_000
      );
      if (expiredTimers.length) {
        expiredTimers.forEach((candidate) => triggered.add(String(candidate.id)));
        this.arrowBlockedUntil.set(playerId, now + 3_000);
        return { correct: true, points: 0, message: "¡Cronómetro agotado! Bloqueo de 3 segundos" };
      }
      const primary = String(shape?.direction ?? cell.meta.arrow);
      const opposite = ({
        UP: "DOWN", DOWN: "UP", LEFT: "RIGHT", RIGHT: "LEFT",
        FRONT: "BACK", BACK: "FRONT",
      } as Record<string, string>)[primary]!;
      const canEscape = this.canArrowShapeEscape(shapeId, removed, primary)
        || (shape?.blockType === "BIDIRECTIONAL" && this.canArrowShapeEscape(shapeId, removed, opposite));
      if (!canEscape) {
        this.arrowFailedTaps.set(playerId, (this.arrowFailedTaps.get(playerId) ?? 0) + 1);
        return { correct: true, points: 0 };
      }
      members.forEach(({ row, col }) => removed.add(`${row}:${col}`));
      if (shape?.blockType === "BOMB") {
        const sourcePoints = shape.points as Array<{ x: number; y: number }> | undefined;
        const sourceHead = sourcePoints?.[sourcePoints.length - 1];
        const sourceX = Number(sourceHead?.x ?? shape.x ?? 0); const sourceY = Number(sourceHead?.y ?? shape.y ?? 0);
        const candidates = (this.meta.shapes as Array<Record<string, unknown>>)
          .filter((candidate) => candidate.id !== shapeId)
          .filter((candidate) => !(candidate.memberKeys as string[]).every((key) => removed.has(key)))
          .sort((a, b) => {
            const aPoints = a.points as Array<{ x: number; y: number }> | undefined;
            const bPoints = b.points as Array<{ x: number; y: number }> | undefined;
            const aHead = aPoints?.[aPoints.length - 1]; const bHead = bPoints?.[bPoints.length - 1];
            return Math.hypot(Number(aHead?.x ?? a.x ?? 0) - sourceX, Number(aHead?.y ?? a.y ?? 0) - sourceY)
              - Math.hypot(Number(bHead?.x ?? b.x ?? 0) - sourceX, Number(bHead?.y ?? b.y ?? 0) - sourceY);
          })
          .slice(0, 2);
        candidates.forEach((candidate) => (candidate.memberKeys as string[]).forEach((key) => removed.add(key)));
      }
      this.arrowRemoved.set(playerId, removed);
      const combo = now - (this.arrowLastSuccessAt.get(playerId) ?? 0) <= 1_800
        ? Math.min(8, (this.arrowCombo.get(playerId) ?? 1) + 1)
        : 1;
      this.arrowCombo.set(playerId, combo);
      this.arrowLastSuccessAt.set(playerId, now);
      return { correct: true, points: 10 * members.length * combo };
    }

    if (this.gameType === "HITORI" && typeof move.val === "object" && move.val !== null
      && String((move.val as Record<string, unknown>).action).toUpperCase() === "HINT") {
      const forced = this.board.flatMap((row, rowIndex) => row.map((candidate, colIndex) => ({ candidate, row: rowIndex, col: colIndex })))
        .find(({ candidate, row, col }) => this.answers[row]![col] === true && candidate.ownerId === null);
      const safe = forced ?? this.board.flatMap((row, rowIndex) => row.map((candidate, colIndex) => ({ candidate, row: rowIndex, col: colIndex })))
        .find(({ candidate, row, col }) => this.answers[row]![col] !== true && candidate.meta.hintColor == null);
      if (!safe) return { correct: false, neutral: true, message: "No quedan deducciones pendientes" };
      safe.candidate.meta.hintColor = forced ? "RED" : "GREEN";
      safe.candidate.meta.hintExpiresAt = now + 4_000;
      this.meta.lastHint = { row: safe.row, col: safe.col, kind: forced ? "PAINT" : "KEEP", at: now };
      return { correct: true, points: 0 };
    }

    if (this.gameType === "NURIKABE") {
      const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : null;
      if (String(payload?.action ?? "").toUpperCase() === "SONAR") {
        const used = this.nurikabeSonarUses.get(playerId) ?? 0;
        if (used >= 3) return { correct: false, neutral: true, message: "No quedan pulsos de Sonar" };
        for (let row = Math.max(0, move.row - 1); row <= Math.min(this.board.length - 1, move.row + 1); row += 1) {
          for (let col = Math.max(0, move.col - 1); col <= Math.min(this.board[row]!.length - 1, move.col + 1); col += 1) {
            this.board[row]![col]!.meta.sonarState = this.answers[row]![col] === true ? "RIVER" : "ISLAND";
            this.board[row]![col]!.meta.sonarUntil = now + 5_000;
          }
        }
        this.nurikabeSonarUses.set(playerId, used + 1);
        return { correct: true, points: 0 };
      }
      const action = String(move.val ?? "").toUpperCase();
      if (!['RIVER', 'ISLAND', 'CLEAR'].includes(action) || cell.meta.islandClue === true) return { correct: false };
      if (action === "CLEAR") {
        cell.value = null; cell.ownerId = null; cell.isRevealed = false;
        return { correct: true, points: 0 };
      }
      const shouldBeRiver = this.answers[move.row]![move.col] === true;
      cell.value = action;
      cell.ownerId = playerId;
      cell.isRevealed = true;
      return { correct: true, points: (action === "RIVER") === shouldBeRiver ? 10 : 0 };
    }
    if (this.gameType === "MINESWEEPER") {
      const mine = this.answers[move.row]![move.col] === true;
      if (mine) {
        cell.value = "MINE";
        cell.isRevealed = true;
        cell.isBlocked = true;
        return { correct: false, hitMine: true };
      }
      cell.value = this.adjacentMineCount(move.row, move.col);
      cell.isRevealed = true;
      cell.ownerId = playerId;
      return { correct: true };
    }

    if (this.gameType === "WORD_SEARCH") {
      const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : null;
      const wordValue = payload?.word ?? move.val;
      const word = String(wordValue ?? "").toUpperCase();
      const placement = (this.meta.placements as WordPlacement[]).find((candidate) =>
        candidate.word === word && candidate.startRow === move.row && candidate.startCol === move.col &&
        (payload?.endRow == null || Number(payload.endRow) === candidate.endRow) &&
        (payload?.endCol == null || Number(payload.endCol) === candidate.endCol)
      );
      if (!placement) return { correct: false };
      const found = this.meta.foundWords as string[];
      if (found.includes(word)) return { correct: false };
      for (let offset = 0; offset < word.length; offset += 1) {
        this.board[placement.startRow + placement.rowStep * offset]![placement.startCol + placement.colStep * offset]!.ownerId = playerId;
      }
      if (!found.includes(word)) found.push(word);
      return { correct: true, points: word.length * 10 };
    }

    if (this.gameType === "DOTS_AND_BOXES") {
      const side = String(move.val ?? "").toLowerCase();
      if (!["top", "right", "bottom", "left"].includes(side) || cell.meta[side] === true) return { correct: false };
      cell.meta[side] = true;
      cell.meta[`${side}OwnerId`] = playerId;
      const neighbour = this.mirrorDotsEdge(move.row, move.col, side);
      if (neighbour) neighbour.meta[`${oppositeSide(side)}OwnerId`] = playerId;
      const closed = ["top", "right", "bottom", "left"].every((edge) => cell.meta[edge] === true);
      if (closed) { cell.ownerId = playerId; cell.isRevealed = true; }
      const neighbourClosed = neighbour !== null && ["top", "right", "bottom", "left"].every((edge) => neighbour.meta[edge] === true);
      if (neighbourClosed && neighbour) { neighbour.ownerId = playerId; neighbour.isRevealed = true; }
      const completedBoxes = Number(closed) + Number(neighbourClosed);
      return {
        correct: true,
        points: completedBoxes > 0 ? completedBoxes * 50 : 5,
        // Regla clásica: cerrar una o dos cajas conserva el turno.
        extraTurn: completedBoxes > 0,
      };
    }

    if (this.gameType === "NEXUS_ZERO") return this.applyNexusSwipe(playerId, move);

    const expected = this.answers[move.row]![move.col]!;
    const normalized = normalizeValue(move.val, expected);
    if (normalized !== expected) return { correct: false };
    cell.value = this.gameType === "HITORI" ? this.board[move.row]![move.col]!.value : expected;
    cell.isBlocked = this.gameType === "HITORI";
    cell.isRevealed = true;
    cell.ownerId = playerId;
    return { correct: true };
  }

  private ticTacToeWinner(): string | null {
    if (this.board.length === 9) {
      const miniWinners = (this.meta.miniWinners ?? {}) as Record<string, string>;
      if (Object.keys(miniWinners).length < 9) return null;
      const x = Object.values(miniWinners).filter((value) => value === "X").length;
      const o = Object.values(miniWinners).filter((value) => value === "O").length;
      if (x !== o) return x > o ? "X" : "O";
      return "DRAW";
    }
    const lines = [
      [[0, 0], [0, 1], [0, 2]], [[1, 0], [1, 1], [1, 2]], [[2, 0], [2, 1], [2, 2]],
      [[0, 0], [1, 0], [2, 0]], [[0, 1], [1, 1], [2, 1]], [[0, 2], [1, 2], [2, 2]],
      [[0, 0], [1, 1], [2, 2]], [[0, 2], [1, 1], [2, 0]],
    ];
    for (const line of lines) {
      const values = line.map(([row, col]) => String(this.board[row!]![col!]!.value ?? ""));
      if (values[0] && values.every((value) => value === values[0])) return values[0]!;
    }
    return null;
  }

  private classicTicWinner(): string | null {
    if (this.board.length !== 3) return null;
    const lines = [
      [[0,0],[0,1],[0,2]], [[1,0],[1,1],[1,2]], [[2,0],[2,1],[2,2]],
      [[0,0],[1,0],[2,0]], [[0,1],[1,1],[2,1]], [[0,2],[1,2],[2,2]],
      [[0,0],[1,1],[2,2]], [[0,2],[1,1],[2,0]],
    ];
    for (const line of lines) {
      const values = line.map(([row, col]) => this.board[row!]![col!]!.value);
      if (values[0] != null && values.every((value) => value === values[0])) return String(values[0]);
    }
    return null;
  }

  private ticRegionWinner(startRow: number, startCol: number): string | null {
    const region = Array.from({ length: 3 }, (_, row) => Array.from({ length: 3 }, (_, col) =>
      String(this.board[startRow + row]![startCol + col]!.value ?? "")
    ));
    const lines = [
      [[0, 0], [0, 1], [0, 2]], [[1, 0], [1, 1], [1, 2]], [[2, 0], [2, 1], [2, 2]],
      [[0, 0], [1, 0], [2, 0]], [[0, 1], [1, 1], [2, 1]], [[0, 2], [1, 2], [2, 2]],
      [[0, 0], [1, 1], [2, 2]], [[0, 2], [1, 1], [2, 0]],
    ];
    for (const line of lines) {
      const values = line.map(([row, col]) => region[row!]![col!]!);
      if (values[0] && values.every((value) => value === values[0])) return values[0]!;
    }
    return null;
  }

  private applyMerge2048Move(
    playerId: string,
    move: GenericMove,
  ): { correct: boolean; points?: number; neutral?: boolean; message?: string } {
    const direction = String(move.val ?? "").toUpperCase();
    if (!["UP", "RIGHT", "DOWN", "LEFT"].includes(direction)) {
      return { correct: false, neutral: true, message: "Dirección inválida" };
    }
    const previous = this.board.map((row) => row.map((cell) => Number(cell.value ?? 0)));
    const next = previous.map((row) => [...row]);
    let points = 0;
    const slide = (line: number[]): number[] => {
      const compact = line.filter((value) => value > 0);
      const result: number[] = [];
      for (let index = 0; index < compact.length; index += 1) {
        if (compact[index] === compact[index + 1]) {
          const merged = compact[index]! * 2;
          result.push(merged);
          points += merged;
          index += 1;
        } else result.push(compact[index]!);
      }
      while (result.length < 4) result.push(0);
      return result;
    };
    if (direction === "LEFT" || direction === "RIGHT") {
      for (let row = 0; row < 4; row += 1) {
        const source = direction === "RIGHT" ? [...previous[row]!].reverse() : previous[row]!;
        const result = slide(source);
        next[row] = direction === "RIGHT" ? result.reverse() : result;
      }
    } else {
      for (let col = 0; col < 4; col += 1) {
        const source = Array.from({ length: 4 }, (_, row) => previous[row]![col]!);
        if (direction === "DOWN") source.reverse();
        const result = slide(source);
        if (direction === "DOWN") result.reverse();
        result.forEach((value, row) => { next[row]![col] = value; });
      }
    }
    if (next.every((row, y) => row.every((value, x) => value === previous[y]![x]))) {
      return { correct: false, neutral: true, message: "Ese deslizamiento no mueve fichas" };
    }
    this.board.forEach((row, y) => row.forEach((cell, x) => {
      const value = next[y]![x]!;
      cell.value = value === 0 ? null : value;
      cell.isRevealed = value !== 0;
      cell.ownerId = value !== 0 && value !== previous[y]![x] ? playerId : null;
    }));
    const empty = this.board.flatMap((row, y) => row.map((cell, x) => ({ cell, y, x })))
      .filter(({ cell }) => cell.value === null);
    const spawned = empty[Math.floor(Math.random() * empty.length)];
    if (spawned) {
      spawned.cell.value = Math.random() < .9 ? 2 : 4;
      spawned.cell.isRevealed = true;
      spawned.cell.ownerId = null;
    }
    this.meta.highestTile = Math.max(...this.board.flat().map((cell) => Number(cell.value ?? 0)));
    return { correct: true, points: Math.max(2, points) };
  }

  private applyNexusSwipe(
    playerId: string,
    move: GenericMove,
  ): { correct: boolean; points?: number; neutral?: boolean; message?: string } {
    const direction = String(move.val ?? "").toUpperCase();
    if (!["UP", "RIGHT", "DOWN", "LEFT"].includes(direction)) {
      return { correct: false, neutral: true, message: "Desliza en una dirección válida" };
    }
    const size = this.board.length;
    const previous = this.board.map((row) => row.map((cell) => cell.value == null ? null : Number.parseInt(String(cell.value), 10)));
    const next = matrixOf<number | null>(size, size, null);
    let nexuses = 0;
    const nexusMoves = Number(this.meta.nexusMoves ?? 0);
    const maxNexusesThisGesture = nexusMoves === 0 ? 1 : 2;
    const processLine = (values: Array<number | null>): Array<number | null> => {
      const compact = values.filter((value): value is number => Number.isInteger(value));
      const output: number[] = [];
      for (const value of compact) {
        const last = output.at(-1);
        // Como máximo se resuelven dos nexos por gesto. Así un tablero lleno
        // no desaparece casi por completo en el primer swipe y conserva la
        // lectura táctica de las cargas restantes.
        if (nexuses < maxNexusesThisGesture && last !== undefined && last + value === 0) {
          output.pop();
          nexuses += 1;
        } else output.push(value);
      }
      while (output.length < size) output.push(Number.NaN);
      return output.map((value) => Number.isNaN(value) ? null : value);
    };
    for (let line = 0; line < size; line += 1) {
      const horizontal = direction === "LEFT" || direction === "RIGHT";
      const source = Array.from({ length: size }, (_, index) =>
        horizontal ? previous[line]![index]! : previous[index]![line]!
      );
      if (direction === "RIGHT" || direction === "DOWN") source.reverse();
      const result = processLine(source);
      if (direction === "RIGHT" || direction === "DOWN") result.reverse();
      result.forEach((value, index) => {
        if (horizontal) next[line]![index] = value;
        else next[index]![line] = value;
      });
    }
    if (next.every((row, y) => row.every((value, x) => value === previous[y]![x]))) {
      return { correct: false, neutral: true, message: "Las fichas ya están bloqueadas en esa dirección" };
    }
    this.board.forEach((row, y) => row.forEach((target, x) => {
      target.value = next[y]![x] ?? null;
      target.isRevealed = target.value !== null;
      target.ownerId = null;
      target.meta = target.value === null ? {} : { charge: true };
    }));
    this.meta.lastNexus = nexuses > 0 ? { playerId, count: nexuses, at: Date.now() } : null;
    this.meta.nexusMoves = nexusMoves + 1;
    this.meta.nexusesCreated = Number(this.meta.nexusesCreated ?? 0) + nexuses;
    const boardIsEmpty = this.board.flat().every((cell) => cell.value === null);
    const round = Number(this.meta.nexusRound ?? 1);
    const targetRounds = Number(this.meta.nexusTargetRounds ?? 3);
    if (boardIsEmpty && round < targetRounds) {
      this.meta.nexusRound = round + 1;
      this.spawnNexusWave(Math.max(2, Math.floor(size * .34)));
      return {
        correct: true,
        points: nexuses * 25 + 40,
        message: `Nexo ${round} superado. Nueva onda ${round + 1}/${targetRounds}`,
      };
    }
    return { correct: true, points: nexuses * 25 };
  }

  private spawnNexusWave(pairCount: number): void {
    this.board.forEach((row) => row.forEach((cell) => {
      cell.value = null; cell.isRevealed = false; cell.ownerId = null; cell.meta = {};
    }));
    // Cada onda mantiene una solución garantizada: cada pareja nace en una
    // misma línea con huecos libres, aunque su orden/signo siga variando.
    const used = new Set<string>();
    for (let pair = 0; pair < Math.min(pairCount, Math.floor(this.board.length * this.board.length / 2)); pair += 1) {
      const value = 1 + Math.floor(Math.random() * 9);
      const row = pair % this.board.length;
      const columns = Array.from({ length: this.board.length }, (_, col) => col)
        .filter((col) => !used.has(`${row}:${col}`));
      for (let index = columns.length - 1; index > 0; index -= 1) {
        const target = Math.floor(Math.random() * (index + 1));
        [columns[index], columns[target]] = [columns[target]!, columns[index]!];
      }
      if (columns.length < 2) continue;
      const first = { row, col: columns[0]! };
      const second = { row, col: columns[1]! };
      used.add(`${row}:${first.col}`); used.add(`${row}:${second.col}`);
      const sign = Math.random() < .5 ? 1 : -1;
      for (const [position, charge] of [[first, value * sign], [second, -value * sign]] as const) {
        const cell = this.board[position.row]![position.col]!;
        cell.value = charge;
        cell.isRevealed = true;
        cell.meta = { charge: true };
      }
    }
  }

  private syncTowerPlayers(game: ArenaGame): void {
    const startingCredits = Number(this.meta.startingCredits ?? 400);
    for (const player of game.snapshot().players) {
      if (!this.towerCredits.has(player.id)) this.towerCredits.set(player.id, startingCredits);
    }
  }

  /**
   * Simulación autoritativa de la oleada. El servidor conserva vida, recorrido
   * y blancos; Compose interpola estos datos y no decide impactos.
   */
  tickTowerDefense(game: ArenaGame, now = Date.now()): boolean {
    if (this.gameType !== "TOWER_DEFENSE" || !this.towerWaveActive) return false;
    const dt = this.towerLastTickAt > 0 ? Math.min(.2, Math.max(.01, (now - this.towerLastTickAt) / 1_000)) : .05;
    this.towerLastTickAt = now;
    const path = (this.meta.path as Array<{ row: number; col: number }> | undefined) ?? [];
    if (path.length < 2) return false;

    // Watchdog autoritativo: incluso si una tropa queda sin objetivo o recibe
    // un estado inconsistente, ninguna oleada puede bloquear la partida.
    if (this.towerWaveStartedAt > 0 && now - this.towerWaveStartedAt >= 75_000) {
      const stalled = this.towerEnemies.filter((enemy) => enemy.status === "WAITING" || enemy.status === "MOVING");
      stalled.forEach((enemy) => { enemy.status = "LEAKED"; });
      if (stalled.length) this.towerBaseHealth = Math.max(0, this.towerBaseHealth - 1);
    }

    for (const enemy of this.towerEnemies) {
      if (enemy.status === "WAITING" && now >= enemy.spawnAt) enemy.status = "MOVING";
      if (enemy.status !== "MOVING") continue;
      const slow = enemy.slowUntil > now ? .58 : 1;
      enemy.progress += enemy.speed * slow * dt;
      if (enemy.progress >= path.length - 1) {
        enemy.status = "LEAKED";
        this.towerBaseHealth = Math.max(0, this.towerBaseHealth - (enemy.kind === "GOLEM" ? 3 : enemy.kind === "BRUTE" ? 2 : 1));
      }
    }
    if (this.towerBaseHealth <= 0) {
      this.towerEnemies.forEach((enemy) => {
        if (enemy.status === "WAITING" || enemy.status === "MOVING") enemy.status = "LEAKED";
      });
    }

    const active = this.towerEnemies.filter((enemy) => enemy.status === "MOVING");
    this.board.forEach((row, towerRow) => row.forEach((tower, towerCol) => {
      const towerType = tower.meta.towerType?.toString();
      if (!towerType || active.length === 0) return;
      const level = Number(tower.meta.level ?? 1);
      const range = towerType === "SNIPER" ? 6.4 : towerType === "BLAST" ? 3.2 : towerType === "FROST" ? 3.7 : 3.4;
      const overclocked = Number(tower.meta.overclockUntil ?? 0) > now;
      const cooldown = Math.max(.14, ((towerType === "RAPID" ? .42 : towerType === "SNIPER" ? 1.25 : .78) - level * .06)
        * (overclocked ? .55 : 1));
      const key = `${towerRow}:${towerCol}`;
      if ((this.towerNextShotAt.get(key) ?? 0) > now) return;
      const candidates = active.map((enemy) => {
        const position = towerEnemyPosition(enemy.progress, path);
        return { enemy, distance: Math.hypot(position.row - towerRow, position.col - towerCol) };
      }).filter(({ distance }) => distance <= range)
        .sort((a, b) => b.enemy.progress - a.enemy.progress);
      const target = candidates[0]?.enemy;
      if (!target) return;
      const damage = (towerType === "SNIPER" ? 22 : towerType === "BLAST" ? 13 : towerType === "FROST" ? 7 : 9)
        * level * (overclocked ? 1.35 : 1);
      const hit = (enemy: TowerEnemy, multiplier = 1) => {
        if (enemy.status !== "MOVING") return;
        enemy.hp -= damage * multiplier;
        if (towerType === "FROST") enemy.slowUntil = now + 1_700;
        if (enemy.hp <= 0) {
          enemy.hp = 0;
          enemy.status = "DEFEATED";
          const owner = tower.ownerId ?? "team";
          this.towerWaveKills.set(owner, (this.towerWaveKills.get(owner) ?? 0) + 1);
        }
      };
      hit(target);
      if (towerType === "BLAST") {
        const targetPosition = towerEnemyPosition(target.progress, path);
        active.filter((enemy) => enemy.id !== target.id).forEach((enemy) => {
          const position = towerEnemyPosition(enemy.progress, path);
          if (Math.hypot(position.row - targetPosition.row, position.col - targetPosition.col) <= 1.15) hit(enemy, .55);
        });
      }
      this.towerProjectiles.push({
        id: randomUUID(), towerRow, towerCol, targetId: target.id,
        color: towerType === "FROST" ? "#22D3EE" : towerType === "BLAST" ? "#FB923C" : towerType === "SNIPER" ? "#C084FC" : "#60A5FA",
        damage,
        towerType,
        firedAt: now, arrivesAt: now + (towerType === "SNIPER" ? 120 : 240),
      });
      this.towerNextShotAt.set(key, now + cooldown * 1_000);
    }));
    this.towerProjectiles = this.towerProjectiles.filter((projectile) => projectile.arrivesAt + 220 > now);

    const pending = this.towerEnemies.some((enemy) => enemy.status === "WAITING" || enemy.status === "MOVING");
    if (!pending) {
      this.towerWaveActive = false;
      const defeated = this.towerEnemies.filter((enemy) => enemy.status === "DEFEATED").length;
      const leaks = this.towerEnemies.filter((enemy) => enemy.status === "LEAKED").length;
      const reward = defeated * (8 + Math.floor(this.towerWave / 4));
      const participants = [...this.towerCredits.keys()];
      const shared = participants.length ? Math.floor(reward / participants.length) : 0;
      participants.forEach((id) => this.towerCredits.set(id, (this.towerCredits.get(id) ?? 0) + shared));
      for (const [ownerId, kills] of this.towerWaveKills) {
        if (ownerId !== "team") game.applyGenericSuccess(ownerId, kills * 5, Math.min(25, kills * 2), now);
      }
      this.towerLastReport = {
        wave: this.towerWave, enemies: this.towerEnemies.length, defeated, leaks,
        modifier: this.towerWave >= 14 ? "RUNIC" : this.towerWave >= 12 ? "ARMORED" : this.towerWave >= 7 ? "STEALTH" : "NONE",
      };
      this.towerWaveKills.clear();
      if (this.towerBaseHealth <= 0 || this.towerWave >= Number(this.meta.maxWaves ?? 20)) this.completed = true;
    }
    this.revision += 1;
    return true;
  }

  private applyTowerDefenseMove(
    playerId: string,
    move: GenericMove,
    game: ArenaGame,
  ): { correct: boolean; points?: number; neutral?: boolean; message?: string } {
    this.syncTowerPlayers(game);
    const payload = typeof move.val === "object" && move.val !== null
      ? move.val as Record<string, unknown>
      : { action: move.val };
    const action = String(payload.action ?? "BUILD").toUpperCase();
    if (["EMP", "ORBITAL", "REPAIR", "OVERCLOCK"].includes(action)) {
      const costs: Record<string, number> = { EMP: 180, ORBITAL: 280, REPAIR: 220, OVERCLOCK: 200 };
      const cost = costs[action]!;
      const credits = this.towerCredits.get(playerId) ?? 0;
      if (credits < cost) return { correct: false, neutral: true, message: "Créditos insuficientes para la habilidad" };
      this.towerCredits.set(playerId, credits - cost);
      const now = Date.now();
      if (action === "EMP") {
        this.towerEnemies.filter((enemy) => enemy.status === "MOVING").forEach((enemy) => {
          enemy.hp = Math.max(0, enemy.hp - 15); enemy.slowUntil = now + 4_500;
          if (enemy.hp <= 0) enemy.status = "DEFEATED";
        });
        return { correct: true, points: 20, message: "Pulso EMP: tropas ralentizadas" };
      }
      if (action === "ORBITAL") {
        this.towerEnemies.filter((enemy) => enemy.status === "MOVING").forEach((enemy) => {
          enemy.hp = Math.max(0, enemy.hp - 80);
          if (enemy.hp <= 0) enemy.status = "DEFEATED";
        });
        return { correct: true, points: 35, message: "Bombardeo orbital desplegado" };
      }
      if (action === "REPAIR") {
        this.towerBaseHealth = Math.min(20, this.towerBaseHealth + 5);
        return { correct: true, points: 10, message: "Núcleo reparado +5" };
      }
      this.board.flat().forEach((tower) => {
        if (tower.ownerId === playerId && tower.meta.towerType != null) tower.meta.overclockUntil = now + 12_000;
      });
      return { correct: true, points: 15, message: "Torres sobrecargadas durante 12 segundos" };
    }
    if (action === "START_WAVE") {
      if (this.towerWaveActive) {
        return { correct: false, neutral: true, message: "La oleada actual todavía está en combate" };
      }
      if (this.towerWave >= Number(this.meta.maxWaves ?? 20)) {
        return { correct: false, neutral: true, message: "Las oleadas ya terminaron" };
      }
      this.towerWave += 1;
      const enemyCount = 5 + this.towerWave * 2;
      const layers = 1 + Math.floor(this.towerWave / 5);
      const now = Date.now();
      this.towerEnemies = Array.from({ length: enemyCount }, (_, index) => {
        const kind: TowerEnemy["kind"] = this.towerWave >= 12 && index % 7 === 0 ? "GOLEM"
          : this.towerWave >= 7 && index % 5 === 0 ? "PHANTOM"
            : index % 4 === 0 ? "BRUTE" : "SCOUT";
        const maxHp = (kind === "GOLEM" ? 110 : kind === "BRUTE" ? 58 : kind === "PHANTOM" ? 38 : 28) * layers;
        return {
          id: `w${this.towerWave}-e${index}-${randomUUID()}`,
          kind, hp: maxHp, maxHp, progress: 0,
          speed: kind === "PHANTOM" ? 1.65 : kind === "GOLEM" ? .82 : kind === "BRUTE" ? 1.02 : 1.32,
          spawnAt: now + index * 420, slowUntil: 0, status: "WAITING",
        };
      });
      this.towerWaveActive = true;
      this.towerLastTickAt = now;
      this.towerWaveStartedAt = now;
      this.towerProjectiles = [];
      this.towerWaveKills.clear();
      return {
        correct: true,
        points: 0,
        message: `Oleada ${this.towerWave}: ${enemyCount} tropas entrando en la arena`,
      };
    }
    if (cellIsTower(this.board[move.row]![move.col]!) && action === "UPGRADE") {
      const target = this.board[move.row]![move.col]!;
      if (target.ownerId !== playerId) return { correct: false, neutral: true, message: "Solo puedes mejorar tu torre" };
      const level = Number(target.meta.level ?? 1);
      if (level >= 3) return { correct: false, neutral: true, message: "Torre al nivel máximo" };
      const cost = 100 * level;
      const credits = this.towerCredits.get(playerId) ?? 0;
      if (credits < cost) return { correct: false, neutral: true, message: "Créditos insuficientes" };
      this.towerCredits.set(playerId, credits - cost);
      target.meta.level = level + 1;
      return { correct: true, points: 10, message: `Torre mejorada a nivel ${level + 1}` };
    }
    const towerType = String(payload.towerType ?? "RAPID").toUpperCase();
    const costs: Record<string, number> = { RAPID: 100, BLAST: 150, SNIPER: 180, FROST: 130 };
    const cost = costs[towerType];
    const target = this.board[move.row]![move.col]!;
    if (!cost || target.meta.buildable !== true || target.meta.towerType != null) {
      return { correct: false, neutral: true, message: "Selecciona un terreno libre" };
    }
    const credits = this.towerCredits.get(playerId) ?? 0;
    if (credits < cost) return { correct: false, neutral: true, message: "Créditos insuficientes" };
    this.towerCredits.set(playerId, credits - cost);
    target.meta.towerType = towerType;
    target.meta.level = 1;
    target.value = towerType;
    target.ownerId = playerId;
    target.isRevealed = true;
    return { correct: true, points: 10, message: `${towerType} construida` };
  }

  private applyReactorChainMove(
    playerId: string,
    move: GenericMove,
  ): { correct: boolean; points?: number; neutral?: boolean; message?: string } {
    const specialTarget = this.board[move.row]?.[move.col];
    const embeddedPower = String(specialTarget?.meta.special ?? "");
    if (specialTarget && embeddedPower) {
      const all = this.board.flatMap((row, r) => row.map((_cell, c) => ({ row: r, col: c })));
      if (embeddedPower === "RAINBOW") {
        const sourceColor = Number(specialTarget.meta.sourceColor ?? specialTarget.value);
        this.board.flat().forEach((cell) => {
          if (Number(cell.value) === sourceColor) { cell.value = 0; cell.meta = { reactorOrb: true, special: "WILD" }; }
        });
        this.meta.lastChain = { playerId, size: 0, color: sourceColor, cells: [], power: embeddedPower, at: Date.now() };
        return { correct: true, points: 40, message: "Esferas multicolor creadas" };
      }
      const cells = embeddedPower === "ROW" ? all.filter((point) => point.row === move.row)
        : embeddedPower === "COLUMN" ? all.filter((point) => point.col === move.col)
          : embeddedPower === "WILD" ? all.filter((point) => Math.abs(point.row - move.row) + Math.abs(point.col - move.col) <= 2)
            : all.filter((point) => Math.abs(point.row - move.row) <= 1 && Math.abs(point.col - move.col) <= 1);
      this.removeAndCollapseReactors(cells, playerId);
      const points = cells.length * 12;
      this.meta.removed = Number(this.meta.removed ?? 0) + cells.length;
      this.meta.reactorScore = Number(this.meta.reactorScore ?? 0) + points;
      this.meta.level = Math.min(100, 1 + Math.floor(Number(this.meta.reactorScore) / 600));
      this.meta.lastChain = { playerId, size: cells.length, color: specialTarget.value, cells, power: embeddedPower, at: Date.now() };
      if (!this.hasReactorMove()) { this.meta.noMoves = true; this.completed = true; }
      return { correct: true, points, message: `${embeddedPower}: ${cells.length} esferas` };
    }
    const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : {};
    const action = String(payload.action ?? move.val ?? "CHAIN").toUpperCase();
    const currentEnergy = this.reactorPowerEnergy.get(playerId) ?? 0;
    const costs: Record<string, number> = { HAMMER: 3, ROW_BLAST: 4, COLOR_WIPE: 5, SHUFFLE: 3 };
    if (action in costs) {
      const cost = costs[action]!;
      if (currentEnergy < cost) return { correct: false, neutral: true, message: "EnergÃ­a de reactor insuficiente" };
      this.reactorPowerEnergy.set(playerId, currentEnergy - cost);
      if (action === "SHUFFLE") {
        const values = this.board.flat().map((cell) => cell.value);
        for (let index = values.length - 1; index > 0; index -= 1) {
          const targetIndex = Math.floor(Math.random() * (index + 1));
          [values[index], values[targetIndex]] = [values[targetIndex]!, values[index]!];
        }
        this.board.flat().forEach((cell, index) => { cell.value = values[index]!; cell.meta = { reactorOrb: true }; });
        this.ensureReactorMove();
        this.meta.lastPower = { playerId, type: action, at: Date.now() };
        return { correct: true, points: 10, message: "Mezcla prismÃ¡tica" };
      }
      const selected = this.board[move.row]?.[move.col];
      if (!selected) return { correct: false, neutral: true, message: "Objetivo invÃ¡lido" };
      const removed = action === "ROW_BLAST"
        ? this.board[move.row]!.map((_cell, col) => ({ row: move.row, col }))
        : action === "COLOR_WIPE"
          ? this.board.flatMap((row, rowIndex) => row.map((_cell, colIndex) => ({ row: rowIndex, col: colIndex })))
            .filter(({ row, col }) => this.board[row]![col]!.value === selected.value)
          : this.board.flatMap((row, rowIndex) => row.map((_cell, colIndex) => ({ row: rowIndex, col: colIndex })))
            .filter(({ row, col }) => Math.abs(row - move.row) <= 1 && Math.abs(col - move.col) <= 1);
      this.removeAndCollapseReactors(removed, playerId);
      this.meta.removed = Number(this.meta.removed ?? 0) + removed.length;
      this.meta.lastPower = { playerId, type: action, cells: removed, at: Date.now() };
      this.meta.lastChain = { playerId, size: removed.length, color: 0, cells: removed, power: action, at: Date.now() };
      return { correct: true, points: removed.length * 8, message: `${action}: ${removed.length} nÃºcleos` };
    }
    const target = this.board[move.row]?.[move.col];
    const color = Number(target?.value ?? 0);
    if (!target || color <= 0) return { correct: false, neutral: true, message: "Núcleo vacío" };
    const queue = [{ row: move.row, col: move.col }];
    const group: Array<{ row: number; col: number }> = [];
    const visited = new Set<string>();
    while (queue.length) {
      const current = queue.shift()!;
      const key = `${current.row}:${current.col}`;
      if (visited.has(key) || Number(this.board[current.row]?.[current.col]?.value ?? 0) !== color) continue;
      visited.add(key); group.push(current);
      queue.push(
        { row: current.row - 1, col: current.col }, { row: current.row + 1, col: current.col },
        { row: current.row, col: current.col - 1 }, { row: current.row, col: current.col + 1 },
      );
    }
    if (group.length < 3) return { correct: false, neutral: true, message: "Necesitas al menos 3 núcleos conectados" };
    const createdSpecial = group.length >= 7 ? "RAINBOW" : group.length >= 6 ? "BOMB" : group.length >= 5 ? (move.row % 2 === 0 ? "ROW" : "COLUMN") : null;
    const removedGroup = createdSpecial ? group.filter(({ row, col }) => row !== move.row || col !== move.col) : group;
    this.removeAndCollapseReactors(removedGroup, playerId);
    if (createdSpecial) {
      const survivor = this.board[move.row]?.[move.col];
      if (survivor) { survivor.value = color; survivor.meta = { reactorOrb: true, special: createdSpecial, sourceColor: color }; }
    }
    const combo = Math.max(1, Math.floor(group.length / 4));
    this.meta.combo = combo;
    this.meta.removed = Number(this.meta.removed ?? 0) + group.length;
    const earnedPoints = group.length * group.length * combo;
    this.meta.reactorScore = Number(this.meta.reactorScore ?? 0) + earnedPoints;
    this.meta.level = Math.min(100, 1 + Math.floor(Number(this.meta.reactorScore) / 600));
    this.meta.lastChain = { playerId, size: group.length, color, cells: group, at: Date.now() };
    if (!this.hasReactorMove()) { this.meta.noMoves = true; this.completed = true; }
    return { correct: true, points: earnedPoints, message: createdSpecial ? `Creaste esfera ${createdSpecial}` : `Cadena ×${combo} de ${group.length}` };
  }

  private removeAndCollapseReactors(cells: Array<{ row: number; col: number }>, playerId: string): void {
    cells.forEach(({ row, col }) => {
      const cell = this.board[row]?.[col];
      if (cell) { cell.value = null; cell.ownerId = playerId; cell.isRevealed = false; }
    });
    const colors = Number(this.meta.colors ?? 5);
    for (let col = 0; col < this.board[0]!.length; col += 1) {
      const values = this.board.map((row) => row[col]!.value).filter((value) => value != null);
      while (values.length < this.board.length) values.unshift(1 + Math.floor(Math.random() * colors));
      values.forEach((value, row) => {
        const cell = this.board[row]![col]!;
        cell.value = value; cell.isRevealed = true; cell.ownerId = null; cell.meta = { reactorOrb: true };
      });
    }
    this.meta.noMoves = !this.hasReactorMove();
  }

  private hasReactorMove(): boolean {
    return this.board.some((row, rowIndex) => row.some((cell, colIndex) => Boolean(cell.meta.special) || this.reactorGroupSize(rowIndex, colIndex) >= 3));
  }

  private ensureReactorMove(): void {
    const playable = this.board.some((row, rowIndex) => row.some((_cell, colIndex) => this.reactorGroupSize(rowIndex, colIndex) >= 3));
    if (!playable) {
      const color = 1 + Math.floor(Math.random() * Number(this.meta.colors ?? 5));
      for (const [row, col] of [[0, 0], [0, 1], [1, 0]] as const) this.board[row]![col]!.value = color;
    }
  }

  private reactorGroupSize(row: number, col: number): number {
    const color = Number(this.board[row]?.[col]?.value ?? 0);
    if (color <= 0) return 0;
    const queue = [{ row, col }];
    const visited = new Set<string>();
    while (queue.length) {
      const current = queue.shift()!;
      const key = `${current.row}:${current.col}`;
      if (visited.has(key) || Number(this.board[current.row]?.[current.col]?.value ?? 0) !== color) continue;
      visited.add(key);
      queue.push(
        { row: current.row - 1, col: current.col }, { row: current.row + 1, col: current.col },
        { row: current.row, col: current.col - 1 }, { row: current.row, col: current.col + 1 },
      );
    }
    return visited.size;
  }

  private activeTeam(playerId: string): "BLUE" | "RED" {
    return Math.max(0, this.turnOrder.indexOf(playerId)) % 2 === 0 ? "BLUE" : "RED";
  }

  private applyCheckersMove(
    playerId: string,
    move: GenericMove,
    source: GenericCell,
  ): { correct: boolean; points?: number; extraTurn?: boolean } {
    const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : {};
    let targetRow = Number(payload.targetRow); let targetCol = Number(payload.targetCol);
    const target = this.board[targetRow]?.[targetCol];
    const team = this.activeTeam(playerId);
    if (this.meta.forcedPiece != null && this.meta.forcedPiece !== `${move.row}:${move.col}`) return { correct: false };
    if (!target || target.isBlocked || target.value !== null || source.meta.team !== team) return { correct: false };
    const king = source.meta.king === true;
    const dy = targetRow - move.row; const dx = targetCol - move.col;
    if (Math.abs(dy) !== Math.abs(dx) || dy === 0) return { correct: false };
    const direction = team === "BLUE" ? 1 : -1;
    const captures = this.checkersCapturesFor(team);
    const mustCapture = captures.length > 0;
    let captured: GenericCell | null = null;
    if (!king) {
      if (Math.abs(dy) === 1 && dy === direction && !mustCapture) {
        // Movimiento simple válido.
      } else if (Math.abs(dy) === 2) {
        const middle = this.board[move.row + dy / 2]![move.col + dx / 2]!;
        if (middle.meta.team == null || middle.meta.team === team) return { correct: false };
        captured = middle;
      } else return { correct: false };
    } else {
      let enemies = 0;
      for (let step = 1; step < Math.abs(dy); step += 1) {
        const traversed = this.board[move.row + Math.sign(dy) * step]![move.col + Math.sign(dx) * step]!;
        if (traversed.value === null) continue;
        if (traversed.meta.team === team || ++enemies > 1) return { correct: false };
        captured = traversed;
      }
      if (mustCapture && !captured) return { correct: false };
    }
    if (mustCapture && !captured) return { correct: false };
    target.value = source.value; target.isRevealed = true; target.ownerId = playerId;
    target.meta = { ...source.meta };
    source.value = null; source.isRevealed = false; source.ownerId = null;
    source.meta = { playable: true, team: null, king: false };
    if (captured) {
      captured.value = null; captured.isRevealed = false; captured.ownerId = null;
      captured.meta = { playable: true, team: null, king: false };
    }
    if ((team === "BLUE" && targetRow === 7) || (team === "RED" && targetRow === 0)) {
      target.meta.king = true;
      target.value = `${team}_KING`;
    }
    const extraTurn = captured !== null && this.checkersCapturesFrom(targetRow, targetCol, team).length > 0;
    if (extraTurn) this.meta.forcedPiece = `${targetRow}:${targetCol}`;
    else delete this.meta.forcedPiece;
    return { correct: true, points: captured ? 35 : 5, extraTurn };
  }

  private checkersCapturesFor(team: "BLUE" | "RED"): Array<{ row: number; col: number }> {
    const result: Array<{ row: number; col: number }> = [];
    this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
      if (cell.meta.team === team && this.checkersCapturesFrom(rowIndex, colIndex, team).length) {
        result.push({ row: rowIndex, col: colIndex });
      }
    }));
    return result;
  }

  private checkersCapturesFrom(row: number, col: number, team: "BLUE" | "RED"): Array<{ row: number; col: number }> {
    const result: Array<{ row: number; col: number }> = [];
    for (const dy of [-1, 1]) for (const dx of [-1, 1]) {
      const middle = this.board[row + dy]?.[col + dx];
      const landing = this.board[row + dy * 2]?.[col + dx * 2];
      if (middle?.meta.team != null && middle.meta.team !== team && landing && !landing.isBlocked && landing.value === null) {
        result.push({ row: row + dy * 2, col: col + dx * 2 });
      }
    }
    return result;
  }

  private applyChessTacticsMove(
    playerId: string,
    move: GenericMove,
    source: GenericCell,
  ): { correct: boolean; points?: number; extraTurn?: boolean } {
    const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : {};
    const action = String(payload.action ?? "MOVE").toUpperCase();
    const targetRow = Number(payload.targetRow); const targetCol = Number(payload.targetCol);
    const target = this.board[targetRow]?.[targetCol];
    const piece = pieceFromCell(source);
    if (!piece || piece.team !== this.activeTeam(playerId) || !target) return { correct: false };
    if (piece.statusEffects.some((effect) => effect.toUpperCase() === "STUNNED")) return { correct: false };
    const origin = { row: move.row, col: move.col };
    if (action === "MOVE" || action === "ATTACK") {
      const ambusher = this.board.flatMap((row, rowIndex) => row.map((cell, colIndex) => ({
        cell,
        row: rowIndex,
        col: colIndex,
        piece: pieceFromCell(cell),
      }))).find((entry) =>
        entry.piece?.team !== piece.team
        && entry.piece?.type === "KNIGHT"
        && entry.piece.ambushTarget?.row === targetRow
        && entry.piece.ambushTarget?.col === targetCol
      );
      if (ambusher?.piece && target.value === null) {
        const ambusherOwnerId = ambusher.cell.ownerId;
        clearPiece(source);
        clearPiece(ambusher.cell);
        ambusher.piece.ambushTarget = null;
        ambusher.piece.statusEffects = ambusher.piece.statusEffects.filter((effect) => !effect.toUpperCase().includes("AMBUSH"));
        writePiece(target, ambusher.piece, ambusherOwnerId);
        this.meta.lastChessAction = {
          action: "SKILL",
          skill: "AMBUSH",
          sourceRow: ambusher.row,
          sourceCol: ambusher.col,
          targetRow,
          targetCol,
          pieceType: "KNIGHT",
          at: Date.now(),
        };
        return { correct: true, points: 0 };
      }
      const valid = movementRange(piece, origin).some((point) => point.row === targetRow && point.col === targetCol);
      const enemy = pieceFromCell(target);
      const expectsAttack = enemy !== null;
      const attackValid = attackRange(piece, origin).some((point) => point.row === targetRow && point.col === targetCol);
      if ((!expectsAttack && !valid) || (expectsAttack && !attackValid) || !this.chessPathClear(origin, { row: targetRow, col: targetCol }, piece.type)) {
        return { correct: false };
      }
      if (piece.type === "PAWN" && !expectsAttack) {
        const direction = piece.team === "BLUE" ? 1 : -1;
        const distance = targetRow - move.row;
        if (targetCol !== move.col || distance !== direction && distance !== direction * 2) return { correct: false };
        if (Math.abs(distance) === 2 && this.board[move.row + direction]![move.col]!.value !== null) {
          return { correct: false };
        }
      }
      if (enemy && enemy.team === piece.team) return { correct: false };
      if (enemy?.statusEffects.some((effect) => effect.toUpperCase() === "INVULNERABLE")) return { correct: false };
      if (enemy?.isShielded && !["PAWN", "KNIGHT", "BISHOP"].includes(piece.type)) return { correct: false };
      if (piece.ap < 1) return { correct: false };
      const intimidation = piece.statusEffects.find((effect) => effect.startsWith("INTIMIDATED:"));
      if (intimidation) {
        const [, queenRow, queenCol] = intimidation.split(":").map(Number);
        const requiredY = Math.sign(move.row - queenRow!); const requiredX = Math.sign(move.col - queenCol!);
        if (Math.sign(targetRow - move.row) !== requiredY || Math.sign(targetCol - move.col) !== requiredX) return { correct: false };
        piece.statusEffects = piece.statusEffects.filter((effect) => effect !== intimidation);
      }
      piece.hasMoved = true;
      // Se consume el punto de acción antes de serializar la pieza destino.
      // Antes se escribía primero y el cliente recibía AP/estado obsoleto.
      piece.ap -= 1;
      if (enemy) {
        if (enemy.isShielded) {
          enemy.isShielded = false;
          writePiece(target, enemy, target.ownerId);
          piece.ap = 0;
          writePiece(source, piece, playerId);
          return { correct: true, points: 2 };
        }
        if (enemy.type === "PAWN") this.capturedPawns.set(enemy.team, (this.capturedPawns.get(enemy.team) ?? 0) + 1);
        if (enemy.type === "KING") {
          this.meta.winnerTeam = piece.team;
          this.completed = true;
        }
        writePiece(target, piece, playerId);
        clearPiece(source);
      } else {
        writePiece(target, piece, playerId);
        clearPiece(source);
      }
      this.meta.lastChessAction = {
        action: enemy ? "ATTACK" : "MOVE",
        sourceRow: move.row,
        sourceCol: move.col,
        targetRow,
        targetCol,
        pieceType: piece.type,
        at: Date.now(),
      };
      this.updateChessPassives();
      return { correct: true, points: enemy ? 45 : 4 };
    }
    if (action !== "SKILL") return { correct: false };
    const skill = skillFor(piece); const cost = skillCost(skill);
    if (piece.ap < cost || piece.currentCooldown > 0) return { correct: false };
    if (skill === "PHALANX_CHARGE") {
      const direction = piece.team === "BLUE" ? 1 : -1;
      if (targetRow !== move.row + direction * 2 || targetCol !== move.col || this.board[move.row + direction]![move.col]!.value !== null) return { correct: false };
      const pushed = pieceFromCell(target);
      if (pushed) {
        if (pushed.team === piece.team || pushed.type === "KING") return { correct: false };
        const landing = this.board[targetRow + direction]?.[targetCol];
        if (!landing || landing.value !== null || landing.isBlocked) return { correct: false };
        writePiece(landing, pushed, target.ownerId);
      } else if (target.value !== null || target.isBlocked) return { correct: false };
      writePiece(target, piece, playerId);
      clearPiece(source);
    } else if (skill === "SEISMIC_LEAP") {
      const valid = movementRange(piece, origin).some((point) => point.row === targetRow && point.col === targetCol);
      if (!valid || target.value !== null || target.isBlocked) return { correct: false };
      writePiece(target, piece, playerId); clearPiece(source);
      for (let row = targetRow - 1; row <= targetRow + 1; row += 1) for (let col = targetCol - 1; col <= targetCol + 1; col += 1) {
        const victimCell = this.board[row]?.[col]; const victim = victimCell ? pieceFromCell(victimCell) : null;
        if (victimCell && victim && victim.team !== piece.team) {
          victim.statusEffects = [...new Set([...victim.statusEffects, "STUNNED"])];
          writePiece(victimCell, victim, victimCell.ownerId);
        }
      }
    } else if (skill === "PIERCING_RAY") {
      const dy = Math.sign(targetRow - move.row); const dx = Math.sign(targetCol - move.col);
      if (Math.abs(targetRow - move.row) !== Math.abs(targetCol - move.col) || Math.abs(targetRow - move.row) > 3) {
        return { correct: false };
      }
      for (let row = move.row + dy, col = move.col + dx, distance = 1;
        distance <= 3 && row >= 0 && row < 8 && col >= 0 && col < 8;
        row += dy, col += dx, distance += 1) {
        const victimCell = this.board[row]![col]!;
        const victim = pieceFromCell(victimCell);
        if (!victim) continue;
        if (victim.team === piece.team || victim.type === "KING") break;
        const pawnFrontShield = victim.type === "PAWN" && Math.sign(move.row - row) === (victim.team === "BLUE" ? -1 : 1);
        if (victim.isShielded) {
          victim.isShielded = false; writePiece(victimCell, victim, victimCell.ownerId);
        } else if (!pawnFrontShield) {
          if (victim.type === "PAWN") this.capturedPawns.set(victim.team, (this.capturedPawns.get(victim.team) ?? 0) + 1);
          clearPiece(victimCell);
        }
        break;
      }
      writePiece(source, piece, playerId);
    } else if (skill === "STONE_WALL") {
      if (Math.max(Math.abs(targetRow - move.row), Math.abs(targetCol - move.col)) !== 1 || target.value !== null || target.isBlocked) return { correct: false };
      target.value = "WALL"; target.isRevealed = true; target.isBlocked = true;
      target.meta = { wall: true, wallTurns: 4, team: piece.team };
      writePiece(source, piece, playerId);
    } else if (skill === "ROYAL_INTIMIDATION") {
      if (targetRow !== move.row || targetCol !== move.col) return { correct: false };
      for (let row = move.row - 2; row <= move.row + 2; row += 1) for (let col = move.col - 2; col <= move.col + 2; col += 1) {
        const victimCell = this.board[row]?.[col]; const victim = victimCell ? pieceFromCell(victimCell) : null;
        if (victimCell && victim && victim.team !== piece.team) {
          victim.statusEffects = [...new Set([...victim.statusEffects, `INTIMIDATED:${move.row}:${move.col}`])];
          writePiece(victimCell, victim, victimCell.ownerId);
        }
      }
      writePiece(source, piece, playerId);
    } else {
      if (Math.max(Math.abs(targetRow - move.row), Math.abs(targetCol - move.col)) !== 1 || target.value !== null || target.isBlocked) return { correct: false };
      if ((this.capturedPawns.get(piece.team) ?? 0) <= 0) return { correct: false };
      const revived: Piece = { ...piece, id: `${piece.team}-PAWN-revived-${Date.now()}`, type: "PAWN", currentCooldown: 0, statusEffects: [], isShielded: false, hasMoved: true };
      writePiece(target, revived, playerId);
      this.capturedPawns.set(piece.team, (this.capturedPawns.get(piece.team) ?? 1) - 1);
      writePiece(source, piece, playerId);
    }
    piece.ap -= cost;
    piece.currentCooldown = cooldownFor(skill) + 1;
    const currentCell = this.board.flat().find((cell) => cell.meta.pieceId === piece.id);
    if (currentCell) writePiece(currentCell, piece, currentCell.ownerId);
    this.meta.lastChessAction = {
      action: "SKILL",
      skill,
      sourceRow: move.row,
      sourceCol: move.col,
      targetRow,
      targetCol,
      pieceType: piece.type,
      at: Date.now(),
    };
    this.updateChessPassives();
    return { correct: true, points: 32 };
  }

  private chessPathClear(origin: { row: number; col: number }, target: { row: number; col: number }, type: Piece["type"]): boolean {
    if (["PAWN", "KNIGHT", "KING"].includes(type)) return true;
    const dy = Math.sign(target.row - origin.row); const dx = Math.sign(target.col - origin.col);
    for (let row = origin.row + dy, col = origin.col + dx; row !== target.row || col !== target.col; row += dy, col += dx) {
      const traversed = this.board[row]?.[col];
      if (traversed?.isBlocked) return false;
      if (traversed?.value != null) {
        const ally = pieceFromCell(traversed);
        if (!(type === "QUEEN" && ally?.team === pieceFromCell(this.board[origin.row]![origin.col]!)?.team)) return false;
      }
    }
    return true;
  }

  private updateChessPassives(): void {
    this.board.flat().forEach((cell) => {
      const piece = pieceFromCell(cell);
      if (!piece) return;
      piece.isShielded = false;
      writePiece(cell, piece, cell.ownerId);
    });
    this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
      const piece = pieceFromCell(cell);
      if (!piece || piece.type !== "ROOK") return;
      for (let y = rowIndex - 1; y <= rowIndex + 1; y += 1) for (let x = colIndex - 1; x <= colIndex + 1; x += 1) {
        const allyCell = this.board[y]?.[x]; const ally = allyCell ? pieceFromCell(allyCell) : null;
        if (!allyCell || !ally || ally.team !== piece.team || ally.id === piece.id) continue;
        ally.isShielded = true;
        writePiece(allyCell, ally, allyCell.ownerId);
      }
    }));
  }

  private arrowShapeMembers(shapeId: string): Array<{ row: number; col: number; cell: GenericCell }> {
    const result: Array<{ row: number; col: number; cell: GenericCell }> = [];
    this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
      if (String(cell.meta.shapeId ?? `${rowIndex}:${colIndex}`) === shapeId) {
        result.push({ row: rowIndex, col: colIndex, cell });
      }
    }));
    return result;
  }

  private canArrowShapeEscape(shapeId: string, removed: Set<string>, directionOverride?: string): boolean {
    if (this.meta.pathModel === "SERPENTINE_V2") {
      type Point = { x: number; y: number };
      type Route = { id: string; points: Point[]; direction: string; exitVector: Point; thickness: number; memberKeys: string[]; gridX?: number; gridY?: number; gridCells?: number[] };
      const routes = this.meta.shapes as Route[];
      const route = routes.find((candidate) => candidate.id === shapeId);
      if (!route || route.points.length < 2) return false;
      const activeRoutes = routes.filter((candidate) => !candidate.memberKeys.every((key) => removed.has(key)));
      if (this.meta.gridBased === true && route.gridX !== undefined && route.gridY !== undefined) {
        const vector = directionOverride === "UP" ? { x: 0, y: -1 }
          : directionOverride === "RIGHT" ? { x: 1, y: 0 }
            : directionOverride === "DOWN" ? { x: 0, y: 1 }
              : directionOverride === "LEFT" ? { x: -1, y: 0 }
                : route.exitVector;
        const dimension = Number(this.meta.logicalColumns ?? 100);
        const occupied = new Set(activeRoutes.filter((candidate) => candidate.id !== route.id)
          .flatMap((candidate) => candidate.gridCells?.map((encoded) => `${encoded % dimension}:${Math.floor(encoded / dimension)}`)
            ?? [`${candidate.gridX}:${candidate.gridY}`]));
        let x = route.gridX + vector.x; let y = route.gridY + vector.y;
        while (x >= 0 && x < dimension && y >= 0 && y < dimension) {
          if (occupied.has(`${x}:${y}`)) return false;
          x += vector.x; y += vector.y;
        }
        return true;
      }
      const guaranteed = [...activeRoutes].sort((a, b) =>
        Number((a as unknown as { removalOrder?: number }).removalOrder ?? 0)
          - Number((b as unknown as { removalOrder?: number }).removalOrder ?? 0)
      )[0];
      // Salvaguarda de generaciÃ³n: siempre existe al menos una ruta liberable.
      // La colisiÃ³n geomÃ©trica sigue rigiendo para todas las demÃ¡s.
      if (guaranteed?.id === route.id) return true;
      const namedVectors: Record<string, Point> = {
        UP: { x: 0, y: -1 }, RIGHT: { x: 1, y: 0 }, DOWN: { x: 0, y: 1 }, LEFT: { x: -1, y: 0 },
        ANGLE_60: { x: .5, y: -.866 }, ANGLE_120: { x: -.5, y: -.866 },
        ANGLE_240: { x: -.5, y: .866 }, ANGLE_300: { x: .5, y: .866 },
      };
      // Direcciones anguladas conservan el vector de la ruta; nunca deben caer
      // accidentalmente al caso LEFT por no pertenecer a los cuatro cardinales.
      const vector = directionOverride ? (namedVectors[directionOverride] ?? route.exitVector) : route.exitVector;
      const head = route.points[route.points.length - 1]!;
      const candidates = [
        vector.x > 0 ? (1.08 - head.x) / vector.x : Number.POSITIVE_INFINITY,
        vector.x < 0 ? (-.08 - head.x) / vector.x : Number.POSITIVE_INFINITY,
        vector.y > 0 ? (1.08 - head.y) / vector.y : Number.POSITIVE_INFINITY,
        vector.y < 0 ? (-.08 - head.y) / vector.y : Number.POSITIVE_INFINITY,
      ].filter((value) => value > 0);
      const distance = Math.min(...candidates);
      const rayEnd = { x: head.x + vector.x * distance, y: head.y + vector.y * distance };
      const pointToSegment = (point: Point, start: Point, end: Point): number => {
        const dx = end.x - start.x; const dy = end.y - start.y; const square = dx * dx + dy * dy;
        if (square === 0) return Math.hypot(point.x - start.x, point.y - start.y);
        const t = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / square));
        return Math.hypot(point.x - start.x - t * dx, point.y - start.y - t * dy);
      };
      const rayBlockedBy = (obstacle: Route): boolean => {
        for (let index = 1; index < obstacle.points.length; index += 1) {
          const start = obstacle.points[index - 1]!; const end = obstacle.points[index]!;
          for (let step = 1; step <= 32; step += 1) {
            const t = step / 32;
            const point = { x: head.x + (rayEnd.x - head.x) * t, y: head.y + (rayEnd.y - head.y) * t };
            if (pointToSegment(point, start, end) <= route.thickness + obstacle.thickness + .008) return true;
          }
        }
        return false;
      };
      return !routes.some((obstacle) =>
        obstacle.id !== route.id
        && !obstacle.memberKeys.every((key) => removed.has(key))
        && rayBlockedBy(obstacle)
      );
    }
    if (this.meta.freeSpace === true) {
      type SpatialShape = {
        id: string; x: number; y: number; z: number; width: number; height: number; depth: number;
        direction: string; pathType: string; memberKeys: string[];
      };
      const shapes = this.meta.shapes as SpatialShape[];
      const shape = shapes.find((candidate) => candidate.id === shapeId);
      if (!shape) return false;
      const obstacles = shapes.filter((candidate) =>
        candidate.id !== shape.id && !candidate.memberKeys.every((key) => removed.has(key))
      );
      // Una ligera superposición visual de las formas curvas de la silueta no
      // debe convertir el nivel en un interbloqueo imposible desde el inicio.
      const initiallyOverlapping = new Set(
        obstacles.filter((obstacle) => boxesIntersect3d(shape, obstacle)).map((obstacle) => obstacle.id),
      );
      const direction = directionOverride ?? shape.direction;
      const vector = direction === "UP" ? { x: 0, y: -1, z: 0 }
        : direction === "RIGHT" ? { x: 1, y: 0, z: 0 }
          : direction === "DOWN" ? { x: 0, y: 1, z: 0 }
            : direction === "LEFT" ? { x: -1, y: 0, z: 0 }
              : direction === "FRONT" ? { x: 0, y: 0, z: -1 }
                : { x: 0, y: 0, z: 1 };
      const perpendicular = { x: -vector.y, y: vector.x };
      for (let step = 1; step <= 80; step += 1) {
        const progress = step / 40;
        const curveSign = shape.pathType === "CURVE_LEFT" ? -1 : shape.pathType === "CURVE_RIGHT" ? 1 : 0;
        const curve = curveSign * Math.sin(Math.min(1, progress) * Math.PI) * .11;
        const projected = {
          x: shape.x + vector.x * progress + perpendicular.x * curve,
          y: shape.y + vector.y * progress + perpendicular.y * curve,
          z: shape.z + vector.z * progress,
          width: shape.width,
          height: shape.height,
          depth: shape.depth,
        };
        const outside = projected.x + projected.width < 0 || projected.x > 1
          || projected.y + projected.height < 0 || projected.y > 1
          || projected.z + projected.depth < 0 || projected.z > 1;
        if (outside) return true;
        if (obstacles.some((obstacle) => !initiallyOverlapping.has(obstacle.id) && boxesIntersect3d(projected, obstacle))) return false;
      }
      return false;
    }
    const members = this.arrowShapeMembers(shapeId);
    if (!members.length) return false;
    const own = new Set(members.map(({ row, col }) => `${row}:${col}`));
    const direction = String(members[0]!.cell.meta.arrow);
    const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1]
      : direction === "DOWN" ? [1, 0] : [0, -1];
    return members.every((member) => {
      for (let row = member.row + dy, col = member.col + dx;
        row >= 0 && row < this.board.length && col >= 0 && col < this.board[0]!.length;
        row += dy, col += dx) {
        const key = `${row}:${col}`;
        if (!own.has(key) && !removed.has(key)) return false;
      }
      return true;
    });
  }

  private botValue(row: number, col: number, correct: boolean): unknown {
    if (this.gameType === "WORD_SEARCH") {
      const placement = (this.meta.placements as WordPlacement[]).find((item) => item.startRow === row && item.startCol === col);
      const word = placement?.word ?? "ERROR";
      return { word: correct ? word : `${word}X`, endRow: placement?.endRow ?? row, endCol: placement?.endCol ?? col };
    }
    if (this.gameType === "DOTS_AND_BOXES") {
      const cell = this.board[row]![col]!;
      const free = ["top", "right", "bottom", "left"].filter((side) => cell.meta[side] !== true);
      return free[0] ?? "top";
    }
    if (this.gameType === "MINESWEEPER") {
      const mine = this.answers[row]![col] === true;
      if (correct && mine) {
        const safe = this.findFirstSafeCell();
        if (safe) return this.botValue(safe.row, safe.col, true);
      }
      return "REVEAL";
    }
    const answer = this.answers[row]![col];
    if (this.gameType === "NURIKABE") return answer === true ? "RIVER" : "ISLAND";
    if (correct) return answer;
    if (typeof answer === "number") return answer % 9 + 1;
    if (typeof answer === "boolean") return !answer;
    return "?";
  }

  private findFirstSafeCell(): { row: number; col: number } | null {
    for (let row = 0; row < this.answers.length; row += 1) {
      for (let col = 0; col < this.answers[row]!.length; col += 1) {
        if (this.answers[row]![col] !== true && this.board[row]![col]!.ownerId === null) return { row, col };
      }
    }
    return null;
  }

  private findFirstTrueCell(): { row: number; col: number } | null {
    for (let row = 0; row < this.answers.length; row += 1) {
      for (let col = 0; col < this.answers[row]!.length; col += 1) {
        if (this.answers[row]![col] === true && this.board[row]![col]!.ownerId === null) return { row, col };
      }
    }
    return null;
  }

  private adjacentMineCount(row: number, col: number): number {
    let count = 0;
    for (let y = Math.max(0, row - 1); y <= Math.min(this.answers.length - 1, row + 1); y += 1) {
      for (let x = Math.max(0, col - 1); x <= Math.min(this.answers[0]!.length - 1, col + 1); x += 1) {
        if (this.answers[y]![x] === true) count += 1;
      }
    }
    return count;
  }

  private mirrorDotsEdge(row: number, col: number, side: string): GenericCell | null {
    const neighbour = side === "top" ? { row: row - 1, col, edge: "bottom" }
      : side === "right" ? { row, col: col + 1, edge: "left" }
        : side === "bottom" ? { row: row + 1, col, edge: "top" }
          : { row, col: col - 1, edge: "right" };
    const adjacent = this.board[neighbour.row]?.[neighbour.col];
    if (adjacent) adjacent.meta[neighbour.edge] = true;
    return adjacent ?? null;
  }

  private isPuzzleComplete(): boolean {
    if (this.gameType === "SECRET_CODE") return this.completed;
    if (this.gameType === "CAPITAL_ARENA") return false;
    if (this.gameType === "MINESWEEPER") {
      return this.board.every((row, y) => row.every((cell, x) => this.answers[y]![x] === true || cell.ownerId !== null));
    }
    if (this.gameType === "WORD_SEARCH") return (this.meta.foundWords as string[]).length === (this.meta.words as string[]).length;
    if (this.gameType === "HANGMAN") {
      return this.board[0]!.every((cell) => cell.value !== null)
        || (this.turnOrder.length > 0 && this.turnOrder.every((id) => (this.hangmanErrors.get(id) ?? 0) >= 6));
    }
    if (this.gameType === "ARROWS_ESCAPE") {
      const stageCleared = [...this.arrowRemoved.values()]
        .some((removed) => removed.size === this.board.length * this.board[0]!.length);
      if (!stageCleared) return false;
      const level = Number(this.meta.level ?? 1);
      if (level >= Number(this.meta.levelCount ?? 100)) return true;
      const nextLevel = level + 1;
      const next = createPuzzleBlueprint("ARROWS_ESCAPE", {
        seed: `${this.gameId}-stage-${nextLevel}`,
        difficulty: String(this.meta.difficulty ?? "MEDIUM") as PuzzleDifficulty,
        level: nextLevel,
      });
      this.board = next.board;
      this.answers = next.answers;
      this.meta = next.meta;
      this.arrowRemoved.clear();
      this.arrowFailedTaps.clear();
      this.arrowCombo.clear();
      this.arrowLastSuccessAt.clear();
      this.arrowBlockedUntil.clear();
      this.arrowTimerTriggered.clear();
      this.arrowTimerStartedAt = Date.now();
      this.arrowStaticGeometryChanged = true;
      this.arrowStageAdvancedTo = nextLevel;
      return false;
    }
    if (this.gameType === "MEMORY_NEON") return this.board.flat().every((cell) => cell.ownerId !== null);
    if (this.gameType === "MERGE_2048") {
      if (this.board.flat().some((cell) => Number(cell.value ?? 0) >= Number(this.meta.target ?? 256))) return true;
      if (this.board.flat().some((cell) => cell.value === null)) return false;
      for (let row = 0; row < 4; row += 1) for (let col = 0; col < 4; col += 1) {
        const value = this.board[row]![col]!.value;
        if (this.board[row + 1]?.[col]?.value === value || this.board[row]?.[col + 1]?.value === value) return false;
      }
      this.meta.gameOverReason = "NO_MOVES";
      return true;
    }
    if (this.gameType === "TIC_TAC_TOE") return this.meta.variant === "CLASSIC"
      ? this.classicTicWinner() !== null || this.board.flat().every((cell) => cell.value !== null)
      : this.ticTacToeWinner() !== null;
    if (this.gameType === "CHECKERS") {
      const teams = new Set(this.board.flat().map((cell) => cell.meta.team).filter(Boolean));
      return teams.size <= 1;
    }
    if (this.gameType === "CHESS_TACTICS") {
      const kings = new Set(this.board.flat()
        .filter((cell) => cell.meta.type === "KING")
        .map((cell) => cell.meta.team)
        .filter(Boolean));
      return this.completed || kings.size <= 1;
    }
    if (this.gameType === "DOTS_AND_BOXES") return this.board.every((row) => row.every((cell) => cell.ownerId !== null));
    if (this.gameType === "NURIKABE") {
      return this.board.every((row, y) => row.every((cell, x) => cell.meta.islandClue === true || cell.value === (this.answers[y]![x] === true ? "RIVER" : "ISLAND")));
    }
    if (this.gameType === "NEXUS_ZERO") {
      return this.board.flat().every((cell) => cell.value === null)
        && Number(this.meta.nexusRound ?? 1) >= Number(this.meta.nexusTargetRounds ?? 3);
    }
    if (this.gameType === "TOWER_DEFENSE") return this.completed;
    if (this.gameType === "REACTOR_CHAIN") {
      return this.meta.noMoves === true || Number(this.meta.level ?? 1) >= 100;
    }
    if (["HITORI", "BRIDGES"].includes(this.gameType)) {
      return this.board.every((row, y) => row.every((cell, x) => this.answers[y]![x] !== true || cell.ownerId !== null));
    }
    if (this.gameType === "CROSS_LETTERS") {
      return this.letterBag.length === 0 && [...this.racks.values()].every((rack) => rack.length === 0 || findWordForRack(rack) === null);
    }
    return this.board.every((row, y) => row.every((cell, x) => this.answers[y]![x] === null || cell.isBlocked || cell.ownerId !== null));
  }

  private syncLetterPlayers(game: ArenaGame, now: number): void {
    const players = game.snapshot(now).players;
    const ids = players.map((player) => player.id);
    this.turnOrder = ids;
    for (const player of players) this.ensureRack(player.id, player.isBot);
    for (const id of [...this.racks.keys()]) if (!ids.includes(id)) this.racks.delete(id);
    if (!this.activePlayerId || !ids.includes(this.activePlayerId) || now >= this.turnEndsAt) {
      const previous = this.activePlayerId ? ids.indexOf(this.activePlayerId) : -1;
      this.activePlayerId = ids.length ? ids[(previous + 1) % ids.length]! : null;
      this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
    }
  }

  private syncTurnPlayers(game: ArenaGame): void {
    const ids = game.snapshot().players.map((player) => player.id)
      .filter((id) => this.gameType !== "HANGMAN" || (this.hangmanErrors.get(id) ?? 0) < 6);
    const preserved = this.turnOrder.filter((id) => ids.includes(id));
    this.turnOrder = [...preserved, ...ids.filter((id) => !preserved.includes(id))];
    if (!this.activePlayerId || !ids.includes(this.activePlayerId)) this.activePlayerId = ids[0] ?? null;
  }

  private syncSecretPlayers(game: ArenaGame): void {
    const players = game.snapshot().players;
    if (this.secretAssignments.size === 0 && players.length > 0) {
      const shuffled = [...players].sort(() => Math.random() - 0.5);
      shuffled.forEach((player, index) => {
        const team: "RED" | "BLUE" = index % 2 === 0 ? "RED" : "BLUE";
        const hasCaptain = [...this.secretAssignments.values()].some((entry) => entry.team === team && entry.role === "CAPTAIN");
        this.secretAssignments.set(player.id, { team, role: hasCaptain ? "OPERATIVE" : "CAPTAIN" });
      });
      return;
    }
    for (const player of players) {
      if (this.secretAssignments.has(player.id)) continue;
      const red = [...this.secretAssignments.values()].filter((entry) => entry.team === "RED").length;
      const blue = [...this.secretAssignments.values()].filter((entry) => entry.team === "BLUE").length;
      const team: "RED" | "BLUE" = red <= blue ? "RED" : "BLUE";
      const hasCaptain = [...this.secretAssignments.values()].some((entry) => entry.team === team && entry.role === "CAPTAIN");
      this.secretAssignments.set(player.id, { team, role: hasCaptain ? "OPERATIVE" : "CAPTAIN" });
    }
  }

  private secretRemainingCounts(): Record<string, number> {
    const remaining: Record<string, number> = { RED: 0, BLUE: 0 };
    this.board.forEach((row, y) => row.forEach((cell, x) => {
      const identity = String(this.answers[y]![x]);
      if (cell.ownerId === null && (identity === "RED" || identity === "BLUE")) remaining[identity] = (remaining[identity] ?? 0) + 1;
    }));
    return remaining;
  }

  private secretActivePlayerId(): string | null {
    const requiredRole = this.secretClue == null ? "CAPTAIN" : "OPERATIVE";
    return [...this.secretAssignments.entries()]
      .find(([, assignment]) => assignment.team === this.secretCurrentTeam && assignment.role === requiredRole)?.[0] ?? null;
  }

  private syncCapitalPlayers(game: ArenaGame): void {
    const players = game.snapshot().players;
    this.turnOrder = players.map((player) => player.id);
    for (const player of players) {
      if (!this.capitalBalances.has(player.id)) {
        this.capitalBalances.set(player.id, 1_500);
        this.capitalPositions.set(player.id, 0);
        game.setGenericScore(player.id, 1_500);
      }
    }
    if (!this.activePlayerId || !this.turnOrder.includes(this.activePlayerId)) {
      this.activePlayerId = this.turnOrder[0] ?? null;
      this.capitalStage = "ROLL";
    }
  }

  private applyCapitalMove(playerId: string, move: GenericMove, game: ArenaGame): {
    correct: boolean; points?: number; neutral?: boolean; message?: string
  } {
    const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : {};
    const action = String(payload.action ?? "").toUpperCase();
    const authoritativePosition = this.capitalPositions.get(playerId) ?? 0;
    if (payload.from != null && Number.parseInt(String(payload.from), 10) !== authoritativePosition) {
      return { correct: false, neutral: true, message: "La posición de origen no coincide con el servidor" };
    }
    if (payload.to != null) {
      const requestedTarget = Number.parseInt(String(payload.to), 10);
      const clockwiseDistance = (requestedTarget - authoritativePosition + 40) % 40;
      // Ningún payload del cliente puede teletransportar una ficha. Los saltos
      // por dados o cartas se calculan exclusivamente en el servidor.
      if (!Number.isInteger(requestedTarget) || clockwiseDistance !== 1) {
        return { correct: false, neutral: true, message: "Movimiento no adyacente rechazado" };
      }
    }
    if (action === "ROLL") {
      if (this.capitalStage !== "ROLL") return { correct: false };
      const from = this.capitalPositions.get(playerId) ?? 0;
      const dice: [number, number] = [1 + Math.floor(Math.random() * 6), 1 + Math.floor(Math.random() * 6)];
      const distance = dice[0] + dice[1];
      const rawTarget = from + distance;
      const to = rawTarget % 40;
      if (rawTarget >= 40) this.changeCapitalBalance(playerId, 200);
      this.capitalDice = dice;
      this.capitalPositions.set(playerId, to);
      this.capitalLastMove = { playerId, from, to };
      this.resolveCapitalLanding(playerId, to);
      this.refreshCapitalScores(game);
      return { correct: true, points: 0 };
    }
    if (action === "SKILL") {
      if (this.capitalSkillsUsed.has(playerId)) return { correct: false };
      this.capitalSkillsUsed.add(playerId);
      this.changeCapitalBalance(playerId, 100);
      this.capitalEvent = "Impulso de mercado activado: +100 créditos";
      this.refreshCapitalScores(game);
      return { correct: true, points: 0 };
    }
    if (action === "BUY") {
      const index = this.capitalPendingProperty;
      if (this.capitalStage !== "BUY_OR_END" || index == null || this.capitalPropertyOwners.has(index)) return { correct: false };
      const space = this.capitalSpace(index);
      const balance = this.capitalBalances.get(playerId) ?? 0;
      if (!space || space.price <= 0 || balance < space.price) return { correct: false };
      this.changeCapitalBalance(playerId, -space.price);
      this.capitalPropertyOwners.set(index, playerId);
      this.capitalPropertyLevels.set(index, 0);
      const propertyCell = this.capitalCell(index);
      if (propertyCell) propertyCell.ownerId = playerId;
      this.capitalEvent = `${playerId.slice(0, 8)} conquistó ${space.name}`;
      this.capitalPendingProperty = null;
      this.capitalStage = "END";
      this.refreshCapitalScores(game);
      return { correct: true, points: 0 };
    }
    if (action === "BUILD") {
      if (this.capitalStage !== "END") return { correct: false };
      const index = this.capitalPositions.get(playerId);
      if (index == null) return { correct: false };
      const space = this.capitalSpace(index);
      if (!space) return { correct: false };
      const level = this.capitalPropertyLevels.get(index) ?? 0;
      const cost = Math.max(50, Math.round(space.price / 2));
      if (this.capitalPropertyOwners.get(index) !== playerId || level >= 4 || (this.capitalBalances.get(playerId) ?? 0) < cost) return { correct: false };
      this.changeCapitalBalance(playerId, -cost);
      this.capitalPropertyLevels.set(index, level + 1);
      this.capitalEvent = `Mejora de hackeo nivel ${level + 1} en ${space.name}`;
      this.refreshCapitalScores(game);
      return { correct: true, points: 0 };
    }
    if (action === "END_TURN") {
      if (this.capitalStage === "ROLL") return { correct: false };
      this.advanceCapitalTurn();
      return { correct: true, points: 0 };
    }
    return { correct: false };
  }

  private resolveCapitalLanding(playerId: string, index: number): void {
    const space = this.capitalSpace(index);
    if (!space) { this.capitalStage = "END"; return; }
    if (space.type === "GO_TO_JAIL") {
      this.capitalPositions.set(playerId, 10);
      this.capitalLastMove = { playerId, from: index, to: 10 };
      this.capitalEvent = "La red de seguridad te envió a la Cárcel";
      this.capitalStage = "END";
      return;
    }
    if (space.type === "CHANCE") {
      this.drawCapitalCard(playerId, index);
      return;
    }
    if (space.type === "TAX") {
      this.changeCapitalBalance(playerId, -120);
      this.capitalEvent = "Impuesto de infraestructura: -120";
      this.capitalStage = "END";
      return;
    }
    const owner = this.capitalPropertyOwners.get(index);
    if (space.price > 0 && !owner) {
      this.capitalPendingProperty = index;
      this.capitalStage = "BUY_OR_END";
      this.capitalEvent = `${space.name} está disponible por ${space.price}`;
      return;
    }
    if (owner && owner !== playerId) {
      const level = this.capitalPropertyLevels.get(index) ?? 0;
      const requestedRent = space.rent * (level + 1);
      const rent = Math.min(requestedRent, this.capitalBalances.get(playerId) ?? 0);
      this.changeCapitalBalance(playerId, -rent);
      this.changeCapitalBalance(owner, rent);
      this.capitalEvent = `Renta ${rent} pagada por ${space.name}`;
    } else this.capitalEvent = owner === playerId ? `Tu distrito ${space.name}` : space.name;
    this.capitalStage = "END";
  }

  private drawCapitalCard(playerId: string, from: number): void {
    const cards: Array<{
      title: string;
      description: string;
      kind: "BONUS" | "PENALTY" | "MOVE";
      money?: number;
      move?: number;
      jail?: boolean;
    }> = [
      { title: "Hackathon Maestro", description: "Recibes 200 créditos", kind: "BONUS" as const, money: 200 },
      { title: "Inversión Relámpago", description: "Recibes 120 créditos", kind: "BONUS" as const, money: 120 },
      { title: "Fallo de Servidor", description: "Pagas una multa de 100 créditos", kind: "PENALTY" as const, money: -100 },
      { title: "Auditoría de la Arena", description: "Pagas una multa de 160 créditos", kind: "PENALTY" as const, money: -160 },
      { title: "Atajo Quantum", description: "Avanzas 3 casillas", kind: "MOVE" as const, move: 3 },
      { title: "Firewall Policial", description: "Vas directamente a la cárcel", kind: "PENALTY" as const, jail: true },
    ];
    const selected = cards[Math.floor(Math.random() * cards.length)]!;
    if (selected.money) this.changeCapitalBalance(playerId, selected.money);
    if (selected.jail) {
      this.capitalPositions.set(playerId, 10);
      this.capitalLastMove = { playerId, from, to: 10 };
    } else if (selected.move) {
      const rawTarget = from + selected.move;
      const target = rawTarget % 40;
      if (rawTarget >= 40) this.changeCapitalBalance(playerId, 200);
      this.capitalPositions.set(playerId, target);
      this.capitalLastMove = { playerId, from, to: target };
      this.resolveCapitalLanding(playerId, target);
    }
    this.capitalCard = {
      id: randomUUID(),
      playerId,
      title: selected.title,
      description: selected.description,
      kind: selected.kind,
    };
    this.capitalEvent = `🎴 ${selected.title}: ${selected.description}`;
    this.capitalStage = "END";
  }

  private advanceCapitalTurn(): void {
    if (!this.turnOrder.length) return;
    const current = Math.max(0, this.turnOrder.indexOf(this.activePlayerId ?? ""));
    this.activePlayerId = this.turnOrder[(current + 1) % this.turnOrder.length]!;
    this.capitalStage = "ROLL";
    this.capitalPendingProperty = null;
    this.capitalEvent = `Turno de ${this.activePlayerId.slice(0, 8)}`;
  }

  private createCapitalBotMove(playerId?: string): GenericMove | null {
    if (!playerId || this.activePlayerId !== playerId) return null;
    let action = "ROLL";
    if (this.capitalStage === "BUY_OR_END") {
      const index = this.capitalPendingProperty;
      const space = index == null ? null : this.capitalSpace(index);
      action = space && (this.capitalBalances.get(playerId) ?? 0) >= space.price && Math.random() < .72 ? "BUY" : "END_TURN";
    } else if (this.capitalStage === "END") {
      const index = this.capitalPositions.get(playerId) ?? 0;
      const space = this.capitalSpace(index);
      const level = this.capitalPropertyLevels.get(index) ?? 0;
      const buildCost = Math.max(50, Math.round((space?.price ?? 0) / 2));
      const canBuild = this.capitalPropertyOwners.get(index) === playerId &&
        level < 4 && (this.capitalBalances.get(playerId) ?? 0) >= buildCost;
      action = canBuild && Math.random() < .32 ? "BUILD" : "END_TURN";
    }
    return { requestId: `capital-bot-${randomUUID()}`, row: 10, col: 10, val: { action } };
  }

  private changeCapitalBalance(playerId: string, delta: number): void {
    this.capitalBalances.set(playerId, Math.max(0, (this.capitalBalances.get(playerId) ?? 0) + delta));
  }

  private refreshCapitalScores(game: ArenaGame): void {
    for (const playerId of this.turnOrder) {
      let netWorth = this.capitalBalances.get(playerId) ?? 0;
      for (const [index, owner] of this.capitalPropertyOwners) {
        if (owner !== playerId) continue;
        const space = this.capitalSpace(index);
        if (space) netWorth += space.price + (this.capitalPropertyLevels.get(index) ?? 0) * Math.round(space.price / 2);
      }
      game.setGenericScore(playerId, netWorth);
    }
  }

  private capitalSpace(index: number): { index: number; name: string; type: string; price: number; rent: number } | null {
    const spaces = this.meta.spaces as Array<{ index: number; name: string; type: string; price: number; rent: number }>;
    return spaces.find((space) => space.index === index) ?? null;
  }

  private capitalCell(index: number): GenericCell | null {
    return this.board.flat().find((cell) => Number(cell.meta.index) === index) ?? null;
  }

  private advanceStrictTurn(): void {
    if (!this.turnOrder.length) return;
    const index = Math.max(0, this.turnOrder.indexOf(this.activePlayerId ?? ""));
    const previousTeam = this.gameType === "CHESS_TACTICS" && this.activePlayerId
      ? this.activeTeam(this.activePlayerId)
      : null;
    this.activePlayerId = this.turnOrder[(index + 1) % this.turnOrder.length]!;
    if (this.gameType === "CHESS_TACTICS") {
      const nextTeam = this.activeTeam(this.activePlayerId);
      // Fin de turno: enfría habilidades del equipo que actuó y consume Stun.
      this.board.flat().forEach((cell) => {
        if (cell.meta.wall === true) {
          const turns = Number(cell.meta.wallTurns ?? 1) - 1;
          if (turns <= 0) clearPiece(cell); else cell.meta.wallTurns = turns;
          return;
        }
        const piece = pieceFromCell(cell);
        if (!piece || piece.team !== previousTeam) return;
        piece.currentCooldown = Math.max(0, piece.currentCooldown - 1);
        piece.statusEffects = piece.statusEffects.filter((effect) => effect.toUpperCase() !== "STUNNED");
        writePiece(cell, piece, cell.ownerId);
      });
      // Aura de Inspiración del Rey: reduce un turno adicional a aliados adyacentes.
      this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
        const king = pieceFromCell(cell);
        if (!king || king.team !== nextTeam || king.type !== "KING") return;
        for (let y = rowIndex - 1; y <= rowIndex + 1; y += 1) for (let x = colIndex - 1; x <= colIndex + 1; x += 1) {
          const allyCell = this.board[y]?.[x]; const ally = allyCell ? pieceFromCell(allyCell) : null;
          if (!allyCell || !ally || ally.team !== king.team || ally.id === king.id) continue;
          ally.currentCooldown = Math.max(0, ally.currentCooldown - 1);
          writePiece(allyCell, ally, allyCell.ownerId);
        }
      }));
      this.board.flat().forEach((cell) => {
        const piece = pieceFromCell(cell);
        if (!piece || piece.team !== nextTeam) return;
        piece.ap = piece.maxAp;
        piece.canActThisTurn = false;
        piece.statusEffects = piece.statusEffects.filter((effect) => effect.toUpperCase() !== "INVULNERABLE");
        writePiece(cell, piece, cell.ownerId);
      });
      this.updateChessPassives();
    }
  }

  private ensureRack(playerId: string, guaranteeBotMove = false): void {
    if (this.racks.has(playerId)) return;
    let rack: string[] = [];
    let suggested: string | null = null;
    for (let attempt = 0; attempt < (guaranteeBotMove ? 80 : 1); attempt += 1) {
      rack = [];
      while (rack.length < 7 && this.letterBag.length) rack.push(this.letterBag.pop()!);
      suggested = findAnchoredWordForRack(rack, String(this.meta.centralWord ?? "ARENA"));
      if (!guaranteeBotMove || suggested) break;
      this.letterBag.unshift(...rack);
      shuffleLetters(this.letterBag);
    }
    this.racks.set(playerId, rack);
    this.suggestedWords.set(playerId, suggested ?? findWordForRack(rack) ?? "");
  }

  private refillRack(playerId: string): void {
    const rack = this.racks.get(playerId) ?? [];
    while (rack.length < 7 && this.letterBag.length) rack.push(this.letterBag.pop()!);
    this.racks.set(playerId, rack);
    this.suggestedWords.set(playerId, findWordForRack(rack) ?? "");
  }

  private advanceLetterTurn(now: number): void {
    if (!this.turnOrder.length) return;
    const index = Math.max(0, this.turnOrder.indexOf(this.activePlayerId ?? ""));
    this.activePlayerId = this.turnOrder[(index + 1) % this.turnOrder.length]!;
    this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
  }

  private applyCrossLettersMove(playerId: string, move: GenericMove): { correct: boolean; points?: number } {
    const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : { word: move.val };
    const word = normalizeSpanishWord(String(payload.word ?? ""));
    const direction = String(payload.direction ?? "H").toUpperCase();
    if (!SPANISH_WORDS.has(word) || !["H", "V"].includes(direction) || word.length < 2 || [...word].length > 15) return { correct: false };
    const rowStep = direction === "V" ? 1 : 0;
    const colStep = direction === "H" ? 1 : 0;
    const coordinates = [...word].map((letter, index) => ({
      letter, row: move.row + rowStep * index, col: move.col + colStep * index
    }));
    if (coordinates.some(({ row, col }) => !this.board[row]?.[col])) return { correct: false };
    const hasTiles = this.board.some((row) => row.some((target) => target.value !== null));
    if (!hasTiles && !coordinates.some(({ row, col }) => row === 7 && col === 7)) return { correct: false };
    if (hasTiles && !coordinates.some(({ row, col, letter }) => this.board[row]![col]!.value === letter)) return { correct: false };
    if (coordinates.some(({ row, col, letter }) => {
      const existing = this.board[row]![col]!.value;
      return existing !== null && existing !== letter;
    })) return { correct: false };

    const rack = [...(this.racks.get(playerId) ?? [])];
    const newTiles = coordinates.filter(({ row, col }) => this.board[row]![col]!.value === null);
    for (const { letter } of newTiles) {
      const index = rack.indexOf(letter);
      if (index < 0) return { correct: false };
      rack.splice(index, 1);
    }
    // Cross-check autoritativo: se simula la colocación completa y se validan
    // todas las palabras horizontales y verticales que toca cada ficha nueva.
    const projected = this.board.map((boardRow) => boardRow.map((target) => target.value == null ? "" : String(target.value)));
    coordinates.forEach(({ row, col, letter }) => { projected[row]![col] = letter; });
    const formedWords = new Set<string>([word]);
    for (const tile of newTiles) {
      for (const axis of ["H", "V"] as const) {
        const cross = readOrthogonalWord(projected, tile.row, tile.col, axis);
        if (cross.length >= 2) formedWords.add(cross);
      }
    }
    if ([...formedWords].some((formed) => !SPANISH_WORDS.has(formed))) return { correct: false };
    let wordMultiplier = 1;
    let points = 0;
    for (const { row, col, letter } of coordinates) {
      const target = this.board[row]![col]!;
      const fresh = target.value === null;
      const bonus = fresh ? String(target.meta.bonus ?? "NONE") : "NONE";
      const letterMultiplier = bonus === "TL" ? 3 : bonus === "DL" ? 2 : 1;
      if (bonus === "TW") wordMultiplier *= 3;
      if (bonus === "DW") wordMultiplier *= 2;
      points += (SCRABBLE_SCORES[letter] ?? 1) * letterMultiplier;
      if (fresh) {
        target.value = letter; target.ownerId = playerId; target.isRevealed = true;
      } else target.ownerId = playerId; // Word Conquest: reutilizar una letra reconquista la casilla.
    }
    const entry = SPANISH_DICTIONARY.find((candidate) => normalizeSpanishWord(candidate.word) === word);
    const played = (this.meta.playedWords ?? []) as Array<Record<string, unknown>>;
    this.meta.playedWords = [...played.slice(-11), {
      word, definition: entry?.clue ?? "Palabra vÃ¡lida del diccionario espaÃ±ol", playerId, points: points * wordMultiplier, at: Date.now(),
    }];
    this.meta.lastWord = { word, playerId, points: points * wordMultiplier, at: Date.now() };
    this.racks.set(playerId, rack);
    this.refillRack(playerId);
    return { correct: true, points: points * wordMultiplier + (newTiles.length === 7 ? 50 : 0) };
  }

  private applySecretCodeMove(playerId: string, move: GenericMove, cell: GenericCell): { correct: boolean; points?: number } {
    const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : {};
    const action = String(payload.action ?? "GUESS").toUpperCase();
    const assignment = this.secretAssignments.get(playerId);
    if (!assignment || assignment.team !== this.secretCurrentTeam) return { correct: false };
    if (action === "CLUE") {
      if (assignment.role !== "CAPTAIN") return { correct: false };
      const word = normalizeSpanishWord(String(payload.clue ?? ""));
      const count = Number(payload.count);
      if (word.length < 2 || word.length > 20 || !Number.isInteger(count) || count < 1 || count > 9) return { correct: false };
      if (this.secretWords().some((boardWord) => boardWord.includes(word) || word.includes(boardWord))) return { correct: false };
      this.secretClue = { word, count, remaining: count };
      return { correct: true, points: 0 };
    }
    if (action !== "GUESS" || assignment.role !== "OPERATIVE" || !this.secretClue || cell.ownerId !== null) return { correct: false };
    const identity = String(this.answers[move.row]![move.col]);
    cell.ownerId = playerId;
    cell.meta.revealedColor = identity;
    if (identity === "ASSASSIN") {
      this.secretWinnerTeam = assignment.team === "RED" ? "BLUE" : "RED";
      this.completed = true;
      return { correct: true, points: 0 };
    }
    if (identity === assignment.team) {
      this.secretClue.remaining -= 1;
      if (this.secretRemainingCounts()[assignment.team] === 0) {
        this.secretWinnerTeam = assignment.team;
        this.completed = true;
      } else if (this.secretClue.remaining <= 0) this.switchSecretTeam();
      return { correct: true, points: 20 };
    }
    this.switchSecretTeam();
    return { correct: true, points: identity === "NEUTRAL" ? 0 : 10 };
  }

  private switchSecretTeam(): void {
    this.secretCurrentTeam = this.secretCurrentTeam === "RED" ? "BLUE" : "RED";
    this.secretClue = null;
  }

  private createSecretBotMove(playerId?: string): GenericMove | null {
    if (!playerId) return null;
    const assignment = this.secretAssignments.get(playerId);
    if (!assignment || assignment.team !== this.secretCurrentTeam) return null;
    if (assignment.role === "CAPTAIN" && !this.secretClue) {
      return { requestId: `secret-bot-${randomUUID()}`, row: 0, col: 0, val: { action: "CLUE", clue: "IDEA", count: 1 } };
    }
    if (assignment.role !== "OPERATIVE" || !this.secretClue) return null;
    for (let row = 0; row < 5; row += 1) for (let col = 0; col < 5; col += 1) {
      if (this.board[row]![col]!.ownerId === null && this.answers[row]![col] === assignment.team) {
        return { requestId: `secret-bot-${randomUUID()}`, row, col, val: { action: "GUESS" } };
      }
    }
    return null;
  }

  private createCrossLettersBotMove(accuracy: number, playerId?: string): GenericMove | null {
    const id = playerId ?? this.activePlayerId;
    if (!id) return null;
    const word = this.suggestedWords.get(id) ?? "ARENA";
    const correctWord = Math.random() <= accuracy ? word : `${word}X`;
    const empty = !this.board.some((row) => row.some((cell) => cell.value !== null));
    if (empty) return { requestId: `letters-bot-${randomUUID()}`, row: 7, col: Math.max(0, 7 - Math.floor(word.length / 2)), val: { word: correctWord, direction: "H" } };
    for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
      const letter = String(this.board[row]![col]!.value ?? "");
      if (!letter) continue;
      const index = word.indexOf(letter);
      if (index < 0) continue;
      const start = row - index;
      if (start >= 0 && start + word.length <= 15) {
        return { requestId: `letters-bot-${randomUUID()}`, row: start, col, val: { word: correctWord, direction: "V" } };
      }
    }
    return null;
  }

  private unresolvedWordPlacements(): WordPlacement[] {
    const found = new Set(this.meta.foundWords as string[]);
    return (this.meta.placements as WordPlacement[]).filter((placement) => !found.has(placement.word));
  }

  private validateEnvelope(move: GenericMove): string | null {
    if (!move || typeof move.requestId !== "string" || move.requestId.length < 1 || move.requestId.length > 100) return "requestId inválido";
    if (!Number.isInteger(move.row) || !Number.isInteger(move.col)) return "Coordenadas inválidas";
    if (!this.board[move.row]?.[move.col]) return "Movimiento fuera del tablero";
    return null;
  }

  private reject(requestId: string, code: NonNullable<GenericMoveResult["code"]>, message: string): GenericMoveResult {
    return { accepted: false, requestId, code, message, points: 0, penaltyMs: 0, completed: this.completed };
  }
}

function normalizeValue(value: unknown, expected: CellValue): CellValue {
  if (typeof expected === "number") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  if (typeof expected === "boolean") return value === true || value === "true" || value === "BLOCK" || value === "FILL";
  return typeof value === "string" ? value.toUpperCase() : null;
}

function pieceFromCell(cell: GenericCell): Piece | null {
  const type = String(cell.meta.type ?? "");
  const team = String(cell.meta.team ?? "");
  if (!["PAWN", "KNIGHT", "BISHOP", "ROOK", "QUEEN", "KING"].includes(type) || !["BLUE", "RED"].includes(team)) return null;
  return {
    id: String(cell.meta.pieceId ?? ""),
    team: team as Piece["team"],
    owner: team as Piece["team"],
    type: type as Piece["type"],
    hp: Number(cell.meta.hp ?? 0),
    maxHp: Number(cell.meta.maxHp ?? 1),
    ap: Number(cell.meta.ap ?? 0),
    maxAp: Number(cell.meta.maxAp ?? 0),
    defense: Number(cell.meta.defense ?? 0),
    statusEffects: Array.isArray(cell.meta.statusEffects) ? cell.meta.statusEffects.map(String) : [],
    currentCooldown: Number(cell.meta.currentCooldown ?? 0),
    isShielded: cell.meta.isShielded === true,
    hasEvasion: cell.meta.hasEvasion !== false,
    canActThisTurn: cell.meta.canActThisTurn === true,
    hasMoved: cell.meta.hasMoved === true,
    ambushTarget: typeof cell.meta.ambushTarget === "string" && cell.meta.ambushTarget.includes(":")
      ? (() => {
        const [row, col] = cell.meta.ambushTarget.split(":").map(Number);
        return Number.isInteger(row) && Number.isInteger(col) ? { row: row!, col: col! } : null;
      })()
      : null,
  };
}

function writePiece(cell: GenericCell, piece: Piece, ownerId: string | null): void {
  cell.value = piece.type;
  cell.isRevealed = true;
  cell.ownerId = ownerId;
  cell.meta = {
    pieceId: piece.id,
    team: piece.team,
    owner: piece.owner,
    type: piece.type,
    hp: piece.hp,
    maxHp: piece.maxHp,
    ap: piece.ap,
    maxAp: piece.maxAp,
    defense: piece.defense,
    statusEffects: piece.statusEffects,
    currentCooldown: piece.currentCooldown,
    isShielded: piece.isShielded,
    hasEvasion: piece.hasEvasion,
    canActThisTurn: piece.canActThisTurn,
    hasMoved: piece.hasMoved,
    ambushTarget: piece.ambushTarget ? `${piece.ambushTarget.row}:${piece.ambushTarget.col}` : null,
  };
}

function clearPiece(cell: GenericCell): void {
  cell.value = null;
  cell.isRevealed = false;
  cell.ownerId = null;
  cell.meta = {};
}

function oppositeSide(side: string): string {
  return ({ top: "bottom", right: "left", bottom: "top", left: "right" } as Record<string, string>)[side] ?? "";
}

function matrixOf<T>(rows: number, columns: number, value: T): T[][] {
  return Array.from({ length: rows }, () => Array.from({ length: columns }, () => value));
}

function rectanglesIntersect(
  first: { x: number; y: number; width: number; height: number },
  second: { x: number; y: number; width: number; height: number },
): boolean {
  return first.x < second.x + second.width
    && first.x + first.width > second.x
    && first.y < second.y + second.height
    && first.y + first.height > second.y;
}

function boxesIntersect3d(
  first: { x: number; y: number; z: number; width: number; height: number; depth: number },
  second: { x: number; y: number; z: number; width: number; height: number; depth: number },
): boolean {
  return rectanglesIntersect(first, second)
    && first.z < second.z + second.depth
    && first.z + first.depth > second.z;
}

function cellIsTower(cell: GenericCell): boolean {
  return typeof cell.meta.towerType === "string";
}

function towerEnemyPosition(
  progress: number,
  path: Array<{ row: number; col: number }>,
): { row: number; col: number } {
  const start = Math.max(0, Math.min(path.length - 1, Math.floor(progress)));
  const end = Math.min(path.length - 1, start + 1);
  const fraction = Math.max(0, Math.min(1, progress - start));
  return {
    row: path[start]!.row + (path[end]!.row - path[start]!.row) * fraction,
    col: path[start]!.col + (path[end]!.col - path[start]!.col) * fraction,
  };
}

function neighbourFor(row: number, col: number, side: string, rows: number, columns: number): { row: number; col: number } | null {
  const target = side === "top" ? { row: row - 1, col }
    : side === "right" ? { row, col: col + 1 }
      : side === "bottom" ? { row: row + 1, col }
        : side === "left" ? { row, col: col - 1 }
          : null;
  return target && target.row >= 0 && target.row < rows && target.col >= 0 && target.col < columns ? target : null;
}

const SPANISH_WORDS = new Set(SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word)));
const LETTER_BAG = [..."AAAAAAAAAAAAEEEEEEEEEEEEOOOOOOOOOOSSSSSSNNNNNRRRRRIIIIILLTTTTCCCCUDPMG B F V Y Q H Z J Ñ X".replace(/\s/g, "")];

function normalizeSpanishWord(value: string): string {
  return value.trim().toUpperCase().replace(/Ñ/g, "#").normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/#/g, "Ñ").replace(/[^A-ZÑ]/g, "");
}

function readOrthogonalWord(board: string[][], row: number, col: number, axis: "H" | "V"): string {
  const dy = axis === "V" ? 1 : 0;
  const dx = axis === "H" ? 1 : 0;
  let startRow = row; let startCol = col;
  while (board[startRow - dy]?.[startCol - dx]) {
    startRow -= dy; startCol -= dx;
  }
  let result = "";
  for (let y = startRow, x = startCol; board[y]?.[x]; y += dy, x += dx) result += board[y]![x]!;
  return result;
}

function shuffleLetters(letters: string[]): string[] {
  for (let index = letters.length - 1; index > 0; index -= 1) {
    const target = Math.floor(Math.random() * (index + 1));
    [letters[index], letters[target]] = [letters[target]!, letters[index]!];
  }
  return letters;
}

function findWordForRack(rack: string[]): string | null {
  return SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word))
    .filter((word) => word.length >= 2 && word.length <= rack.length)
    .find((word) => {
      const available = [...rack];
      return [...word].every((letter) => {
        const index = available.indexOf(letter);
        if (index < 0) return false;
        available.splice(index, 1);
        return true;
      });
    }) ?? null;
}

function findAnchoredWordForRack(rack: string[], anchor: string): string | null {
  const anchors = new Set(anchor);
  return SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word))
    .filter((word) => word.length >= 2 && word.length <= rack.length && [...word].some((letter) => anchors.has(letter)))
    .find((word) => {
      const available = [...rack];
      return [...word].every((letter) => {
        const index = available.indexOf(letter);
        if (index < 0) return false;
        available.splice(index, 1);
        return true;
      });
    }) ?? null;
}
