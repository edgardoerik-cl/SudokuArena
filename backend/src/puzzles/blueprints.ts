import type { CellValue, GameType, GenericCell, PuzzleBlueprint } from "./types.js";

const LATIN_9 = Array.from({ length: 9 }, (_, row) =>
  Array.from({ length: 9 }, (_, column) => ((row * 3 + Math.floor(row / 3) + column) % 9) + 1)
);

export function createPuzzleBlueprint(gameType: GameType): PuzzleBlueprint {
  switch (gameType) {
    case "MINESWEEPER": return minesweeper();
    case "WORD_SEARCH": return wordSearch();
    case "CROSSWORD": return crossword();
    case "NONOGRAM": return nonogram();
    case "DOTS_AND_BOXES": return dotsAndBoxes();
    case "KAKURO": return numberPuzzle("sum", 9);
    case "MATHDOKU": return numberPuzzle("cage", 6);
    case "HITORI": return hitori();
    case "RUMMIKUB": return rummikub();
    case "SUDOKU": return numberPuzzle("sudoku", 9);
  }
}

function minesweeper(): PuzzleBlueprint {
  const rows = 10;
  const columns = 10;
  const mines = new Set([3, 9, 14, 27, 31, 45, 52, 68, 74, 91, 96]);
  const answers = matrix(rows, columns, (row, col) => mines.has(row * columns + col));
  const board = matrix(rows, columns, () => cell(null, false));
  return { board, answers, meta: { mineCount: mines.size } };
}

function wordSearch(): PuzzleBlueprint {
  const words = ["ARENA", "LOGICA", "NEON", "MATRIZ", "PUZZLE"];
  const filler = "QWERTYUIOPASDFGHJKLZXCVBNM";
  const answers = matrix<CellValue>(10, 10, (row, col) =>
    row < words.length && col < words[row]!.length ? words[row]![col]! : filler[(row * 7 + col * 11) % filler.length]!
  );
  const board = answers.map((row) => row.map((value) => cell(value, true)));
  return { board, answers, meta: { words, foundWords: [] } };
}

function crossword(): PuzzleBlueprint {
  const rows = ["ARENA#RED", "LOGICA###", "NEON#BOT#", "MATRIZ###", "PUZZLE###", "JUEGO####", "COLOR####", "PODER####", "MENTE####"];
  const answers = rows.map((row) => row.padEnd(9, "#").slice(0, 9).split('').map((value) => value === "#" ? null : value));
  const board = answers.map((row, rowIndex) => row.map((answer, col) =>
    answer === null ? cell(null, true, { block: true }) : cell(null, false, { clue: col === 0 ? rowIndex + 1 : 0 })
  ));
  return { board, answers, meta: { clues: rows.map((_, index) => `${index + 1}. Palabra de la Arena`) } };
}

function nonogram(): PuzzleBlueprint {
  const pattern = ["00111100", "01111110", "11011011", "11111111", "01111110", "00111100", "00011000", "00111100"];
  const answers = pattern.map((row) => row.split('').map((value) => value === "1"));
  const board = answers.map((row) => row.map(() => cell(null, false)));
  return { board, answers, meta: { rowClues: answers.map(runClues), columnClues: transpose(answers).map(runClues) } };
}

function dotsAndBoxes(): PuzzleBlueprint {
  const board = matrix(5, 5, () => cell(null, false, { top: false, right: false, bottom: false, left: false }));
  const answers = matrix<CellValue>(5, 5, () => true);
  return { board, answers, meta: { dots: 6 } };
}

function numberPuzzle(kind: string, size: number): PuzzleBlueprint {
  const source = size === 9 ? LATIN_9 : matrix(size, size, (row, col) => ((row + col) % size) + 1);
  const answers = source.map((row) => row.map((value) => value as CellValue));
  const board = matrix(size, size, (row, col) => cell(null, false, {
    kind,
    clue: kind === "sum" && (row === 0 || col === 0) ? source[row]!.reduce((total, value) => total + value, 0) : 0,
    cage: kind === "cage" ? Math.floor((row * size + col) / 2) : -1
  }));
  return { board, answers, meta: { kind, size } };
}

function hitori(): PuzzleBlueprint {
  const answers = matrix<CellValue>(8, 8, (row, col) => (row + col) % 5 === 0);
  const board = matrix(8, 8, (row, col) => cell(((row * 2 + col) % 8) + 1, true));
  return { board, answers, meta: { action: "BLOCK_DUPLICATES" } };
}

function rummikub(): PuzzleBlueprint {
  const colors = ["RED", "BLUE", "GREEN", "ORANGE"];
  const answers = matrix<CellValue>(4, 13, (_, col) => col + 1);
  const board = matrix(4, 13, (row) => cell(null, false, { tileColor: colors[row]! }));
  return { board, answers, meta: { colors, operations: ["PLACE", "MOVE", "GROUP", "RUN"] } };
}

function cell(value: CellValue, isRevealed: boolean, meta: GenericCell["meta"] = {}): GenericCell {
  return { value, isRevealed, ownerId: null, isBlocked: false, meta };
}

function matrix<T>(rows: number, columns: number, create: (row: number, col: number) => T): T[][] {
  return Array.from({ length: rows }, (_, row) => Array.from({ length: columns }, (_, col) => create(row, col)));
}

function runClues(values: boolean[]): number[] {
  const result: number[] = [];
  let run = 0;
  for (const value of values) {
    if (value) run += 1;
    else if (run > 0) { result.push(run); run = 0; }
  }
  if (run > 0) result.push(run);
  return result.length > 0 ? result : [0];
}

function transpose<T>(matrixValue: T[][]): T[][] {
  return matrixValue[0]!.map((_, column) => matrixValue.map((row) => row[column]!));
}
