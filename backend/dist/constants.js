export const BOARD_SIZE = 9;
export const APP_VERSION = "0.5.0";
export const MAX_PLAYERS = 4;
export const CELL_POINTS = 10;
export const SECTION_POINTS = 100;
export const PENALTY_MS = 3_000;
export const CLEAR_DELAY_MS = 1_000;
export const ENERGY_PER_HIT = 25;
export const MAX_ENERGY = 100;
export const FOG_POWER_COST = 100;
export const MIRROR_CELL_POINTS = 20;
export const MIRROR_PENALTY_MS = 6_000;
export const GOLDEN_CELL_BONUS = 50;
export const BOARD_EVENT_INTERVAL_MS = 45_000;
export const BOARD_EVENT_DURATION_MS = 10_000;
export const MATCH_DURATION_MS = 180_000;
export const PLAYER_COLORS = ["#E53935", "#1E88E5", "#43A047", "#FB8C00"];
// Solución conocida. En producción se seleccionaría/generaría una por partida.
export const SOLUTION = [
    [5, 3, 4, 6, 7, 8, 9, 1, 2],
    [6, 7, 2, 1, 9, 5, 3, 4, 8],
    [1, 9, 8, 3, 4, 2, 5, 6, 7],
    [8, 5, 9, 7, 6, 1, 4, 2, 3],
    [4, 2, 6, 8, 5, 3, 7, 9, 1],
    [7, 1, 3, 9, 2, 4, 8, 5, 6],
    [9, 6, 1, 5, 3, 7, 2, 8, 4],
    [2, 8, 7, 4, 1, 9, 6, 3, 5],
    [3, 4, 5, 2, 8, 6, 1, 7, 9]
];
/** Genera una solución válida mediante permutaciones que preservan bloques 3x3. */
export function createRandomSolution() {
    const digits = shuffled([1, 2, 3, 4, 5, 6, 7, 8, 9]);
    const rows = shuffled([0, 1, 2]).flatMap((band) => shuffled([0, 1, 2]).map((row) => band * 3 + row));
    const columns = shuffled([0, 1, 2]).flatMap((stack) => shuffled([0, 1, 2]).map((column) => stack * 3 + column));
    return rows.map((row) => columns.map((column) => digits[SOLUTION[row][column] - 1]));
}
function shuffled(values) {
    const copy = [...values];
    for (let index = copy.length - 1; index > 0; index -= 1) {
        const target = Math.floor(Math.random() * (index + 1));
        [copy[index], copy[target]] = [copy[target], copy[index]];
    }
    return copy;
}
//# sourceMappingURL=constants.js.map