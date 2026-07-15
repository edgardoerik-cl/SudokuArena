import { randomUUID } from "node:crypto";
import { createPuzzleBlueprint } from "./blueprints.js";
const GENERIC_HIT_POINTS = 10;
const GENERIC_ENERGY = 25;
/**
 * Motor matricial autoritativo. Las reglas que no son naturalmente matriciales
 * (palabras, aristas y grupos de Rummikub) se codifican en `val` y `cell.meta`.
 */
export class GenericPuzzleEngine {
    gameType;
    gameId;
    board;
    answers;
    meta;
    revision = 0;
    completed = false;
    processed = new Map();
    constructor(gameType, gameId) {
        this.gameType = gameType;
        this.gameId = gameId;
        const blueprint = createPuzzleBlueprint(gameType);
        this.board = blueprint.board;
        this.answers = blueprint.answers;
        this.meta = blueprint.meta;
    }
    snapshot(game, now = Date.now()) {
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
            meta: structuredClone(this.meta)
        };
    }
    makeMove(playerId, move, game, now = Date.now(), options = {}) {
        const invalid = this.validateEnvelope(move);
        if (invalid)
            return this.reject(move?.requestId ?? "", "INVALID_MOVE", invalid);
        if (this.completed)
            return this.reject(move.requestId, "FINISHED", "El puzzle ya terminó");
        if (!game.canPlayerAct(playerId, now)) {
            return this.reject(move.requestId, "PLAYER_BLOCKED", "Jugador temporalmente bloqueado");
        }
        const requests = this.processed.get(playerId) ?? new Set();
        this.processed.set(playerId, requests);
        if (requests.has(move.requestId))
            return this.reject(move.requestId, "DUPLICATE", "Jugada duplicada");
        requests.add(move.requestId);
        const cell = this.board[move.row][move.col];
        if (cell.isBlocked || cell.ownerId !== null) {
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
        game.applyGenericSuccess(playerId, points, options.rewardEnergy === false ? 0 : GENERIC_ENERGY, now);
        this.completed = this.isPuzzleComplete();
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
    createBotMove(accuracy) {
        const candidates = [];
        for (let row = 0; row < this.board.length; row += 1) {
            for (let col = 0; col < this.board[row].length; col += 1) {
                const cell = this.board[row][col];
                if (cell.ownerId === null && !cell.isBlocked) {
                    if (this.gameType !== "WORD_SEARCH" || col === 0)
                        candidates.push({ row, col });
                }
            }
        }
        if (candidates.length === 0)
            return null;
        const correct = Math.random() <= accuracy;
        let suitable = this.gameType === "MINESWEEPER"
            ? candidates.filter(({ row, col }) => (this.answers[row][col] === true) !== correct)
            : candidates;
        if ((this.gameType === "NONOGRAM" || this.gameType === "HITORI") && correct) {
            suitable = candidates.filter(({ row, col }) => this.answers[row][col] === true);
        }
        if (this.gameType === "DOTS_AND_BOXES" && correct) {
            const edgeCount = ({ row, col }) => ["top", "right", "bottom", "left"].filter((side) => this.board[row][col].meta[side] === true).length;
            const closing = candidates.filter((candidate) => edgeCount(candidate) === 3);
            const safe = candidates.filter((candidate) => edgeCount(candidate) <= 1);
            suitable = closing.length > 0 ? closing : safe.length > 0 ? safe : candidates;
        }
        const pool = suitable.length > 0 ? suitable : candidates;
        const target = pool[Math.floor(Math.random() * pool.length)];
        return {
            requestId: `generic-bot-${randomUUID()}`,
            row: target.row,
            col: target.col,
            val: this.botValue(target.row, target.col, correct)
        };
    }
    revealMove(row, col) {
        if (!this.board[row]?.[col] || this.board[row][col].ownerId !== null)
            return null;
        let target = { row, col };
        if (this.gameType === "MINESWEEPER" && this.answers[row][col] === true) {
            const safe = this.findFirstSafeCell();
            if (!safe)
                return null;
            target = safe;
        }
        else if (this.gameType === "WORD_SEARCH") {
            target = { row: Math.min(row, this.meta.words.length - 1), col: 0 };
            if (this.board[target.row][0].ownerId !== null) {
                const nextWord = this.meta.words.findIndex((_, index) => this.board[index][0].ownerId === null);
                if (nextWord < 0)
                    return null;
                target = { row: nextWord, col: 0 };
            }
        }
        else if ((this.gameType === "NONOGRAM" || this.gameType === "HITORI") && this.answers[row][col] !== true) {
            const unresolved = this.findFirstTrueCell();
            if (!unresolved)
                return null;
            target = unresolved;
        }
        return {
            requestId: `generic-reveal-${randomUUID()}`,
            row: target.row,
            col: target.col,
            val: this.botValue(target.row, target.col, true)
        };
    }
    applySpecificMove(playerId, move, cell) {
        if (this.gameType === "MINESWEEPER") {
            const mine = this.answers[move.row][move.col] === true;
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
            const wordValue = typeof move.val === "object" && move.val !== null && "word" in move.val
                ? move.val.word
                : move.val;
            const word = String(wordValue ?? "").toUpperCase();
            const words = this.meta.words;
            const index = words.indexOf(word);
            if (index < 0 || move.row !== index || move.col !== 0)
                return { correct: false };
            for (let col = 0; col < word.length; col += 1)
                this.board[index][col].ownerId = playerId;
            const found = this.meta.foundWords;
            if (!found.includes(word))
                found.push(word);
            return { correct: true, points: word.length * 10 };
        }
        if (this.gameType === "DOTS_AND_BOXES") {
            const side = String(move.val ?? "").toLowerCase();
            if (!["top", "right", "bottom", "left"].includes(side) || cell.meta[side] === true)
                return { correct: false };
            cell.meta[side] = true;
            const neighbour = this.mirrorDotsEdge(move.row, move.col, side);
            const closed = ["top", "right", "bottom", "left"].every((edge) => cell.meta[edge] === true);
            if (closed) {
                cell.ownerId = playerId;
                cell.isRevealed = true;
            }
            const neighbourClosed = neighbour !== null && ["top", "right", "bottom", "left"].every((edge) => neighbour.meta[edge] === true);
            if (neighbourClosed && neighbour) {
                neighbour.ownerId = playerId;
                neighbour.isRevealed = true;
            }
            return { correct: true, points: (closed ? 50 : 0) + (neighbourClosed ? 50 : 0) || 5 };
        }
        if (this.gameType === "RUMMIKUB") {
            const payload = typeof move.val === "object" && move.val !== null ? move.val : { tile: move.val };
            const tile = Number(payload.tile);
            const operation = String(payload.operation ?? "PLACE").toUpperCase();
            if (!Number.isInteger(tile) || tile < 1 || tile > 13 || !["PLACE", "MOVE", "GROUP", "RUN"].includes(operation)) {
                return { correct: false };
            }
            if (operation === "PLACE" && tile !== this.answers[move.row][move.col])
                return { correct: false };
            cell.value = tile;
            cell.ownerId = playerId;
            cell.isRevealed = true;
            cell.meta.lastOperation = operation;
            return { correct: true, points: operation === "PLACE" ? 10 : 5 };
        }
        const expected = this.answers[move.row][move.col];
        const normalized = normalizeValue(move.val, expected);
        if (normalized !== expected)
            return { correct: false };
        cell.value = this.gameType === "HITORI" ? this.board[move.row][move.col].value : expected;
        cell.isBlocked = this.gameType === "HITORI";
        cell.isRevealed = true;
        cell.ownerId = playerId;
        return { correct: true };
    }
    botValue(row, col, correct) {
        if (this.gameType === "WORD_SEARCH") {
            const word = this.meta.words[row] ?? "ERROR";
            return correct ? word : `${word}X`;
        }
        if (this.gameType === "DOTS_AND_BOXES") {
            const cell = this.board[row][col];
            const free = ["top", "right", "bottom", "left"].filter((side) => cell.meta[side] !== true);
            return free[0] ?? "top";
        }
        if (this.gameType === "MINESWEEPER") {
            const mine = this.answers[row][col] === true;
            if (correct && mine) {
                const safe = this.findFirstSafeCell();
                if (safe)
                    return this.botValue(safe.row, safe.col, true);
            }
            return "REVEAL";
        }
        const answer = this.answers[row][col];
        if (correct)
            return answer;
        if (typeof answer === "number")
            return answer % 9 + 1;
        if (typeof answer === "boolean")
            return !answer;
        return "?";
    }
    findFirstSafeCell() {
        for (let row = 0; row < this.answers.length; row += 1) {
            for (let col = 0; col < this.answers[row].length; col += 1) {
                if (this.answers[row][col] !== true && this.board[row][col].ownerId === null)
                    return { row, col };
            }
        }
        return null;
    }
    findFirstTrueCell() {
        for (let row = 0; row < this.answers.length; row += 1) {
            for (let col = 0; col < this.answers[row].length; col += 1) {
                if (this.answers[row][col] === true && this.board[row][col].ownerId === null)
                    return { row, col };
            }
        }
        return null;
    }
    adjacentMineCount(row, col) {
        let count = 0;
        for (let y = Math.max(0, row - 1); y <= Math.min(this.answers.length - 1, row + 1); y += 1) {
            for (let x = Math.max(0, col - 1); x <= Math.min(this.answers[0].length - 1, col + 1); x += 1) {
                if (this.answers[y][x] === true)
                    count += 1;
            }
        }
        return count;
    }
    mirrorDotsEdge(row, col, side) {
        const neighbour = side === "top" ? { row: row - 1, col, edge: "bottom" }
            : side === "right" ? { row, col: col + 1, edge: "left" }
                : side === "bottom" ? { row: row + 1, col, edge: "top" }
                    : { row, col: col - 1, edge: "right" };
        const adjacent = this.board[neighbour.row]?.[neighbour.col];
        if (adjacent)
            adjacent.meta[neighbour.edge] = true;
        return adjacent ?? null;
    }
    isPuzzleComplete() {
        if (this.gameType === "MINESWEEPER") {
            return this.board.every((row, y) => row.every((cell, x) => this.answers[y][x] === true || cell.ownerId !== null));
        }
        if (this.gameType === "WORD_SEARCH")
            return this.meta.foundWords.length === this.meta.words.length;
        if (this.gameType === "DOTS_AND_BOXES")
            return this.board.every((row) => row.every((cell) => cell.ownerId !== null));
        if (this.gameType === "NONOGRAM" || this.gameType === "HITORI") {
            return this.board.every((row, y) => row.every((cell, x) => this.answers[y][x] !== true || cell.ownerId !== null));
        }
        return this.board.every((row, y) => row.every((cell, x) => this.answers[y][x] === null || cell.ownerId !== null));
    }
    validateEnvelope(move) {
        if (!move || typeof move.requestId !== "string" || move.requestId.length < 1 || move.requestId.length > 100)
            return "requestId inválido";
        if (!Number.isInteger(move.row) || !Number.isInteger(move.col))
            return "Coordenadas inválidas";
        if (!this.board[move.row]?.[move.col])
            return "Movimiento fuera del tablero";
        return null;
    }
    reject(requestId, code, message) {
        return { accepted: false, requestId, code, message, points: 0, penaltyMs: 0, completed: this.completed };
    }
}
function normalizeValue(value, expected) {
    if (typeof expected === "number") {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : null;
    }
    if (typeof expected === "boolean")
        return value === true || value === "true" || value === "BLOCK" || value === "FILL";
    return typeof value === "string" ? value.toUpperCase() : null;
}
//# sourceMappingURL=engine.js.map