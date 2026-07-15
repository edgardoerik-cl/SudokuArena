import { randomUUID } from "node:crypto";
import type { ArenaGame } from "../game.js";
import { createPuzzleBlueprint } from "./blueprints.js";
import { SCRABBLE_SCORES } from "./blueprints.js";
import { SPANISH_DICTIONARY } from "./spanishDictionary.js";
import type {
  CellValue,
  GameType,
  GenericBoardState,
  GenericCell,
  GenericMove,
  GenericMoveResult
} from "./types.js";
import type { PuzzleGenerationOptions } from "./types.js";

const GENERIC_HIT_POINTS = 10;
const GENERIC_ENERGY = 25;

interface WordPlacement {
  word: string;
  startRow: number;
  startCol: number;
  endRow: number;
  endCol: number;
  rowStep: number;
  colStep: number;
}

/**
 * Motor matricial autoritativo. Las reglas que no son naturalmente matriciales
 * (palabras, aristas y grupos de Rummikub) se codifican en `val` y `cell.meta`.
 */
export class GenericPuzzleEngine {
  private board: GenericCell[][];
  private readonly answers: CellValue[][];
  private readonly meta: Record<string, unknown>;
  private revision = 0;
  private completed = false;
  private readonly processed = new Map<string, Set<string>>();
  private readonly racks = new Map<string, string[]>();
  private readonly suggestedWords = new Map<string, string>();
  private turnOrder: string[] = [];
  private activePlayerId: string | null = null;
  private turnEndsAt = 0;

  constructor(readonly gameType: GameType, readonly gameId: string, options: PuzzleGenerationOptions = {}) {
    const blueprint = createPuzzleBlueprint(gameType, options);
    this.board = blueprint.board;
    this.answers = blueprint.answers;
    this.meta = blueprint.meta;
  }

  snapshot(game: ArenaGame, now = Date.now()): GenericBoardState {
    if (this.gameType === "CROSS_LETTERS") this.syncLetterPlayers(game, now);
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
          activePlayerId: this.activePlayerId,
          turnEndsAt: this.turnEndsAt,
          rackCounts: Object.fromEntries([...this.racks].map(([id, rack]) => [id, rack.length]))
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

    if (this.gameType === "CROSS_LETTERS") {
      this.syncLetterPlayers(game, now);
      if (this.activePlayerId !== playerId) return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno");
    }

    const cell = this.board[move.row]![move.col]!;
    if (cell.isBlocked || (cell.ownerId !== null && !["SLITHERLINK", "NURIKABE", "CROSS_LETTERS", "WORD_SEARCH"].includes(this.gameType))) {
      return this.reject(move.requestId, "CELL_LOCKED", "Casilla ya resuelta");
    }

    const outcome = this.applySpecificMove(playerId, move, cell);
    if (!outcome.correct) {
      const penaltyMs = this.gameType === "MINESWEEPER" && outcome.hitMine ? 5_000 : 3_000;
      game.applyGenericPenalty(playerId, now + penaltyMs);
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
    if (this.gameType === "CROSS_LETTERS") this.advanceLetterTurn(now);
    this.revision += 1;
    return {
      accepted: true,
      requestId: move.requestId,
      message: this.completed ? "Puzzle completado" : "Movimiento aceptado",
      points,
      penaltyMs: 0,
      completed: this.completed
    };
  }

  /** Produce una intención; siempre vuelve a pasar por `makeMove`. */
  createBotMove(accuracy: number, playerId?: string): GenericMove | null {
    if (this.gameType === "CROSS_LETTERS") return this.createCrossLettersBotMove(accuracy, playerId);
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
        if ((cell.ownerId === null || this.gameType === "SLITHERLINK") && !cell.isBlocked) {
          candidates.push({ row, col });
        }
      }
    }
    if (candidates.length === 0) return null;
    const correct = Math.random() <= accuracy;
    let suitable = this.gameType === "MINESWEEPER"
      ? candidates.filter(({ row, col }) => (this.answers[row]![col] === true) !== correct)
      : candidates;
    if (["NONOGRAM", "HITORI", "BRIDGES"].includes(this.gameType) && correct) {
      suitable = candidates.filter(({ row, col }) => this.answers[row]![col] === true);
    }
    if (this.gameType === "SLITHERLINK") {
      suitable = candidates.filter(({ row, col }) => this.missingSlitherEdges(row, col).length > 0);
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
    if (!this.board[row]?.[col] || this.board[row]![col]!.ownerId !== null) return null;
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
    } else if (["NONOGRAM", "HITORI", "NURIKABE", "BRIDGES"].includes(this.gameType) && this.answers[row]![col] !== true) {
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
    cell: GenericCell
  ): { correct: boolean; hitMine?: boolean; points?: number } {
    if (this.gameType === "CROSS_LETTERS") return this.applyCrossLettersMove(playerId, move);

    if (this.gameType === "NURIKABE") {
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
      const neighbour = this.mirrorDotsEdge(move.row, move.col, side);
      const closed = ["top", "right", "bottom", "left"].every((edge) => cell.meta[edge] === true);
      if (closed) { cell.ownerId = playerId; cell.isRevealed = true; }
      const neighbourClosed = neighbour !== null && ["top", "right", "bottom", "left"].every((edge) => neighbour.meta[edge] === true);
      if (neighbourClosed && neighbour) { neighbour.ownerId = playerId; neighbour.isRevealed = true; }
      return { correct: true, points: (closed ? 50 : 0) + (neighbourClosed ? 50 : 0) || 5 };
    }

    if (this.gameType === "SLITHERLINK") {
      let row = move.row; let col = move.col;
      let side = String(move.val ?? "").toLowerCase();
      let target = cell;
      let expected = String(this.answers[row]![col] ?? "").split("|").filter(Boolean);
      if (!expected.includes(side)) {
        const neighbour = neighbourFor(row, col, side, this.board.length, this.board[0]!.length);
        if (!neighbour) return { correct: false };
        const opposite = oppositeSide(side);
        const neighbourExpected = String(this.answers[neighbour.row]![neighbour.col] ?? "").split("|").filter(Boolean);
        if (!neighbourExpected.includes(opposite)) return { correct: false };
        row = neighbour.row; col = neighbour.col; side = opposite;
        target = this.board[row]![col]!; expected = neighbourExpected;
      }
      if (target.meta[side] === true) return { correct: false };
      target.meta[side] = true;
      this.mirrorDotsEdge(row, col, side);
      if (expected.every((edge) => target.meta[edge] === true)) {
        target.ownerId = playerId;
        target.isRevealed = true;
      }
      return { correct: true, points: 8 };
    }

    if (this.gameType === "RUMMIKUB") {
      const payload = typeof move.val === "object" && move.val !== null ? move.val as Record<string, unknown> : { tile: move.val };
      const tile = Number(payload.tile);
      const operation = String(payload.operation ?? "PLACE").toUpperCase();
      if (!Number.isInteger(tile) || tile < 1 || tile > 13 || !["PLACE", "MOVE", "GROUP", "RUN"].includes(operation)) {
        return { correct: false };
      }
      if (operation === "PLACE" && tile !== this.answers[move.row]![move.col]) return { correct: false };
      cell.value = tile;
      cell.ownerId = playerId;
      cell.isRevealed = true;
      cell.meta.lastOperation = operation;
      return { correct: true, points: operation === "PLACE" ? 10 : 5 };
    }

    const expected = this.answers[move.row]![move.col]!;
    const normalized = normalizeValue(move.val, expected);
    if (normalized !== expected) return { correct: false };
    cell.value = this.gameType === "HITORI" ? this.board[move.row]![move.col]!.value : expected;
    cell.isBlocked = this.gameType === "HITORI";
    cell.isRevealed = true;
    cell.ownerId = playerId;
    return { correct: true };
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
    if (this.gameType === "SLITHERLINK") return this.missingSlitherEdges(row, col)[0] ?? "top";
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
    if (this.gameType === "MINESWEEPER") {
      return this.board.every((row, y) => row.every((cell, x) => this.answers[y]![x] === true || cell.ownerId !== null));
    }
    if (this.gameType === "WORD_SEARCH") return (this.meta.foundWords as string[]).length === (this.meta.words as string[]).length;
    if (this.gameType === "DOTS_AND_BOXES") return this.board.every((row) => row.every((cell) => cell.ownerId !== null));
    if (this.gameType === "NURIKABE") {
      return this.board.every((row, y) => row.every((cell, x) => cell.meta.islandClue === true || cell.value === (this.answers[y]![x] === true ? "RIVER" : "ISLAND")));
    }
    if (["NONOGRAM", "HITORI", "BRIDGES"].includes(this.gameType)) {
      return this.board.every((row, y) => row.every((cell, x) => this.answers[y]![x] !== true || cell.ownerId !== null));
    }
    if (this.gameType === "SLITHERLINK") {
      return this.board.every((row, y) => row.every((cell, x) => {
        const expected = String(this.answers[y]![x] ?? "").split("|").filter(Boolean);
        return expected.every((edge) => cell.meta[edge] === true);
      }));
    }
    if (this.gameType === "CROSS_LETTERS") return false;
    return this.board.every((row, y) => row.every((cell, x) => this.answers[y]![x] === null || cell.isBlocked || cell.ownerId !== null));
  }

  private syncLetterPlayers(game: ArenaGame, now: number): void {
    const ids = game.snapshot(now).players.map((player) => player.id);
    this.turnOrder = ids;
    for (const id of ids) this.ensureRack(id);
    for (const id of [...this.racks.keys()]) if (!ids.includes(id)) this.racks.delete(id);
    if (!this.activePlayerId || !ids.includes(this.activePlayerId) || now >= this.turnEndsAt) {
      const previous = this.activePlayerId ? ids.indexOf(this.activePlayerId) : -1;
      this.activePlayerId = ids.length ? ids[(previous + 1) % ids.length]! : null;
      this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
    }
  }

  private ensureRack(playerId: string): void {
    if (this.racks.has(playerId)) return;
    const candidates = SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word)).filter((word) => word.length >= 3 && word.length <= 7);
    const suggested = candidates[Math.floor(Math.random() * candidates.length)] ?? "ARENA";
    this.suggestedWords.set(playerId, suggested);
    const rack = [...suggested];
    while (rack.length < 7) rack.push(randomLetter());
    this.racks.set(playerId, rack.slice(0, 7));
  }

  private refillRack(playerId: string): void {
    const candidates = SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word)).filter((word) => word.length >= 3 && word.length <= 7);
    const suggested = candidates[Math.floor(Math.random() * candidates.length)] ?? "LOGICA";
    this.suggestedWords.set(playerId, suggested);
    const rack = this.racks.get(playerId) ?? [];
    for (const letter of suggested) if (rack.length < 7) rack.push(letter);
    while (rack.length < 7) rack.push(randomLetter());
    this.racks.set(playerId, rack);
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
    if (!SPANISH_WORDS.has(word) || !["H", "V"].includes(direction) || word.length < 2) return { correct: false };
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
      }
    }
    this.racks.set(playerId, rack);
    this.refillRack(playerId);
    return { correct: true, points: points * wordMultiplier + (newTiles.length === 7 ? 50 : 0) };
  }

  private createCrossLettersBotMove(accuracy: number, playerId?: string): GenericMove | null {
    const id = playerId ?? this.activePlayerId;
    if (!id || this.activePlayerId !== id) return null;
    const word = this.suggestedWords.get(id) ?? "ARENA";
    const correctWord = Math.random() <= accuracy ? word : `${word}X`;
    const empty = !this.board.some((row) => row.some((cell) => cell.value !== null));
    if (empty) return { requestId: `letters-bot-${randomUUID()}`, row: 7, col: Math.max(0, 7 - Math.floor(word.length / 2)), val: { word: correctWord, direction: "H" } };
    for (let row = 0; row < this.board.length; row += 1) for (let col = 0; col < this.board[row]!.length; col += 1) {
      const letter = String(this.board[row]![col]!.value ?? "");
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

  private missingSlitherEdges(row: number, col: number): string[] {
    const expected = String(this.answers[row]![col] ?? "").split("|").filter(Boolean);
    return expected.filter((edge) => this.board[row]![col]!.meta[edge] !== true);
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

function oppositeSide(side: string): string {
  return ({ top: "bottom", right: "left", bottom: "top", left: "right" } as Record<string, string>)[side] ?? "";
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

function randomLetter(): string {
  return LETTER_BAG[Math.floor(Math.random() * LETTER_BAG.length)] ?? "A";
}
