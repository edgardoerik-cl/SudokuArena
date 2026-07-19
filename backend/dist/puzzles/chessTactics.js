export function calculateDamage(baseDamage, defense, multiplier = 1) {
    return Math.max(1, Math.round(baseDamage * multiplier - defense * .45));
}
export function movementRange(piece, origin) {
    if (piece.ap <= 0)
        return [];
    const result = [];
    for (let dy = -piece.ap; dy <= piece.ap; dy += 1) {
        for (let dx = -piece.ap; dx <= piece.ap; dx += 1) {
            const distance = Math.abs(dx) + Math.abs(dy);
            if (distance === 0 || distance > piece.ap)
                continue;
            const row = origin.row + dy;
            const col = origin.col + dx;
            if (row >= 0 && row < 8 && col >= 0 && col < 8)
                result.push({ row, col });
        }
    }
    return result;
}
export function attackRange(piece, origin) {
    if (piece.ap <= 0)
        return [];
    if (piece.type === "PAWN") {
        const direction = piece.team === "BLUE" ? 1 : -1;
        return [-1, 0, 1]
            .map((dx) => ({ row: origin.row + direction, col: origin.col + dx }))
            .filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
    }
    if (piece.type === "KNIGHT") {
        return [[-2, -1], [-2, 1], [-1, -2], [-1, 2], [1, -2], [1, 2], [2, -1], [2, 1]]
            .map(([dy, dx]) => ({ row: origin.row + dy, col: origin.col + dx }))
            .filter(({ row, col }) => row >= 0 && row < 8 && col >= 0 && col < 8);
    }
    const result = [];
    for (const [dy, dx] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
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
export function skillCost(skill) {
    return skill === "PHALANX" ? 2 : skill === "EARTHQUAKE" ? 3 : 3;
}
export function skillFor(piece) {
    return piece.type === "PAWN" ? "PHALANX" : piece.type === "KNIGHT" ? "EARTHQUAKE" : "PIERCING_RAY";
}
//# sourceMappingURL=chessTactics.js.map