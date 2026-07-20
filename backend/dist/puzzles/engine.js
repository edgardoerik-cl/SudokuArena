import { randomUUID } from "node:crypto";
import { createPuzzleBlueprint } from "./blueprints.js";
import { SCRABBLE_SCORES } from "./blueprints.js";
import { SPANISH_DICTIONARY } from "./spanishDictionary.js";
import { attackRange, calculateDamage, cooldownFor, movementRange, skillCost, skillFor } from "./chessTactics.js";
const GENERIC_HIT_POINTS = 10;
const GENERIC_ENERGY = 25;
const STRICT_PLAYER_TURN_GAMES = new Set([
    "MINESWEEPER", "CROSSWORD", "DOTS_AND_BOXES", "HANGMAN",
    "TIC_TAC_TOE", "CHECKERS", "CHESS_TACTICS",
]);
/**
 * Motor matricial autoritativo. Las reglas que no son naturalmente matriciales
 * Las reglas especializadas se codifican en `val`, `cell.meta` y validadores puros.
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
    racks = new Map();
    suggestedWords = new Map();
    letterBag = shuffleLetters([...LETTER_BAG, ...LETTER_BAG]);
    turnOrder = [];
    activePlayerId = null;
    turnEndsAt = 0;
    secretAssignments = new Map();
    secretCurrentTeam = "BLUE";
    secretClue = null;
    secretWinnerTeam = null;
    capitalBalances = new Map();
    capitalPositions = new Map();
    capitalPropertyOwners = new Map();
    capitalPropertyLevels = new Map();
    capitalSkillsUsed = new Set();
    capitalStage = "ROLL";
    capitalPendingProperty = null;
    capitalDice = [1, 1];
    capitalLastMove = null;
    capitalEvent = "La economía neón está lista";
    capitalCard = null;
    hangmanGuesses = new Set();
    hangmanErrors = new Map();
    hiddenWord;
    arrowRemoved = new Map();
    memoryFirstPicks = new Map();
    mergeBotStep = 0;
    constructor(gameType, gameId, options = {}) {
        this.gameType = gameType;
        this.gameId = gameId;
        const blueprint = createPuzzleBlueprint(gameType, options);
        this.board = blueprint.board;
        this.answers = blueprint.answers;
        this.meta = blueprint.meta;
        this.hiddenWord = gameType === "HANGMAN" ? blueprint.answers[0].map(String).join("") : "";
    }
    setFirstPlayer(playerId, now = Date.now()) {
        this.activePlayerId = playerId;
        this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
    }
    snapshot(game, now = Date.now()) {
        if (this.gameType === "CROSS_LETTERS")
            this.syncLetterPlayers(game, now);
        if (STRICT_PLAYER_TURN_GAMES.has(this.gameType))
            this.syncTurnPlayers(game);
        if (this.gameType === "SECRET_CODE")
            this.syncSecretPlayers(game);
        if (this.gameType === "CAPITAL_ARENA")
            this.syncCapitalPlayers(game);
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
                    maskedWord: this.board[0].map((cell) => cell.value?.toString() ?? "_"),
                    // Compatibilidad del contrato solicitado: nunca contiene la respuesta,
                    // solo letras ya descubiertas y guiones.
                    hiddenWord: this.board[0].map((cell) => cell.value?.toString() ?? "_"),
                    wrongGuesses: [...this.hangmanGuesses].filter((letter) => !this.hiddenWord.includes(letter)),
                    eliminated: [...this.hangmanErrors].filter(([, errors]) => errors >= 6).map(([id]) => id),
                    currentPlayerTurn: this.activePlayerId,
                    ...(this.completed && this.board[0].some((cell) => cell.value === null)
                        ? { answerOnGameOver: this.hiddenWord }
                        : {}),
                } : {}),
                ...(this.gameType === "ARROWS_ESCAPE" ? {
                    progress: Object.fromEntries([...this.arrowRemoved].map(([id, removed]) => [id, removed.size])),
                    removedByPlayer: Object.fromEntries([...this.arrowRemoved].map(([id, removed]) => [id, [...removed]])),
                } : {}),
                ...(this.gameType === "MEMORY_NEON" ? {
                    pairsFound: this.board.flat().filter((cell) => cell.ownerId !== null).length / 2,
                    activePicks: Object.fromEntries([...this.memoryFirstPicks].map(([id, pick]) => [id, `${pick.row}:${pick.col}`])),
                } : {})
            }
        };
    }
    rackFor(playerId) {
        return [...(this.racks.get(playerId) ?? [])];
    }
    shuffleRack(playerId) {
        const rack = this.racks.get(playerId) ?? [];
        for (let index = rack.length - 1; index > 0; index -= 1) {
            const target = Math.floor(Math.random() * (index + 1));
            [rack[index], rack[target]] = [rack[target], rack[index]];
        }
        return [...rack];
    }
    secretStateFor(playerId) {
        if (this.gameType !== "SECRET_CODE")
            return null;
        const assignment = this.secretAssignments.get(playerId);
        if (!assignment)
            return null;
        return {
            ...assignment,
            currentTeam: this.secretCurrentTeam,
            clue: this.secretClue,
            winnerTeam: this.secretWinnerTeam,
            key: assignment.role === "CAPTAIN" ? this.answers.flat().map(String) : null
        };
    }
    secretWords() {
        return this.gameType === "SECRET_CODE" ? this.board.flat().map((cell) => String(cell.value ?? "")) : [];
    }
    secretTeamFor(playerId) {
        return this.secretAssignments.get(playerId)?.team ?? null;
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
        if (this.gameType === "CROSS_LETTERS") {
            this.syncLetterPlayers(game, now);
            if (this.activePlayerId !== playerId)
                return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno");
        }
        if (STRICT_PLAYER_TURN_GAMES.has(this.gameType)) {
            this.syncTurnPlayers(game);
            if (this.activePlayerId !== playerId)
                return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno");
        }
        if (this.gameType === "SECRET_CODE") {
            this.syncSecretPlayers(game);
            if (this.secretActivePlayerId() !== playerId)
                return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno de equipo");
        }
        if (this.gameType === "CAPITAL_ARENA") {
            this.syncCapitalPlayers(game);
            if (this.activePlayerId !== playerId)
                return this.reject(move.requestId, "INVALID_MOVE", "Espera tu turno económico");
        }
        const cell = this.board[move.row][move.col];
        if (cell.isBlocked || (cell.ownerId !== null && !["NURIKABE", "CROSS_LETTERS", "WORD_SEARCH", "CAPITAL_ARENA", "HANGMAN", "ARROWS_ESCAPE", "CHECKERS", "CHESS_TACTICS", "MERGE_2048"].includes(this.gameType))) {
            return this.reject(move.requestId, "CELL_LOCKED", "Casilla ya resuelta");
        }
        const outcome = this.applySpecificMove(playerId, move, cell, game, now);
        if (!outcome.correct && outcome.neutral === true) {
            return this.reject(move.requestId, "INVALID_MOVE", outcome.message ?? "Entrada no válida");
        }
        if (!outcome.correct) {
            const penaltyMs = this.gameType === "MINESWEEPER" && outcome.hitMine ? 5_000 : 3_000;
            game.applyGenericPenalty(playerId, now + penaltyMs);
            // En Damas una jugada ilegal no consume el turno.
            if (STRICT_PLAYER_TURN_GAMES.has(this.gameType) && this.gameType !== "CHECKERS")
                this.advanceStrictTurn();
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
        if (this.gameType === "CROSS_LETTERS")
            this.advanceLetterTurn(now);
        if (STRICT_PLAYER_TURN_GAMES.has(this.gameType) && outcome.extraTurn !== true)
            this.advanceStrictTurn();
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
    createBotMove(accuracy, playerId) {
        if (this.gameType === "CROSS_LETTERS")
            return this.createCrossLettersBotMove(accuracy, playerId);
        if (this.gameType === "SECRET_CODE")
            return this.createSecretBotMove(playerId);
        if (this.gameType === "CAPITAL_ARENA")
            return this.createCapitalBotMove(playerId);
        if (this.gameType === "MEMORY_NEON") {
            const effectivePlayerId = playerId ?? this.memoryFirstPicks.keys().next().value;
            const first = effectivePlayerId ? this.memoryFirstPicks.get(effectivePlayerId) : null;
            if (first) {
                const answer = this.answers[first.row][first.col];
                for (let row = 0; row < this.board.length; row += 1)
                    for (let col = 0; col < this.board[row].length; col += 1) {
                        if ((row !== first.row || col !== first.col) && this.board[row][col].value === null
                            && this.board[row][col].ownerId === null && this.answers[row][col] === answer) {
                            return { requestId: `memory-bot-${randomUUID()}`, row, col, val: "FLIP" };
                        }
                    }
            }
            for (let row = 0; row < this.board.length; row += 1)
                for (let col = 0; col < this.board[row].length; col += 1) {
                    if (this.board[row][col].value === null && this.board[row][col].ownerId === null) {
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
                val: directions[this.mergeBotStep++ % directions.length],
            };
        }
        if (this.gameType === "HANGMAN") {
            const target = this.board[0].findIndex((cell) => cell.value === null);
            if (target < 0)
                return null;
            return { requestId: `hangman-bot-${randomUUID()}`, row: 0, col: target, val: this.answers[0][target] };
        }
        if (this.gameType === "ARROWS_ESCAPE") {
            const removed = this.arrowRemoved.get(playerId ?? "bot") ?? new Set();
            for (let row = 0; row < this.board.length; row += 1)
                for (let col = 0; col < this.board[row].length; col += 1) {
                    const key = `${row}:${col}`;
                    if (removed.has(key))
                        continue;
                    const shapeId = String(this.board[row][col].meta.shapeId ?? key);
                    if (this.canArrowShapeEscape(shapeId, removed)) {
                        return { requestId: `arrows-bot-${randomUUID()}`, row, col, val: "ESCAPE" };
                    }
                }
            return null;
        }
        if (STRICT_PLAYER_TURN_GAMES.has(this.gameType) && playerId && this.activePlayerId !== playerId)
            return null;
        if (this.gameType === "TIC_TAC_TOE") {
            for (let row = 0; row < 3; row += 1)
                for (let col = 0; col < 3; col += 1) {
                    if (this.board[row][col].value === null) {
                        return { requestId: `gato-bot-${randomUUID()}`, row, col, val: "MARK" };
                    }
                }
            return null;
        }
        if (this.gameType === "CHECKERS" && playerId) {
            const team = this.activeTeam(playerId);
            const captures = this.checkersCapturesFor(team);
            const sources = captures.length ? captures : this.board.flatMap((row, rowIndex) => row.map((cell, colIndex) => ({ cell, row: rowIndex, col: colIndex })).filter(({ cell }) => cell.meta.team === team));
            for (const source of sources) {
                const capture = this.checkersCapturesFrom(source.row, source.col, team)[0];
                if (capture)
                    return { requestId: `checkers-bot-${randomUUID()}`, row: source.row, col: source.col, val: { targetRow: capture.row, targetCol: capture.col } };
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
            for (let row = 0; row < 8; row += 1)
                for (let col = 0; col < 8; col += 1) {
                    const piece = pieceFromCell(this.board[row][col]);
                    if (!piece || piece.team !== team)
                        continue;
                    const attack = attackRange(piece, { row, col }).find((point) => {
                        const enemy = pieceFromCell(this.board[point.row][point.col]);
                        return enemy && enemy.team !== team;
                    });
                    if (attack && piece.ap >= 2)
                        return { requestId: `chess-bot-${randomUUID()}`, row, col, val: { action: "ATTACK", targetRow: attack.row, targetCol: attack.col } };
                    const target = movementRange(piece, { row, col }).find((point) => this.board[point.row][point.col].value === null);
                    if (target)
                        return { requestId: `chess-bot-${randomUUID()}`, row, col, val: { action: "MOVE", targetRow: target.row, targetCol: target.col } };
                }
            return null;
        }
        if (this.gameType === "WORD_SEARCH") {
            const unresolved = this.unresolvedWordPlacements();
            const placement = unresolved[Math.floor(Math.random() * unresolved.length)];
            if (!placement)
                return null;
            const correct = Math.random() <= accuracy;
            return {
                requestId: `generic-bot-${randomUUID()}`,
                row: placement.startRow,
                col: placement.startCol,
                val: { word: correct ? placement.word : `${placement.word}X`, endRow: placement.endRow, endCol: placement.endCol }
            };
        }
        if (this.gameType === "NEXUS_ZERO") {
            for (let row = 0; row < this.board.length; row += 1) {
                for (let col = 0; col < this.board[row].length; col += 1) {
                    if (this.board[row][col].ownerId !== null)
                        continue;
                    const [targetRow, targetCol] = String(this.answers[row][col]).split(":").map(Number);
                    if (this.board[targetRow]?.[targetCol]?.ownerId === null) {
                        return {
                            requestId: `nexus-bot-${randomUUID()}`,
                            row,
                            col,
                            val: Math.random() <= accuracy
                                ? { targetRow, targetCol }
                                : { targetRow: row, targetCol: (col + 2) % this.board[row].length },
                        };
                    }
                }
            }
            return null;
        }
        const candidates = [];
        for (let row = 0; row < this.board.length; row += 1) {
            for (let col = 0; col < this.board[row].length; col += 1) {
                const cell = this.board[row][col];
                if (cell.ownerId === null && !cell.isBlocked) {
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
        if (["HITORI", "BRIDGES"].includes(this.gameType) && correct) {
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
        if (this.gameType === "CAPITAL_ARENA")
            return null;
        if (!this.board[row]?.[col] || this.board[row][col].ownerId !== null)
            return null;
        if (this.gameType === "NEXUS_ZERO") {
            const [targetRow, targetCol] = String(this.answers[row][col]).split(":").map(Number);
            if (!this.board[targetRow]?.[targetCol] || this.board[targetRow][targetCol].ownerId !== null)
                return null;
            return {
                requestId: `nexus-reveal-${randomUUID()}`,
                row,
                col,
                val: { targetRow, targetCol },
            };
        }
        let target = { row, col };
        if (this.gameType === "MINESWEEPER" && this.answers[row][col] === true) {
            const safe = this.findFirstSafeCell();
            if (!safe)
                return null;
            target = safe;
        }
        else if (this.gameType === "WORD_SEARCH") {
            const placement = this.unresolvedWordPlacements()[0];
            if (!placement)
                return null;
            return {
                requestId: `generic-reveal-${randomUUID()}`,
                row: placement.startRow,
                col: placement.startCol,
                val: { word: placement.word, endRow: placement.endRow, endCol: placement.endCol }
            };
        }
        else if (["HITORI", "NURIKABE", "BRIDGES"].includes(this.gameType) && this.answers[row][col] !== true) {
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
    applySpecificMove(playerId, move, cell, game, now) {
        if (this.gameType === "CROSS_LETTERS")
            return this.applyCrossLettersMove(playerId, move);
        if (this.gameType === "SECRET_CODE")
            return this.applySecretCodeMove(playerId, move, cell);
        if (this.gameType === "CAPITAL_ARENA")
            return this.applyCapitalMove(playerId, move, game);
        if (this.gameType === "MERGE_2048")
            return this.applyMerge2048Move(playerId, move);
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
            const answer = this.answers[move.row][move.col];
            const first = this.memoryFirstPicks.get(playerId);
            cell.value = answer ?? null;
            cell.isRevealed = true;
            if (!first) {
                this.memoryFirstPicks.set(playerId, { row: move.row, col: move.col });
                return { correct: true, points: 0 };
            }
            const firstCell = this.board[first.row][first.col];
            this.memoryFirstPicks.delete(playerId);
            if (this.answers[first.row][first.col] === answer) {
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
            const letter = String(move.val ?? "").trim().toUpperCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
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
                    this.board[0][col].value = letter;
                    this.board[0][col].ownerId = playerId;
                    this.board[0][col].isRevealed = true;
                    hits += 1;
                }
            });
            if (hits === 0) {
                this.hangmanErrors.set(playerId, (this.hangmanErrors.get(playerId) ?? 0) + 1);
                return { correct: true, points: 0 };
            }
            return { correct: true, points: hits * 12, extraTurn: true };
        }
        if (this.gameType === "TIC_TAC_TOE") {
            if (cell.value !== null)
                return { correct: false };
            const playerIndex = Math.max(0, this.turnOrder.indexOf(playerId));
            cell.value = playerIndex % 2 === 0 ? "X" : "O";
            cell.ownerId = playerId;
            cell.isRevealed = true;
            const winner = this.ticTacToeWinner();
            if (winner) {
                this.meta.winnerMark = winner;
                this.meta.winnerPlayerId = playerId;
                this.completed = true;
            }
            return { correct: true, points: winner ? 100 : 10 };
        }
        if (this.gameType === "CHECKERS")
            return this.applyCheckersMove(playerId, move, cell);
        if (this.gameType === "CHESS_TACTICS")
            return this.applyChessTacticsMove(playerId, move, cell);
        if (this.gameType === "ARROWS_ESCAPE") {
            const removed = this.arrowRemoved.get(playerId) ?? new Set();
            const shapeId = String(cell.meta.shapeId ?? `${move.row}:${move.col}`);
            const members = this.arrowShapeMembers(shapeId);
            if (!members.length || members.every(({ row, col }) => removed.has(`${row}:${col}`)))
                return { correct: false };
            if (!this.canArrowShapeEscape(shapeId, removed))
                return { correct: false, points: 0 };
            members.forEach(({ row, col }) => removed.add(`${row}:${col}`));
            this.arrowRemoved.set(playerId, removed);
            return { correct: true, points: 10 * members.length };
        }
        if (this.gameType === "NURIKABE") {
            const action = String(move.val ?? "").toUpperCase();
            if (!['RIVER', 'ISLAND', 'CLEAR'].includes(action) || cell.meta.islandClue === true)
                return { correct: false };
            if (action === "CLEAR") {
                cell.value = null;
                cell.ownerId = null;
                cell.isRevealed = false;
                return { correct: true, points: 0 };
            }
            const shouldBeRiver = this.answers[move.row][move.col] === true;
            cell.value = action;
            cell.ownerId = playerId;
            cell.isRevealed = true;
            return { correct: true, points: (action === "RIVER") === shouldBeRiver ? 10 : 0 };
        }
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
            const payload = typeof move.val === "object" && move.val !== null ? move.val : null;
            const wordValue = payload?.word ?? move.val;
            const word = String(wordValue ?? "").toUpperCase();
            const placement = this.meta.placements.find((candidate) => candidate.word === word && candidate.startRow === move.row && candidate.startCol === move.col &&
                (payload?.endRow == null || Number(payload.endRow) === candidate.endRow) &&
                (payload?.endCol == null || Number(payload.endCol) === candidate.endCol));
            if (!placement)
                return { correct: false };
            const found = this.meta.foundWords;
            if (found.includes(word))
                return { correct: false };
            for (let offset = 0; offset < word.length; offset += 1) {
                this.board[placement.startRow + placement.rowStep * offset][placement.startCol + placement.colStep * offset].ownerId = playerId;
            }
            if (!found.includes(word))
                found.push(word);
            return { correct: true, points: word.length * 10 };
        }
        if (this.gameType === "DOTS_AND_BOXES") {
            const side = String(move.val ?? "").toLowerCase();
            if (!["top", "right", "bottom", "left"].includes(side) || cell.meta[side] === true)
                return { correct: false };
            cell.meta[side] = true;
            cell.meta[`${side}OwnerId`] = playerId;
            const neighbour = this.mirrorDotsEdge(move.row, move.col, side);
            if (neighbour)
                neighbour.meta[`${oppositeSide(side)}OwnerId`] = playerId;
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
            const completedBoxes = Number(closed) + Number(neighbourClosed);
            return {
                correct: true,
                points: completedBoxes > 0 ? completedBoxes * 50 : 5,
                // Regla clásica: cerrar una o dos cajas conserva el turno.
                extraTurn: completedBoxes > 0,
            };
        }
        if (this.gameType === "NEXUS_ZERO") {
            const payload = typeof move.val === "object" && move.val !== null ? move.val : {};
            const targetRow = Number.parseInt(String(payload.targetRow), 10);
            const targetCol = Number.parseInt(String(payload.targetCol), 10);
            const target = this.board[targetRow]?.[targetCol];
            if (!target || target.ownerId !== null)
                return { correct: false };
            const expectedPartner = String(this.answers[move.row][move.col]) === `${targetRow}:${targetCol}`;
            const firstValue = Number.parseInt(String(cell.value), 10);
            const secondValue = Number.parseInt(String(target.value), 10);
            if (!Number.isInteger(firstValue) || !Number.isInteger(secondValue))
                return { correct: false };
            if (!expectedPartner || firstValue + secondValue !== 0)
                return { correct: false };
            cell.ownerId = playerId;
            target.ownerId = playerId;
            cell.isRevealed = true;
            target.isRevealed = true;
            return { correct: true, points: 24 };
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
    ticTacToeWinner() {
        const lines = [
            [[0, 0], [0, 1], [0, 2]], [[1, 0], [1, 1], [1, 2]], [[2, 0], [2, 1], [2, 2]],
            [[0, 0], [1, 0], [2, 0]], [[0, 1], [1, 1], [2, 1]], [[0, 2], [1, 2], [2, 2]],
            [[0, 0], [1, 1], [2, 2]], [[0, 2], [1, 1], [2, 0]],
        ];
        for (const line of lines) {
            const values = line.map(([row, col]) => String(this.board[row][col].value ?? ""));
            if (values[0] && values.every((value) => value === values[0]))
                return values[0];
        }
        return null;
    }
    applyMerge2048Move(playerId, move) {
        const direction = String(move.val ?? "").toUpperCase();
        if (!["UP", "RIGHT", "DOWN", "LEFT"].includes(direction)) {
            return { correct: false, neutral: true, message: "Dirección inválida" };
        }
        const previous = this.board.map((row) => row.map((cell) => Number(cell.value ?? 0)));
        const next = previous.map((row) => [...row]);
        let points = 0;
        const slide = (line) => {
            const compact = line.filter((value) => value > 0);
            const result = [];
            for (let index = 0; index < compact.length; index += 1) {
                if (compact[index] === compact[index + 1]) {
                    const merged = compact[index] * 2;
                    result.push(merged);
                    points += merged;
                    index += 1;
                }
                else
                    result.push(compact[index]);
            }
            while (result.length < 4)
                result.push(0);
            return result;
        };
        if (direction === "LEFT" || direction === "RIGHT") {
            for (let row = 0; row < 4; row += 1) {
                const source = direction === "RIGHT" ? [...previous[row]].reverse() : previous[row];
                const result = slide(source);
                next[row] = direction === "RIGHT" ? result.reverse() : result;
            }
        }
        else {
            for (let col = 0; col < 4; col += 1) {
                const source = Array.from({ length: 4 }, (_, row) => previous[row][col]);
                if (direction === "DOWN")
                    source.reverse();
                const result = slide(source);
                if (direction === "DOWN")
                    result.reverse();
                result.forEach((value, row) => { next[row][col] = value; });
            }
        }
        if (next.every((row, y) => row.every((value, x) => value === previous[y][x]))) {
            return { correct: false, neutral: true, message: "Ese deslizamiento no mueve fichas" };
        }
        this.board.forEach((row, y) => row.forEach((cell, x) => {
            const value = next[y][x];
            cell.value = value === 0 ? null : value;
            cell.isRevealed = value !== 0;
            cell.ownerId = value !== 0 && value !== previous[y][x] ? playerId : null;
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
    activeTeam(playerId) {
        return Math.max(0, this.turnOrder.indexOf(playerId)) % 2 === 0 ? "BLUE" : "RED";
    }
    applyCheckersMove(playerId, move, source) {
        const payload = typeof move.val === "object" && move.val !== null ? move.val : {};
        let targetRow = Number(payload.targetRow);
        let targetCol = Number(payload.targetCol);
        const target = this.board[targetRow]?.[targetCol];
        const team = this.activeTeam(playerId);
        if (this.meta.forcedPiece != null && this.meta.forcedPiece !== `${move.row}:${move.col}`)
            return { correct: false };
        if (!target || target.isBlocked || target.value !== null || source.meta.team !== team)
            return { correct: false };
        const king = source.meta.king === true;
        const dy = targetRow - move.row;
        const dx = targetCol - move.col;
        if (Math.abs(dy) !== Math.abs(dx) || dy === 0)
            return { correct: false };
        const direction = team === "BLUE" ? 1 : -1;
        const captures = this.checkersCapturesFor(team);
        const mustCapture = captures.length > 0;
        let captured = null;
        if (!king) {
            if (Math.abs(dy) === 1 && dy === direction && !mustCapture) {
                // Movimiento simple válido.
            }
            else if (Math.abs(dy) === 2) {
                const middle = this.board[move.row + dy / 2][move.col + dx / 2];
                if (middle.meta.team == null || middle.meta.team === team)
                    return { correct: false };
                captured = middle;
            }
            else
                return { correct: false };
        }
        else {
            let enemies = 0;
            for (let step = 1; step < Math.abs(dy); step += 1) {
                const traversed = this.board[move.row + Math.sign(dy) * step][move.col + Math.sign(dx) * step];
                if (traversed.value === null)
                    continue;
                if (traversed.meta.team === team || ++enemies > 1)
                    return { correct: false };
                captured = traversed;
            }
            if (mustCapture && !captured)
                return { correct: false };
        }
        if (mustCapture && !captured)
            return { correct: false };
        target.value = source.value;
        target.isRevealed = true;
        target.ownerId = playerId;
        target.meta = { ...source.meta };
        source.value = null;
        source.isRevealed = false;
        source.ownerId = null;
        source.meta = { playable: true, team: null, king: false };
        if (captured) {
            captured.value = null;
            captured.isRevealed = false;
            captured.ownerId = null;
            captured.meta = { playable: true, team: null, king: false };
        }
        if ((team === "BLUE" && targetRow === 7) || (team === "RED" && targetRow === 0)) {
            target.meta.king = true;
            target.value = `${team}_KING`;
        }
        const extraTurn = captured !== null && this.checkersCapturesFrom(targetRow, targetCol, team).length > 0;
        if (extraTurn)
            this.meta.forcedPiece = `${targetRow}:${targetCol}`;
        else
            delete this.meta.forcedPiece;
        return { correct: true, points: captured ? 35 : 5, extraTurn };
    }
    checkersCapturesFor(team) {
        const result = [];
        this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
            if (cell.meta.team === team && this.checkersCapturesFrom(rowIndex, colIndex, team).length) {
                result.push({ row: rowIndex, col: colIndex });
            }
        }));
        return result;
    }
    checkersCapturesFrom(row, col, team) {
        const result = [];
        for (const dy of [-1, 1])
            for (const dx of [-1, 1]) {
                const middle = this.board[row + dy]?.[col + dx];
                const landing = this.board[row + dy * 2]?.[col + dx * 2];
                if (middle?.meta.team != null && middle.meta.team !== team && landing && !landing.isBlocked && landing.value === null) {
                    result.push({ row: row + dy * 2, col: col + dx * 2 });
                }
            }
        return result;
    }
    applyChessTacticsMove(playerId, move, source) {
        const payload = typeof move.val === "object" && move.val !== null ? move.val : {};
        const action = String(payload.action ?? "MOVE").toUpperCase();
        const targetRow = Number(payload.targetRow);
        const targetCol = Number(payload.targetCol);
        const target = this.board[targetRow]?.[targetCol];
        const piece = pieceFromCell(source);
        if (!piece || piece.team !== this.activeTeam(playerId) || !target)
            return { correct: false };
        if (piece.statusEffects.some((effect) => effect.toUpperCase() === "STUNNED"))
            return { correct: false };
        const origin = { row: move.row, col: move.col };
        if (action === "MOVE" || action === "ATTACK") {
            const ambusher = this.board.flatMap((row, rowIndex) => row.map((cell, colIndex) => ({
                cell,
                row: rowIndex,
                col: colIndex,
                piece: pieceFromCell(cell),
            }))).find((entry) => entry.piece?.team !== piece.team
                && entry.piece?.type === "KNIGHT"
                && entry.piece.ambushTarget?.row === targetRow
                && entry.piece.ambushTarget?.col === targetCol);
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
            if (enemy && enemy.team === piece.team)
                return { correct: false };
            if (enemy?.statusEffects.some((effect) => effect.toUpperCase() === "INVULNERABLE"))
                return { correct: false };
            if (enemy?.isShielded && !["PAWN", "KNIGHT", "BISHOP"].includes(piece.type))
                return { correct: false };
            if (piece.ap < 1)
                return { correct: false };
            if (enemy) {
                enemy.hp -= calculateDamage(piece.type === "QUEEN" ? 48 : piece.type === "ROOK" ? 42 : piece.type === "KNIGHT" ? 36 : 30, enemy.defense);
                if (enemy.hp <= 0) {
                    if (enemy.type === "KING") {
                        this.meta.winnerTeam = piece.team;
                        this.completed = true;
                    }
                    clearPiece(target);
                }
                else
                    writePiece(target, enemy, target.ownerId);
            }
            else {
                writePiece(target, piece, playerId);
                clearPiece(source);
            }
            piece.ap -= 1;
            if (enemy)
                writePiece(source, piece, playerId);
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
            return { correct: true, points: enemy ? (enemy.hp <= 0 ? 45 : 18) : 4 };
        }
        if (action !== "SKILL")
            return { correct: false };
        const skill = skillFor(piece);
        const cost = skillCost(skill);
        if (piece.ap < cost || piece.currentCooldown > 0)
            return { correct: false };
        if (skill === "FORCED_MARCH") {
            const direction = piece.team === "BLUE" ? 1 : -1;
            if (targetRow !== move.row + direction || targetCol !== move.col || target.value !== null)
                return { correct: false };
            writePiece(target, piece, playerId);
            clearPiece(source);
            const support = this.board[move.row - direction]?.[move.col];
            const ally = support ? pieceFromCell(support) : null;
            if (support && ally && ally.team === piece.team && ["KNIGHT", "BISHOP"].includes(ally.type)) {
                ally.canActThisTurn = true;
                writePiece(support, ally, support.ownerId);
            }
        }
        else if (skill === "AMBUSH") {
            const valid = attackRange(piece, origin).some((point) => point.row === targetRow && point.col === targetCol);
            if (!valid)
                return { correct: false };
            piece.statusEffects = [...new Set([...piece.statusEffects, "Ambushing"])];
            piece.ambushTarget = { row: targetRow, col: targetCol };
            writePiece(source, piece, playerId);
        }
        else if (skill === "PIERCING_RAY") {
            const dy = Math.sign(targetRow - move.row);
            const dx = Math.sign(targetCol - move.col);
            if (Math.abs(targetRow - move.row) !== Math.abs(targetCol - move.col) || Math.abs(targetRow - move.row) > 3) {
                return { correct: false };
            }
            for (let row = move.row + dy, col = move.col + dx, distance = 1; distance <= 3 && row >= 0 && row < 8 && col >= 0 && col < 8; row += dy, col += dx, distance += 1) {
                const victimCell = this.board[row][col];
                const victim = pieceFromCell(victimCell);
                if (!victim)
                    continue;
                if (victim.team !== piece.team && !victim.statusEffects.some((effect) => effect.toUpperCase() === "INVULNERABLE")) {
                    if (victim.type === "KNIGHT" && victim.hasEvasion) {
                        victim.hasEvasion = false;
                        writePiece(victimCell, victim, victimCell.ownerId);
                    }
                    else
                        clearPiece(victimCell);
                }
                break;
            }
            writePiece(source, piece, playerId);
        }
        else if (skill === "SHOCKWAVE") {
            for (let row = move.row - 1; row <= move.row + 1; row += 1)
                for (let col = move.col - 1; col <= move.col + 1; col += 1) {
                    const victimCell = this.board[row]?.[col];
                    const victim = victimCell ? pieceFromCell(victimCell) : null;
                    if (!victimCell || !victim || victim.team === piece.team)
                        continue;
                    victim.statusEffects = [...new Set([...victim.statusEffects, "Stunned"])];
                    writePiece(victimCell, victim, victimCell.ownerId);
                }
            writePiece(source, piece, playerId);
        }
        else if (skill === "TACTICAL_TRANSPOSITION") {
            const ally = pieceFromCell(target);
            if (!ally || ally.team !== piece.team || ally.type === "KING")
                return { correct: false };
            const owner = target.ownerId;
            writePiece(source, ally, owner);
            writePiece(target, piece, playerId);
        }
        else {
            if (targetRow !== move.row || targetCol !== move.col)
                return { correct: false };
            piece.statusEffects = [...new Set([...piece.statusEffects, "Invulnerable"])];
            writePiece(source, piece, playerId);
        }
        piece.ap -= cost;
        piece.currentCooldown = cooldownFor(skill) + 1;
        const currentCell = this.board.flat().find((cell) => cell.meta.pieceId === piece.id);
        if (currentCell)
            writePiece(currentCell, piece, currentCell.ownerId);
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
    chessPathClear(origin, target, type) {
        if (["PAWN", "KNIGHT", "KING"].includes(type))
            return true;
        const dy = Math.sign(target.row - origin.row);
        const dx = Math.sign(target.col - origin.col);
        for (let row = origin.row + dy, col = origin.col + dx; row !== target.row || col !== target.col; row += dy, col += dx) {
            if (this.board[row]?.[col]?.value != null)
                return false;
        }
        return true;
    }
    updateChessPassives() {
        this.board.flat().forEach((cell) => {
            const piece = pieceFromCell(cell);
            if (!piece)
                return;
            piece.isShielded = false;
            writePiece(cell, piece, cell.ownerId);
        });
        this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
            const piece = pieceFromCell(cell);
            if (!piece || piece.type !== "PAWN")
                return;
            const shielded = [colIndex - 1, colIndex + 1].some((col) => {
                const ally = this.board[rowIndex]?.[col] ? pieceFromCell(this.board[rowIndex][col]) : null;
                return ally?.team === piece.team && ally.type === "PAWN";
            });
            if (shielded) {
                piece.isShielded = true;
                writePiece(cell, piece, cell.ownerId);
            }
        }));
    }
    arrowShapeMembers(shapeId) {
        const result = [];
        this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
            if (String(cell.meta.shapeId ?? `${rowIndex}:${colIndex}`) === shapeId) {
                result.push({ row: rowIndex, col: colIndex, cell });
            }
        }));
        return result;
    }
    canArrowShapeEscape(shapeId, removed) {
        if (this.meta.freeSpace === true) {
            const shapes = this.meta.shapes;
            const shape = shapes.find((candidate) => candidate.id === shapeId);
            if (!shape)
                return false;
            const obstacles = shapes.filter((candidate) => candidate.id !== shape.id && !candidate.memberKeys.every((key) => removed.has(key)));
            // Una ligera superposición visual de las formas curvas de la silueta no
            // debe convertir el nivel en un interbloqueo imposible desde el inicio.
            const initiallyOverlapping = new Set(obstacles.filter((obstacle) => rectanglesIntersect(shape, obstacle)).map((obstacle) => obstacle.id));
            const vector = shape.direction === "UP" ? { x: 0, y: -1 }
                : shape.direction === "RIGHT" ? { x: 1, y: 0 }
                    : shape.direction === "DOWN" ? { x: 0, y: 1 }
                        : { x: -1, y: 0 };
            const perpendicular = { x: -vector.y, y: vector.x };
            for (let step = 1; step <= 80; step += 1) {
                const progress = step / 40;
                const curveSign = shape.pathType === "CURVE_LEFT" ? -1 : shape.pathType === "CURVE_RIGHT" ? 1 : 0;
                const curve = curveSign * Math.sin(Math.min(1, progress) * Math.PI) * .11;
                const projected = {
                    x: shape.x + vector.x * progress + perpendicular.x * curve,
                    y: shape.y + vector.y * progress + perpendicular.y * curve,
                    width: shape.width,
                    height: shape.height,
                };
                const outside = projected.x + projected.width < 0 || projected.x > 1
                    || projected.y + projected.height < 0 || projected.y > 1;
                if (outside)
                    return true;
                if (obstacles.some((obstacle) => !initiallyOverlapping.has(obstacle.id) && rectanglesIntersect(projected, obstacle)))
                    return false;
            }
            return false;
        }
        const members = this.arrowShapeMembers(shapeId);
        if (!members.length)
            return false;
        const own = new Set(members.map(({ row, col }) => `${row}:${col}`));
        const direction = String(members[0].cell.meta.arrow);
        const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1]
            : direction === "DOWN" ? [1, 0] : [0, -1];
        return members.every((member) => {
            for (let row = member.row + dy, col = member.col + dx; row >= 0 && row < this.board.length && col >= 0 && col < this.board[0].length; row += dy, col += dx) {
                const key = `${row}:${col}`;
                if (!own.has(key) && !removed.has(key))
                    return false;
            }
            return true;
        });
    }
    botValue(row, col, correct) {
        if (this.gameType === "WORD_SEARCH") {
            const placement = this.meta.placements.find((item) => item.startRow === row && item.startCol === col);
            const word = placement?.word ?? "ERROR";
            return { word: correct ? word : `${word}X`, endRow: placement?.endRow ?? row, endCol: placement?.endCol ?? col };
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
        if (this.gameType === "NURIKABE")
            return answer === true ? "RIVER" : "ISLAND";
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
        if (this.gameType === "SECRET_CODE")
            return this.completed;
        if (this.gameType === "CAPITAL_ARENA")
            return false;
        if (this.gameType === "MINESWEEPER") {
            return this.board.every((row, y) => row.every((cell, x) => this.answers[y][x] === true || cell.ownerId !== null));
        }
        if (this.gameType === "WORD_SEARCH")
            return this.meta.foundWords.length === this.meta.words.length;
        if (this.gameType === "HANGMAN") {
            return this.board[0].every((cell) => cell.value !== null)
                || (this.turnOrder.length > 0 && this.turnOrder.every((id) => (this.hangmanErrors.get(id) ?? 0) >= 6));
        }
        if (this.gameType === "ARROWS_ESCAPE")
            return [...this.arrowRemoved.values()].some((removed) => removed.size === this.board.length * this.board[0].length);
        if (this.gameType === "MEMORY_NEON")
            return this.board.flat().every((cell) => cell.ownerId !== null);
        if (this.gameType === "MERGE_2048") {
            if (this.board.flat().some((cell) => Number(cell.value ?? 0) >= Number(this.meta.target ?? 256)))
                return true;
            if (this.board.flat().some((cell) => cell.value === null))
                return false;
            for (let row = 0; row < 4; row += 1)
                for (let col = 0; col < 4; col += 1) {
                    const value = this.board[row][col].value;
                    if (this.board[row + 1]?.[col]?.value === value || this.board[row]?.[col + 1]?.value === value)
                        return false;
                }
            this.meta.gameOverReason = "NO_MOVES";
            return true;
        }
        if (this.gameType === "TIC_TAC_TOE")
            return this.ticTacToeWinner() !== null || this.board.flat().every((cell) => cell.value !== null);
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
        if (this.gameType === "DOTS_AND_BOXES")
            return this.board.every((row) => row.every((cell) => cell.ownerId !== null));
        if (this.gameType === "NURIKABE") {
            return this.board.every((row, y) => row.every((cell, x) => cell.meta.islandClue === true || cell.value === (this.answers[y][x] === true ? "RIVER" : "ISLAND")));
        }
        if (["HITORI", "BRIDGES"].includes(this.gameType)) {
            return this.board.every((row, y) => row.every((cell, x) => this.answers[y][x] !== true || cell.ownerId !== null));
        }
        if (this.gameType === "CROSS_LETTERS") {
            return this.letterBag.length === 0 && [...this.racks.values()].every((rack) => rack.length === 0 || findWordForRack(rack) === null);
        }
        return this.board.every((row, y) => row.every((cell, x) => this.answers[y][x] === null || cell.isBlocked || cell.ownerId !== null));
    }
    syncLetterPlayers(game, now) {
        const players = game.snapshot(now).players;
        const ids = players.map((player) => player.id);
        this.turnOrder = ids;
        for (const player of players)
            this.ensureRack(player.id, player.isBot);
        for (const id of [...this.racks.keys()])
            if (!ids.includes(id))
                this.racks.delete(id);
        if (!this.activePlayerId || !ids.includes(this.activePlayerId) || now >= this.turnEndsAt) {
            const previous = this.activePlayerId ? ids.indexOf(this.activePlayerId) : -1;
            this.activePlayerId = ids.length ? ids[(previous + 1) % ids.length] : null;
            this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
        }
    }
    syncTurnPlayers(game) {
        const ids = game.snapshot().players.map((player) => player.id)
            .filter((id) => this.gameType !== "HANGMAN" || (this.hangmanErrors.get(id) ?? 0) < 6);
        this.turnOrder = ids;
        if (!this.activePlayerId || !ids.includes(this.activePlayerId))
            this.activePlayerId = ids[0] ?? null;
    }
    syncSecretPlayers(game) {
        const players = game.snapshot().players;
        if (this.secretAssignments.size === 0 && players.length > 0) {
            const shuffled = [...players].sort(() => Math.random() - 0.5);
            shuffled.forEach((player, index) => {
                const team = index % 2 === 0 ? "RED" : "BLUE";
                const hasCaptain = [...this.secretAssignments.values()].some((entry) => entry.team === team && entry.role === "CAPTAIN");
                this.secretAssignments.set(player.id, { team, role: hasCaptain ? "OPERATIVE" : "CAPTAIN" });
            });
            return;
        }
        for (const player of players) {
            if (this.secretAssignments.has(player.id))
                continue;
            const red = [...this.secretAssignments.values()].filter((entry) => entry.team === "RED").length;
            const blue = [...this.secretAssignments.values()].filter((entry) => entry.team === "BLUE").length;
            const team = red <= blue ? "RED" : "BLUE";
            const hasCaptain = [...this.secretAssignments.values()].some((entry) => entry.team === team && entry.role === "CAPTAIN");
            this.secretAssignments.set(player.id, { team, role: hasCaptain ? "OPERATIVE" : "CAPTAIN" });
        }
    }
    secretRemainingCounts() {
        const remaining = { RED: 0, BLUE: 0 };
        this.board.forEach((row, y) => row.forEach((cell, x) => {
            const identity = String(this.answers[y][x]);
            if (cell.ownerId === null && (identity === "RED" || identity === "BLUE"))
                remaining[identity] = (remaining[identity] ?? 0) + 1;
        }));
        return remaining;
    }
    secretActivePlayerId() {
        const requiredRole = this.secretClue == null ? "CAPTAIN" : "OPERATIVE";
        return [...this.secretAssignments.entries()]
            .find(([, assignment]) => assignment.team === this.secretCurrentTeam && assignment.role === requiredRole)?.[0] ?? null;
    }
    syncCapitalPlayers(game) {
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
    applyCapitalMove(playerId, move, game) {
        const payload = typeof move.val === "object" && move.val !== null ? move.val : {};
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
            if (this.capitalStage !== "ROLL")
                return { correct: false };
            const from = this.capitalPositions.get(playerId) ?? 0;
            const dice = [1 + Math.floor(Math.random() * 6), 1 + Math.floor(Math.random() * 6)];
            const distance = dice[0] + dice[1];
            const rawTarget = from + distance;
            const to = rawTarget % 40;
            if (rawTarget >= 40)
                this.changeCapitalBalance(playerId, 200);
            this.capitalDice = dice;
            this.capitalPositions.set(playerId, to);
            this.capitalLastMove = { playerId, from, to };
            this.resolveCapitalLanding(playerId, to);
            this.refreshCapitalScores(game);
            return { correct: true, points: 0 };
        }
        if (action === "SKILL") {
            if (this.capitalSkillsUsed.has(playerId))
                return { correct: false };
            this.capitalSkillsUsed.add(playerId);
            this.changeCapitalBalance(playerId, 100);
            this.capitalEvent = "Impulso de mercado activado: +100 créditos";
            this.refreshCapitalScores(game);
            return { correct: true, points: 0 };
        }
        if (action === "BUY") {
            const index = this.capitalPendingProperty;
            if (this.capitalStage !== "BUY_OR_END" || index == null || this.capitalPropertyOwners.has(index))
                return { correct: false };
            const space = this.capitalSpace(index);
            const balance = this.capitalBalances.get(playerId) ?? 0;
            if (!space || space.price <= 0 || balance < space.price)
                return { correct: false };
            this.changeCapitalBalance(playerId, -space.price);
            this.capitalPropertyOwners.set(index, playerId);
            this.capitalPropertyLevels.set(index, 0);
            const propertyCell = this.capitalCell(index);
            if (propertyCell)
                propertyCell.ownerId = playerId;
            this.capitalEvent = `${playerId.slice(0, 8)} conquistó ${space.name}`;
            this.capitalPendingProperty = null;
            this.capitalStage = "END";
            this.refreshCapitalScores(game);
            return { correct: true, points: 0 };
        }
        if (action === "BUILD") {
            if (this.capitalStage !== "END")
                return { correct: false };
            const index = this.capitalPositions.get(playerId);
            if (index == null)
                return { correct: false };
            const space = this.capitalSpace(index);
            if (!space)
                return { correct: false };
            const level = this.capitalPropertyLevels.get(index) ?? 0;
            const cost = Math.max(50, Math.round(space.price / 2));
            if (this.capitalPropertyOwners.get(index) !== playerId || level >= 4 || (this.capitalBalances.get(playerId) ?? 0) < cost)
                return { correct: false };
            this.changeCapitalBalance(playerId, -cost);
            this.capitalPropertyLevels.set(index, level + 1);
            this.capitalEvent = `Mejora de hackeo nivel ${level + 1} en ${space.name}`;
            this.refreshCapitalScores(game);
            return { correct: true, points: 0 };
        }
        if (action === "END_TURN") {
            if (this.capitalStage === "ROLL")
                return { correct: false };
            this.advanceCapitalTurn();
            return { correct: true, points: 0 };
        }
        return { correct: false };
    }
    resolveCapitalLanding(playerId, index) {
        const space = this.capitalSpace(index);
        if (!space) {
            this.capitalStage = "END";
            return;
        }
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
        }
        else
            this.capitalEvent = owner === playerId ? `Tu distrito ${space.name}` : space.name;
        this.capitalStage = "END";
    }
    drawCapitalCard(playerId, from) {
        const cards = [
            { title: "Hackathon Maestro", description: "Recibes 200 créditos", kind: "BONUS", money: 200 },
            { title: "Inversión Relámpago", description: "Recibes 120 créditos", kind: "BONUS", money: 120 },
            { title: "Fallo de Servidor", description: "Pagas una multa de 100 créditos", kind: "PENALTY", money: -100 },
            { title: "Auditoría de la Arena", description: "Pagas una multa de 160 créditos", kind: "PENALTY", money: -160 },
            { title: "Atajo Quantum", description: "Avanzas 3 casillas", kind: "MOVE", move: 3 },
            { title: "Firewall Policial", description: "Vas directamente a la cárcel", kind: "PENALTY", jail: true },
        ];
        const selected = cards[Math.floor(Math.random() * cards.length)];
        if (selected.money)
            this.changeCapitalBalance(playerId, selected.money);
        if (selected.jail) {
            this.capitalPositions.set(playerId, 10);
            this.capitalLastMove = { playerId, from, to: 10 };
        }
        else if (selected.move) {
            const rawTarget = from + selected.move;
            const target = rawTarget % 40;
            if (rawTarget >= 40)
                this.changeCapitalBalance(playerId, 200);
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
    advanceCapitalTurn() {
        if (!this.turnOrder.length)
            return;
        const current = Math.max(0, this.turnOrder.indexOf(this.activePlayerId ?? ""));
        this.activePlayerId = this.turnOrder[(current + 1) % this.turnOrder.length];
        this.capitalStage = "ROLL";
        this.capitalPendingProperty = null;
        this.capitalEvent = `Turno de ${this.activePlayerId.slice(0, 8)}`;
    }
    createCapitalBotMove(playerId) {
        if (!playerId || this.activePlayerId !== playerId)
            return null;
        let action = "ROLL";
        if (this.capitalStage === "BUY_OR_END") {
            const index = this.capitalPendingProperty;
            const space = index == null ? null : this.capitalSpace(index);
            action = space && (this.capitalBalances.get(playerId) ?? 0) >= space.price && Math.random() < .72 ? "BUY" : "END_TURN";
        }
        else if (this.capitalStage === "END") {
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
    changeCapitalBalance(playerId, delta) {
        this.capitalBalances.set(playerId, Math.max(0, (this.capitalBalances.get(playerId) ?? 0) + delta));
    }
    refreshCapitalScores(game) {
        for (const playerId of this.turnOrder) {
            let netWorth = this.capitalBalances.get(playerId) ?? 0;
            for (const [index, owner] of this.capitalPropertyOwners) {
                if (owner !== playerId)
                    continue;
                const space = this.capitalSpace(index);
                if (space)
                    netWorth += space.price + (this.capitalPropertyLevels.get(index) ?? 0) * Math.round(space.price / 2);
            }
            game.setGenericScore(playerId, netWorth);
        }
    }
    capitalSpace(index) {
        const spaces = this.meta.spaces;
        return spaces.find((space) => space.index === index) ?? null;
    }
    capitalCell(index) {
        return this.board.flat().find((cell) => Number(cell.meta.index) === index) ?? null;
    }
    advanceStrictTurn() {
        if (!this.turnOrder.length)
            return;
        const index = Math.max(0, this.turnOrder.indexOf(this.activePlayerId ?? ""));
        const previousTeam = this.gameType === "CHESS_TACTICS" && this.activePlayerId
            ? this.activeTeam(this.activePlayerId)
            : null;
        this.activePlayerId = this.turnOrder[(index + 1) % this.turnOrder.length];
        if (this.gameType === "CHESS_TACTICS") {
            const nextTeam = this.activeTeam(this.activePlayerId);
            // Fin de turno: enfría habilidades del equipo que actuó y consume Stun.
            this.board.flat().forEach((cell) => {
                const piece = pieceFromCell(cell);
                if (!piece || piece.team !== previousTeam)
                    return;
                piece.currentCooldown = Math.max(0, piece.currentCooldown - 1);
                piece.statusEffects = piece.statusEffects.filter((effect) => effect.toUpperCase() !== "STUNNED");
                writePiece(cell, piece, cell.ownerId);
            });
            // Aura de liderazgo: la reina acelera un turno adicional a aliados cercanos.
            this.board.forEach((row, rowIndex) => row.forEach((cell, colIndex) => {
                const queen = pieceFromCell(cell);
                if (!queen || queen.team !== previousTeam || queen.type !== "QUEEN")
                    return;
                for (let y = rowIndex - 1; y <= rowIndex + 1; y += 1)
                    for (let x = colIndex - 1; x <= colIndex + 1; x += 1) {
                        const allyCell = this.board[y]?.[x];
                        const ally = allyCell ? pieceFromCell(allyCell) : null;
                        if (!allyCell || !ally || ally.team !== queen.team || ally.id === queen.id)
                            continue;
                        ally.currentCooldown = Math.max(0, ally.currentCooldown - 1);
                        writePiece(allyCell, ally, allyCell.ownerId);
                    }
            }));
            this.board.flat().forEach((cell) => {
                const piece = pieceFromCell(cell);
                if (!piece || piece.team !== nextTeam)
                    return;
                piece.ap = piece.maxAp;
                piece.canActThisTurn = false;
                piece.statusEffects = piece.statusEffects.filter((effect) => effect.toUpperCase() !== "INVULNERABLE");
                writePiece(cell, piece, cell.ownerId);
            });
            this.updateChessPassives();
        }
    }
    ensureRack(playerId, guaranteeBotMove = false) {
        if (this.racks.has(playerId))
            return;
        let rack = [];
        let suggested = null;
        for (let attempt = 0; attempt < (guaranteeBotMove ? 80 : 1); attempt += 1) {
            rack = [];
            while (rack.length < 7 && this.letterBag.length)
                rack.push(this.letterBag.pop());
            suggested = findAnchoredWordForRack(rack, String(this.meta.centralWord ?? "ARENA"));
            if (!guaranteeBotMove || suggested)
                break;
            this.letterBag.unshift(...rack);
            shuffleLetters(this.letterBag);
        }
        this.racks.set(playerId, rack);
        this.suggestedWords.set(playerId, suggested ?? findWordForRack(rack) ?? "");
    }
    refillRack(playerId) {
        const rack = this.racks.get(playerId) ?? [];
        while (rack.length < 7 && this.letterBag.length)
            rack.push(this.letterBag.pop());
        this.racks.set(playerId, rack);
        this.suggestedWords.set(playerId, findWordForRack(rack) ?? "");
    }
    advanceLetterTurn(now) {
        if (!this.turnOrder.length)
            return;
        const index = Math.max(0, this.turnOrder.indexOf(this.activePlayerId ?? ""));
        this.activePlayerId = this.turnOrder[(index + 1) % this.turnOrder.length];
        this.turnEndsAt = now + Number(this.meta.turnSeconds ?? 60) * 1_000;
    }
    applyCrossLettersMove(playerId, move) {
        const payload = typeof move.val === "object" && move.val !== null ? move.val : { word: move.val };
        const word = normalizeSpanishWord(String(payload.word ?? ""));
        const direction = String(payload.direction ?? "H").toUpperCase();
        if (!SPANISH_WORDS.has(word) || !["H", "V"].includes(direction) || word.length < 2)
            return { correct: false };
        const rowStep = direction === "V" ? 1 : 0;
        const colStep = direction === "H" ? 1 : 0;
        const coordinates = [...word].map((letter, index) => ({
            letter, row: move.row + rowStep * index, col: move.col + colStep * index
        }));
        if (coordinates.some(({ row, col }) => !this.board[row]?.[col]))
            return { correct: false };
        const hasTiles = this.board.some((row) => row.some((target) => target.value !== null));
        if (!hasTiles && !coordinates.some(({ row, col }) => row === 7 && col === 7))
            return { correct: false };
        if (hasTiles && !coordinates.some(({ row, col, letter }) => this.board[row][col].value === letter))
            return { correct: false };
        if (coordinates.some(({ row, col, letter }) => {
            const existing = this.board[row][col].value;
            return existing !== null && existing !== letter;
        }))
            return { correct: false };
        const rack = [...(this.racks.get(playerId) ?? [])];
        const newTiles = coordinates.filter(({ row, col }) => this.board[row][col].value === null);
        for (const { letter } of newTiles) {
            const index = rack.indexOf(letter);
            if (index < 0)
                return { correct: false };
            rack.splice(index, 1);
        }
        // Cross-check autoritativo: se simula la colocación completa y se validan
        // todas las palabras horizontales y verticales que toca cada ficha nueva.
        const projected = this.board.map((boardRow) => boardRow.map((target) => target.value == null ? "" : String(target.value)));
        coordinates.forEach(({ row, col, letter }) => { projected[row][col] = letter; });
        const formedWords = new Set([word]);
        for (const tile of newTiles) {
            for (const axis of ["H", "V"]) {
                const cross = readOrthogonalWord(projected, tile.row, tile.col, axis);
                if (cross.length >= 2)
                    formedWords.add(cross);
            }
        }
        if ([...formedWords].some((formed) => !SPANISH_WORDS.has(formed)))
            return { correct: false };
        let wordMultiplier = 1;
        let points = 0;
        for (const { row, col, letter } of coordinates) {
            const target = this.board[row][col];
            const fresh = target.value === null;
            const bonus = fresh ? String(target.meta.bonus ?? "NONE") : "NONE";
            const letterMultiplier = bonus === "TL" ? 3 : bonus === "DL" ? 2 : 1;
            if (bonus === "TW")
                wordMultiplier *= 3;
            if (bonus === "DW")
                wordMultiplier *= 2;
            points += (SCRABBLE_SCORES[letter] ?? 1) * letterMultiplier;
            if (fresh) {
                target.value = letter;
                target.ownerId = playerId;
                target.isRevealed = true;
            }
        }
        this.racks.set(playerId, rack);
        this.refillRack(playerId);
        return { correct: true, points: points * wordMultiplier + (newTiles.length === 7 ? 50 : 0) };
    }
    applySecretCodeMove(playerId, move, cell) {
        const payload = typeof move.val === "object" && move.val !== null ? move.val : {};
        const action = String(payload.action ?? "GUESS").toUpperCase();
        const assignment = this.secretAssignments.get(playerId);
        if (!assignment || assignment.team !== this.secretCurrentTeam)
            return { correct: false };
        if (action === "CLUE") {
            if (assignment.role !== "CAPTAIN")
                return { correct: false };
            const word = normalizeSpanishWord(String(payload.clue ?? ""));
            const count = Number(payload.count);
            if (word.length < 2 || word.length > 20 || !Number.isInteger(count) || count < 1 || count > 9)
                return { correct: false };
            if (this.secretWords().some((boardWord) => boardWord.includes(word) || word.includes(boardWord)))
                return { correct: false };
            this.secretClue = { word, count, remaining: count };
            return { correct: true, points: 0 };
        }
        if (action !== "GUESS" || assignment.role !== "OPERATIVE" || !this.secretClue || cell.ownerId !== null)
            return { correct: false };
        const identity = String(this.answers[move.row][move.col]);
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
            }
            else if (this.secretClue.remaining <= 0)
                this.switchSecretTeam();
            return { correct: true, points: 20 };
        }
        this.switchSecretTeam();
        return { correct: true, points: identity === "NEUTRAL" ? 0 : 10 };
    }
    switchSecretTeam() {
        this.secretCurrentTeam = this.secretCurrentTeam === "RED" ? "BLUE" : "RED";
        this.secretClue = null;
    }
    createSecretBotMove(playerId) {
        if (!playerId)
            return null;
        const assignment = this.secretAssignments.get(playerId);
        if (!assignment || assignment.team !== this.secretCurrentTeam)
            return null;
        if (assignment.role === "CAPTAIN" && !this.secretClue) {
            return { requestId: `secret-bot-${randomUUID()}`, row: 0, col: 0, val: { action: "CLUE", clue: "IDEA", count: 1 } };
        }
        if (assignment.role !== "OPERATIVE" || !this.secretClue)
            return null;
        for (let row = 0; row < 5; row += 1)
            for (let col = 0; col < 5; col += 1) {
                if (this.board[row][col].ownerId === null && this.answers[row][col] === assignment.team) {
                    return { requestId: `secret-bot-${randomUUID()}`, row, col, val: { action: "GUESS" } };
                }
            }
        return null;
    }
    createCrossLettersBotMove(accuracy, playerId) {
        const id = playerId ?? this.activePlayerId;
        if (!id || this.activePlayerId !== id)
            return null;
        const word = this.suggestedWords.get(id) ?? "ARENA";
        const correctWord = Math.random() <= accuracy ? word : `${word}X`;
        const empty = !this.board.some((row) => row.some((cell) => cell.value !== null));
        if (empty)
            return { requestId: `letters-bot-${randomUUID()}`, row: 7, col: Math.max(0, 7 - Math.floor(word.length / 2)), val: { word: correctWord, direction: "H" } };
        for (let row = 0; row < this.board.length; row += 1)
            for (let col = 0; col < this.board[row].length; col += 1) {
                const letter = String(this.board[row][col].value ?? "");
                if (!letter)
                    continue;
                const index = word.indexOf(letter);
                if (index < 0)
                    continue;
                const start = row - index;
                if (start >= 0 && start + word.length <= 15) {
                    return { requestId: `letters-bot-${randomUUID()}`, row: start, col, val: { word: correctWord, direction: "V" } };
                }
            }
        return null;
    }
    unresolvedWordPlacements() {
        const found = new Set(this.meta.foundWords);
        return this.meta.placements.filter((placement) => !found.has(placement.word));
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
function pieceFromCell(cell) {
    const type = String(cell.meta.type ?? "");
    const team = String(cell.meta.team ?? "");
    if (!["PAWN", "KNIGHT", "BISHOP", "ROOK", "QUEEN", "KING"].includes(type) || !["BLUE", "RED"].includes(team))
        return null;
    return {
        id: String(cell.meta.pieceId ?? ""),
        team: team,
        owner: team,
        type: type,
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
        ambushTarget: typeof cell.meta.ambushTarget === "string" && cell.meta.ambushTarget.includes(":")
            ? (() => {
                const [row, col] = cell.meta.ambushTarget.split(":").map(Number);
                return Number.isInteger(row) && Number.isInteger(col) ? { row: row, col: col } : null;
            })()
            : null,
    };
}
function writePiece(cell, piece, ownerId) {
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
        ambushTarget: piece.ambushTarget ? `${piece.ambushTarget.row}:${piece.ambushTarget.col}` : null,
    };
}
function clearPiece(cell) {
    cell.value = null;
    cell.isRevealed = false;
    cell.ownerId = null;
    cell.meta = {};
}
function oppositeSide(side) {
    return { top: "bottom", right: "left", bottom: "top", left: "right" }[side] ?? "";
}
function rectanglesIntersect(first, second) {
    return first.x < second.x + second.width
        && first.x + first.width > second.x
        && first.y < second.y + second.height
        && first.y + first.height > second.y;
}
function neighbourFor(row, col, side, rows, columns) {
    const target = side === "top" ? { row: row - 1, col }
        : side === "right" ? { row, col: col + 1 }
            : side === "bottom" ? { row: row + 1, col }
                : side === "left" ? { row, col: col - 1 }
                    : null;
    return target && target.row >= 0 && target.row < rows && target.col >= 0 && target.col < columns ? target : null;
}
const SPANISH_WORDS = new Set(SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word)));
const LETTER_BAG = [..."AAAAAAAAAAAAEEEEEEEEEEEEOOOOOOOOOOSSSSSSNNNNNRRRRRIIIIILLTTTTCCCCUDPMG B F V Y Q H Z J Ñ X".replace(/\s/g, "")];
function normalizeSpanishWord(value) {
    return value.trim().toUpperCase().replace(/Ñ/g, "#").normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/#/g, "Ñ").replace(/[^A-ZÑ]/g, "");
}
function readOrthogonalWord(board, row, col, axis) {
    const dy = axis === "V" ? 1 : 0;
    const dx = axis === "H" ? 1 : 0;
    let startRow = row;
    let startCol = col;
    while (board[startRow - dy]?.[startCol - dx]) {
        startRow -= dy;
        startCol -= dx;
    }
    let result = "";
    for (let y = startRow, x = startCol; board[y]?.[x]; y += dy, x += dx)
        result += board[y][x];
    return result;
}
function shuffleLetters(letters) {
    for (let index = letters.length - 1; index > 0; index -= 1) {
        const target = Math.floor(Math.random() * (index + 1));
        [letters[index], letters[target]] = [letters[target], letters[index]];
    }
    return letters;
}
function findWordForRack(rack) {
    return SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word))
        .filter((word) => word.length >= 2 && word.length <= rack.length)
        .find((word) => {
        const available = [...rack];
        return [...word].every((letter) => {
            const index = available.indexOf(letter);
            if (index < 0)
                return false;
            available.splice(index, 1);
            return true;
        });
    }) ?? null;
}
function findAnchoredWordForRack(rack, anchor) {
    const anchors = new Set(anchor);
    return SPANISH_DICTIONARY.map((entry) => normalizeSpanishWord(entry.word))
        .filter((word) => word.length >= 2 && word.length <= rack.length && [...word].some((letter) => anchors.has(letter)))
        .find((word) => {
        const available = [...rack];
        return [...word].every((letter) => {
            const index = available.indexOf(letter);
            if (index < 0)
                return false;
            available.splice(index, 1);
            return true;
        });
    }) ?? null;
}
//# sourceMappingURL=engine.js.map