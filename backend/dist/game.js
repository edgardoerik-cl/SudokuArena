import { randomUUID } from "node:crypto";
import { BOARD_SIZE, CELL_POINTS, ENERGY_PER_HIT, FOG_POWER_COST, GOLDEN_CELL_BONUS, MAX_PLAYERS, MAX_ENERGY, MIRROR_CELL_POINTS, MIRROR_PENALTY_MS, PENALTY_MS, PLAYER_COLORS, SECTION_POINTS, SOLUTION } from "./constants.js";
export class ArenaGame {
    gameId;
    solution;
    board = Array.from({ length: BOARD_SIZE }, () => Array.from({ length: BOARD_SIZE }, () => ({
        value: null,
        ownerId: null,
        clearTokens: new Set(),
        golden: false,
        ownerTeamId: null
    })));
    players = new Map();
    pendingSections = new Set();
    processedRequests = new Map();
    teamScores = new Map();
    revision = 0;
    activeBoardEvent = null;
    configuration = { powersEnabled: true, teamMode: "FFA" };
    constructor(gameId = "arena-main", solution = SOLUTION) {
        this.gameId = gameId;
        this.solution = solution;
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
            blockedUntil: 0,
            energy: 0,
            teamId: `PLAYER:${id}`,
            role: "PLAYER",
            teamScore: 0
        };
        this.players.set(id, player);
        this.processedRequests.set(id, new Set());
        this.teamScores.set(player.teamId, 0);
        this.revision += 1;
        return { ...player };
    }
    startMatch(config, hostPlayerId) {
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
            this.teamScores.set(player.teamId, 0);
        }
        this.revision += 1;
    }
    matchResults() {
        return [...this.players.values()]
            .sort((left, right) => right.teamScore - left.teamScore || right.score - left.score || left.slot - right.slot)
            .map((player, index) => ({
            rank: index + 1,
            playerId: player.id,
            name: player.name,
            score: player.score,
            teamId: player.teamId,
            teamScore: player.teamScore,
            role: player.role
        }));
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
                .map((player) => ({ ...player })),
            boardEvent: this.activeBoardEvent ? { ...this.activeBoardEvent } : null
        };
    }
    startBoardEvent(type, now = Date.now(), durationMs = 10_000) {
        if (this.activeBoardEvent)
            return null;
        this.activeBoardEvent = { type, startedAt: now, endsAt: now + durationMs };
        if (type === "GOLDEN_CELLS") {
            const candidates = this.emptyPlayableCells();
            shuffle(candidates);
            for (const { row, column } of candidates.slice(0, 2)) {
                this.board[row][column].golden = true;
            }
        }
        this.revision += 1;
        return { ...this.activeBoardEvent };
    }
    endBoardEvent(expectedType) {
        if (!this.activeBoardEvent || (expectedType && this.activeBoardEvent.type !== expectedType))
            return false;
        for (const row of this.board)
            for (const cell of row)
                cell.golden = false;
        this.activeBoardEvent = null;
        this.revision += 1;
        return true;
    }
    useFogPower(playerId, targetPlayerId) {
        const player = this.players.get(playerId);
        if (!player)
            return powerReject("PLAYER_NOT_FOUND", "Jugador no registrado");
        if (!this.configuration.powersEnabled)
            return powerReject("POWER_DISABLED", "Los poderes están desactivados");
        if (typeof targetPlayerId !== "string" || targetPlayerId.length === 0) {
            return powerReject("INVALID_TARGET", "Objetivo inválido");
        }
        if (targetPlayerId === playerId)
            return powerReject("SELF_TARGET", "No puedes atacarte a ti mismo");
        const target = this.players.get(targetPlayerId);
        if (!target)
            return powerReject("TARGET_NOT_FOUND", "El rival ya no está conectado");
        if (this.configuration.teamMode !== "FFA" && target.teamId === player.teamId) {
            return powerReject("SAME_TEAM", "No puedes atacar a un compañero");
        }
        if (player.energy < FOG_POWER_COST) {
            return powerReject("NOT_ENOUGH_ENERGY", "Necesitas 100% de energía");
        }
        player.energy -= FOG_POWER_COST;
        this.revision += 1;
        return { accepted: true, attackerId: playerId, targetPlayerId, type: "FOG" };
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
        if (this.solution[proposal.row][proposal.column] !== proposal.value) {
            const penaltyMs = this.activeBoardEvent?.type === "MIRROR_HOUR" ? MIRROR_PENALTY_MS : PENALTY_MS;
            player.blockedUntil = now + penaltyMs;
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
        const bossMultiplier = player.role === "BOSS" ? 2 : 1;
        const cellPoints = (this.activeBoardEvent?.type === "MIRROR_HOUR" ? MIRROR_CELL_POINTS : CELL_POINTS) * bossMultiplier;
        const goldenBonus = cell.golden ? GOLDEN_CELL_BONUS : 0;
        cell.golden = false;
        player.score += cellPoints + goldenBonus;
        if (this.configuration.powersEnabled) {
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
            cell.ownerTeamId = null;
            cell.golden = false;
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
    emptyPlayableCells() {
        const cells = [];
        for (let row = 0; row < BOARD_SIZE; row += 1) {
            for (let column = 0; column < BOARD_SIZE; column += 1) {
                const cell = this.board[row][column];
                if (cell.value === null && cell.clearTokens.size === 0)
                    cells.push({ row, column });
            }
        }
        return cells;
    }
    awardTeam(teamId, points) {
        const total = (this.teamScores.get(teamId) ?? 0) + points;
        this.teamScores.set(teamId, total);
        for (const player of this.players.values()) {
            if (player.teamId === teamId)
                player.teamScore = total;
        }
    }
}
function toPublicCell(cell) {
    return {
        value: cell.value,
        ownerId: cell.ownerId,
        clearing: cell.clearTokens.size > 0,
        golden: cell.golden,
        ownerTeamId: cell.ownerTeamId
    };
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
function powerReject(code, message) {
    return { accepted: false, code, message };
}
function shuffle(values) {
    for (let index = values.length - 1; index > 0; index -= 1) {
        const target = Math.floor(Math.random() * (index + 1));
        [values[index], values[target]] = [values[target], values[index]];
    }
}
function teamAssignment(player, mode, hostPlayerId) {
    if (mode === "TWO_V_TWO") {
        return { teamId: player.slot % 2 === 0 ? "TEAM_A" : "TEAM_B", role: "TEAMMATE" };
    }
    if (mode === "THREE_V_ONE") {
        return player.id === hostPlayerId
            ? { teamId: "BOSS", role: "BOSS" }
            : { teamId: "RAIDERS", role: "RAIDER" };
    }
    return { teamId: `PLAYER:${player.id}`, role: "PLAYER" };
}
//# sourceMappingURL=game.js.map