export const BOARD_SIZE = 9;
export const MAX_PLAYERS = 4;
export const CELL_POINTS = 10;
export const SECTION_POINTS = 100;
export const PENALTY_MS = 3_000;
export const CLEAR_DELAY_MS = 1_000;

export const PLAYER_COLORS = ["#E53935", "#1E88E5", "#43A047", "#FB8C00"] as const;

// Solución conocida. En producción se seleccionaría/generaría una por partida.
export const SOLUTION: readonly (readonly number[])[] = [
  [5, 3, 4, 6, 7, 8, 9, 1, 2],
  [6, 7, 2, 1, 9, 5, 3, 4, 8],
  [1, 9, 8, 3, 4, 2, 5, 6, 7],
  [8, 5, 9, 7, 6, 1, 4, 2, 3],
  [4, 2, 6, 8, 5, 3, 7, 9, 1],
  [7, 1, 3, 9, 2, 4, 8, 5, 6],
  [9, 6, 1, 5, 3, 7, 2, 8, 4],
  [2, 8, 7, 4, 1, 9, 6, 3, 5],
  [3, 4, 5, 2, 8, 6, 1, 7, 9]
] as const;
