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
    case "KAKURO": return kakuro();
    case "MATHDOKU": return mathdoku();
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
    answer === null ? blockedCell({ block: true }) : cell(null, false, { clue: col === 0 ? rowIndex + 1 : 0 })
  ));
  return { board, answers, meta: { clues: [
    "1. Lugar donde compiten los jugadores",
    "2. Razonamiento necesario para resolver",
    "3. Luz de color muy brillante",
    "4. Organización rectangular de datos",
    "5. Rompecabezas en inglés",
    "6. Actividad con reglas y objetivos",
    "7. Propiedad visual de cada equipo",
    "8. Habilidad especial que consume energía",
    "9. Capacidad para razonar"
  ] } };
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

function kakuro(): PuzzleBlueprint {
  const size = 5;
  const answers = matrix<CellValue>(size, size, (row, col) => row === 0 || col === 0 ? null : ((row + col - 2) % 4) + 1);
  const board = matrix(size, size, (row, col) => {
    if (row === 0 && col === 0) return blockedCell({ clueCell: true });
    if (row === 0) return blockedCell({ clueCell: true, downSum: 10 });
    if (col === 0) return blockedCell({ clueCell: true, rightSum: 10 });
    return cell(null, false, { runLength: 4 });
  });
  return { board, answers, meta: { instructions: "Cada fila y columna suma 10 sin repetir cifras." } };
}

function mathdoku(): PuzzleBlueprint {
  const size = 6;
  const source = matrix(size, size, (row, col) => ((row + col) % size) + 1);
  const answers = source.map((row) => row.map((value) => value as CellValue));
  const board = matrix(size, size, (row, col) => {
    const cageStart = col - col % 2;
    const cageId = row * 3 + Math.floor(col / 2);
    const target = source[row]![cageStart]! + source[row]![cageStart + 1]!;
    return cell(null, false, {
      cageId,
      cageLabel: col % 2 === 0 ? `${target}+` : "",
      cageStart: col % 2 === 0,
      cageEnd: col % 2 === 1
    });
  });
  return { board, answers, meta: { size, instructions: "Usa 1 a 6 sin repetir por fila o columna y cumple cada jaula." } };
}

function hitori(): PuzzleBlueprint {
  const size = 6;
  const black = new Set(["0:0", "0:3", "2:1", "2:4", "4:0", "4:3"]);
  const values = matrix(size, size, (row, col) => ((row + col) % size) + 1);
  for (const key of black) {
    const [row, col] = key.split(":").map(Number) as [number, number];
    values[row]![col] = values[row]![(col + 1) % size]!;
  }
  const answers = matrix<CellValue>(size, size, (row, col) => black.has(`${row}:${col}`));
  const board = values.map((row) => row.map((value) => cell(value, true)));
  return { board, answers, meta: { action: "BLOCK_DUPLICATES", instructions: "Apaga duplicados sin dejar casillas negras adyacentes." } };
}

function rummikub(): PuzzleBlueprint {
  const colors = ["RED", "BLUE", "GREEN", "ORANGE"];
  const starts = [1, 3, 5, 7];
  const answers = matrix<CellValue>(4, 7, (row, col) => starts[row]! + col);
  const board = matrix(4, 7, (row) => cell(null, false, { tileColor: colors[row]!, meld: "RUN" }));
  return { board, answers, meta: { colors, operations: ["PLACE", "MOVE", "GROUP", "RUN"], instructions: "Completa las cuatro escaleras de siete fichas." } };
}

function cell(value: CellValue, isRevealed: boolean, meta: GenericCell["meta"] = {}): GenericCell {
  return { value, isRevealed, ownerId: null, isBlocked: false, meta };
}

function blockedCell(meta: GenericCell["meta"] = {}): GenericCell {
  return { value: null, isRevealed: true, ownerId: null, isBlocked: true, meta };
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
