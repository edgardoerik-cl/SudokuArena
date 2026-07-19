export function calculateDamage(baseDamage, defense, multiplier = 1) {
    return Math.max(1, Math.round(baseDamage * multiplier - defense * .45));
}
export function movementRange(piece, origin) {
    if (piece.ap <= 0)
        return [];
    const direction = piece.team === "BLUE" ? 1 : -1;
    if (piece.type === "PAWN")
        return inside([{ row: origin.row + direction, col: origin.col }]);
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
    return skill === "ROYAL_BUNKER" || skill === "TACTICAL_TRANSPOSITION" ? 2 : 3;
}
export function skillFor(piece) {
    switch (piece.type) {
        case "PAWN": return "FORCED_MARCH";
        case "KNIGHT": return "AMBUSH";
        case "BISHOP": return "PIERCING_RAY";
        case "ROOK": return "SHOCKWAVE";
        case "QUEEN": return "TACTICAL_TRANSPOSITION";
        case "KING": return "ROYAL_BUNKER";
    }
}
export function cooldownFor(skill) {
    switch (skill) {
        case "FORCED_MARCH": return 3;
        case "AMBUSH": return 4;
        case "PIERCING_RAY": return 5;
        case "SHOCKWAVE": return 4;
        case "TACTICAL_TRANSPOSITION": return Number.MAX_SAFE_INTEGER;
        case "ROYAL_BUNKER": return 6;
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