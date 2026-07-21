export function calculateDamage(baseDamage, defense, multiplier = 1) {
    return Math.max(1, Math.round(baseDamage * multiplier - defense * .45));
}
export function movementRange(piece, origin) {
    if (piece.ap <= 0)
        return [];
    const direction = piece.team === "BLUE" ? 1 : -1;
    if (piece.type === "PAWN") {
        // Chess Tactics es una variante arcade: el peón conserva el avance de
        // una o dos filas. La Carga del Peón sigue siendo distinta porque empuja.
        return inside(Array.from({ length: 2 }, (_, index) => ({
            row: origin.row + direction * (index + 1),
            col: origin.col,
        })));
    }
    if (piece.type === "KNIGHT")
        return inside(offsets(origin, [
            [-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1],
        ]));
    if (piece.type === "KING")
        return inside(offsets(origin, [
            [-1, -1], [-1, 0], [-1, 1], [0, -1], [0, 1], [1, -1], [1, 0], [1, 1],
        ]));
    return rays(origin, piece.type === "BISHOP"
        ? [[-1, -1], [-1, 1], [1, -1], [1, 1]]
        : piece.type === "ROOK"
            ? [[-1, 0], [1, 0], [0, -1], [0, 1]]
            : [[-1, -1], [-1, 0], [-1, 1], [0, -1], [0, 1], [1, -1], [1, 0], [1, 1]]);
}
export function attackRange(piece, origin) {
    if (piece.ap <= 0)
        return [];
    if (piece.type === "PAWN") {
        const direction = piece.team === "BLUE" ? 1 : -1;
        return [-1, 1]
            .map((dx) => ({ row: origin.row + direction, col: origin.col + dx }))
            .filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
    }
    if (piece.type === "KNIGHT") {
        return [[-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1]]
            .map(([dy, dx]) => ({ row: origin.row + dy, col: origin.col + dx }))
            .filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
    }
    return movementRange(piece, origin);
}
export function skillCost(skill) {
    return 1;
}
export function skillFor(piece) {
    switch (piece.type) {
        case "PAWN": return "PHALANX_CHARGE";
        case "KNIGHT": return "SEISMIC_LEAP";
        case "BISHOP": return "PIERCING_RAY";
        case "ROOK": return "STONE_WALL";
        case "QUEEN": return "ROYAL_INTIMIDATION";
        case "KING": return "CALL_TO_ARMS";
    }
}
export function cooldownFor(skill) {
    switch (skill) {
        case "PHALANX_CHARGE": return Number.MAX_SAFE_INTEGER;
        case "SEISMIC_LEAP": return 4;
        case "PIERCING_RAY": return 5;
        case "STONE_WALL": return 4;
        case "ROYAL_INTIMIDATION": return 6;
        case "CALL_TO_ARMS": return Number.MAX_SAFE_INTEGER;
    }
}
function inside(points) {
    return points.filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
}
function offsets(origin, values) {
    return values.map(([row, col]) => ({ row: origin.row + row, col: origin.col + col }));
}
function rays(origin, directions) {
    const result = [];
    for (const [dy, dx] of directions) {
        for (let distance = 1; distance < 8; distance += 1) {
            const row = origin.row + dy * distance;
            const col = origin.col + dx * distance;
            if (row < 0 || row >= 8 || col < 0 || col >= 8)
                break;
            result.push({ row, col });
        }
    }
    return result;
}
//# sourceMappingURL=chessTactics.js.map