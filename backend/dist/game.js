import { randomUUID } from "node:crypto";
import { BOARD_SIZE, CELL_POINTS, MAX_PLAYERS, PENALTY_MS, PLAYER_COLORS, SECTION_POINTS, SOLUTION } from "./constants.js";
export class ArenaGame {
    gameId;
    board = Array.from({ length: BOARD_SIZE }, () => Array.from({ length: BOARD_SIZE }, () => ({
        value: null,
        ownerId: null,
        clearTokens: new Set()
    })));
    players = new Map();
    pendingSections = new Set();
    processedRequests = new Map();
    revision = 0;
    constructor(gameId = "arena-main") {
        this.gameId = gameId;
    }
    get playerCount() {
        return this.players.size;
    }
    addPlayer(id, rawName) {
        if (this.players.size >= MAX_PLAYERS)
            return null;
        const usedSlots = new Set([...this.players.values()].map((player) => player.slot));
        const slot = PLAYER_COLORS.findIndex((_, index) => !usedSlots.has(index));
        const player = {
            id,
            name: sanitizeName(rawName, slot + 1),
            slot,
            color: PLAYER_COLORS[slot] ?? PLAYER_COLORS[0],
            score: 0,
            blockedUntil: 0
        };
        this.players.set(id, player);
        this.processedRequests.set(id, new Set());
        this.revision += 1;
        return { ...player };
    }
    removePlayer(id) {
        if (!this.players.delete(id))
            return false;
        this.processedRequests.delete(id);
        // Las jugadas permanecen válidas, pero pasan a color neutral.
        for (const row of this.board) {
            for (const cell of row) {
                if (cell.ownerId === id)
                    cell.ownerId = null;
            }
        }
        this.revision += 1;
        return true;
    }
    snapshot(now = Date.now()) {
        return {
            gameId: this.gameId,
            revision: this.revision,
            serverTime: now,
            board: this.board.map((row) => row.map(toPublicCell)),
            players: [...this.players.values()]
                .sort((a, b) => a.slot - b.slot)
                .map((player) => ({ ...player }))
        };
    }
    place(playerId, proposal, now = Date.now()) {
        const requestId = typeof proposal?.requestId === "string" ? proposal.requestId : "";
        if (!isValidProposal(proposal)) {
            return reject(requestId, "INVALID_PAYLOAD", "Jugada fuera de rango o mal formada");
        }
        const player = this.players.get(playerId);
        if (!player)
            return reject(requestId, "PLAYER_NOT_FOUND", "Jugador no registrado");
        const requests = this.processedRequests.get(playerId);
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
        const cell = this.board[proposal.row][proposal.column];
        if (cell.clearTokens.size > 0) {
            return reject(requestId, "CELL_CLEARING", "La sección está siendo limpiada");
        }
        if (cell.value !== null) {
            return reject(requestId, "CELL_OCCUPIED", "La casilla ya está ocupada");
        }
        if (SOLUTION[proposal.row][proposal.column] !== proposal.value) {
            player.blockedUntil = now + PENALTY_MS;
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
        player.score += CELL_POINTS;
        const sections = this.completedSections(proposal.row, proposal.column);
        const bonus = SECTION_POINTS * sections.length * sections.length;
        player.score += bonus;
        const clearPlan = sections.length > 0 ? this.markForClearing(sections) : null;
        this.revision += 1;
        return {
            accepted: true,
            requestId,
            revision: this.revision,
            sections,
            bonus,
            clearPlan
        };
    }
    executeClear(plan) {
        let changed = false;
        for (const { row, column } of plan.coordinates) {
            const cell = this.board[row][column];
            if (!cell.clearTokens.delete(plan.token))
                continue;
            // Aunque la casilla ya estuviera vacía por otra sección solapada, quitar
            // el último token cambia el campo público `clearing` y exige snapshot.
            changed = true;
            // Una casilla con otro token sigue bloqueada, pero la primera sección que
            // vence ya la vacía. Así no puede escribirse hasta terminar ambos timers.
            cell.value = null;
            cell.ownerId = null;
        }
        for (const key of plan.sectionKeys)
            this.pendingSections.delete(key);
        if (changed)
            this.revision += 1;
        return changed;
    }
    completedSections(row, column) {
        const box = Math.floor(row / 3) * 3 + Math.floor(column / 3);
        const candidates = [
            { kind: "row", index: row },
            { kind: "column", index: column },
            { kind: "box", index: box }
        ];
        return candidates.filter((section) => {
            const key = sectionKey(section);
            return !this.pendingSections.has(key) && this.sectionCoordinates(section).every(({ row: r, column: c }) => this.board[r][c].value !== null);
        });
    }
    markForClearing(sections) {
        const token = randomUUID();
        const unique = new Map();
        const sectionKeys = sections.map(sectionKey);
        for (const key of sectionKeys)
            this.pendingSections.add(key);
        for (const section of sections) {
            for (const coordinate of this.sectionCoordinates(section)) {
                unique.set(`${coordinate.row}:${coordinate.column}`, coordinate);
            }
        }
        const coordinates = [...unique.values()];
        for (const { row, column } of coordinates)
            this.board[row][column].clearTokens.add(token);
        return { token, coordinates, sectionKeys };
    }
    sectionCoordinates(section) {
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
}
function toPublicCell(cell) {
    return { value: cell.value, ownerId: cell.ownerId, clearing: cell.clearTokens.size > 0 };
}
function isValidProposal(proposal) {
    return (proposal !== null &&
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
        proposal.value <= 9);
}
function reject(requestId, code, message, stateChanged = false) {
    return { accepted: false, requestId, code, message, stateChanged };
}
function rememberRequest(requests, requestId) {
    requests.add(requestId);
    if (requests.size <= 256)
        return;
    const oldest = requests.values().next().value;
    if (oldest !== undefined)
        requests.delete(oldest);
}
function sectionKey(section) {
    return `${section.kind}:${section.index}`;
}
function sanitizeName(rawName, fallbackNumber) {
    const clean = typeof rawName === "string" ? rawName.trim().replace(/\s+/g, " ").slice(0, 24) : "";
    return clean || `Jugador ${fallbackNumber}`;
}
//# sourceMappingURL=game.js.map