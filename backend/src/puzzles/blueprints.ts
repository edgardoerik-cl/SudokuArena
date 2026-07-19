import { SeededRandom } from "./random.js";
import { SPANISH_DICTIONARY } from "./spanishDictionary.js";
import type { CellValue, GameType, GenericCell, PuzzleBlueprint, PuzzleDifficulty, PuzzleGenerationOptions } from "./types.js";

export function createPuzzleBlueprint(gameType: GameType, options: PuzzleGenerationOptions = {}): PuzzleBlueprint {
  const difficulty = options.difficulty ?? "MEDIUM";
  const random = new SeededRandom(options.seed ?? `${gameType}-${Date.now()}-${Math.random()}`);
  switch (gameType) {
    case "MINESWEEPER": return minesweeper(random, difficulty);
    case "WORD_SEARCH": return wordSearch(random, difficulty);
    case "CROSSWORD": return crossword(random, difficulty);
    case "NONOGRAM": return nonogram(random, difficulty);
    case "DOTS_AND_BOXES": return dotsAndBoxes(difficulty);
    case "KAKURO": return kakuro(random, difficulty);
    case "MATHDOKU": return mathdoku(random, difficulty);
    case "HITORI": return hitori(random, difficulty);
    case "RUMMIKUB": return logicTiles(random, difficulty);
    case "NURIKABE": return nurikabe(random, difficulty);
    case "BRIDGES": return bridges(random, difficulty);
    case "SLITHERLINK": return slitherlink(random, difficulty);
    case "HANGMAN": return hangman(random, difficulty);
    case "ARROWS_ESCAPE": return arrowsEscape(random, difficulty);
    case "RHYTHM_JUMP": return { board: [[cell(null, true)]], answers: [[null]], meta: { actionMode: true, bpm: 128, difficulty } };
    case "CROSS_LETTERS": return crossLetters(random, difficulty);
    case "SECRET_CODE": return secretCode(random, difficulty);
    case "CAPITAL_ARENA": return capitalArena(random, difficulty);
    case "NEXUS_ZERO": return nexusZero(random, difficulty);
    case "ABYSS_ARENA": return { board: [[cell(null, true)]], answers: [[null]], meta: { actionMode: true, difficulty } };
    case "SUDOKU": return latinPuzzle(random, 9);
  }
}

/**
 * Nexo Cero: cada pareja ortogonal contiene cargas opuestas. El jugador enlaza
 * dos nodos cuya suma sea cero; ambos quedan conquistados simultáneamente.
 */
function nexusZero(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const rows = sizeFor(difficulty, 4, 6, 6, 8);
  const columns = 6;
  const board = matrix(rows, columns, () => cell(null, true));
  const answers = matrix<CellValue>(rows, columns, () => null);
  const partner = Array.from({ length: rows * columns }, (_, index) =>
    index % columns % 2 === 0 ? index + 1 : index - 1
  );
  // Barajado profundo por "domino flips": cada transformación 2×2 conserva
  // parejas ortogonales y, por tanto, garantiza que el puzzle siga resoluble.
  for (let pass = 0; pass < rows * columns * 12; pass += 1) {
    const row = random.int(0, rows - 2);
    const col = random.int(0, columns - 2);
    const a = row * columns + col;
    const b = a + 1;
    const c = a + columns;
    const d = c + 1;
    if (partner[a] === b && partner[c] === d) {
      partner[a] = c; partner[c] = a; partner[b] = d; partner[d] = b;
    } else if (partner[a] === c && partner[b] === d) {
      partner[a] = b; partner[b] = a; partner[c] = d; partner[d] = c;
    }
  }
  const assigned = new Set<number>();
  for (let index = 0; index < partner.length; index += 1) {
    if (assigned.has(index)) continue;
    const other = partner[index]!;
    const charge = random.int(1, 9) * (random.next() < .5 ? -1 : 1);
    const row = Math.floor(index / columns); const col = index % columns;
    const otherRow = Math.floor(other / columns); const otherCol = other % columns;
    board[row]![col]!.value = charge;
    board[otherRow]![otherCol]!.value = -charge;
    answers[row]![col] = `${otherRow}:${otherCol}`;
    answers[otherRow]![otherCol] = `${row}:${col}`;
    board[row]![col]!.meta = { charge: true };
    board[otherRow]![otherCol]!.meta = { charge: true };
    assigned.add(index); assigned.add(other);
  }
  return {
    board,
    answers,
    meta: {
      instructions: "Nexo Cero: enlaza dos cargas vecinas que sumen 0. Encadena rápido para dominar la matriz.",
      shufflePasses: rows * columns * 12,
      guaranteedSolvable: true,
      difficulty,
    },
  };
}

function sizeFor(difficulty: PuzzleDifficulty, easy: number, medium: number, hard: number, expert: number): number {
  return difficulty === "EASY" ? easy : difficulty === "HARD" ? hard : difficulty === "EXPERT" ? expert : medium;
}

function minesweeper(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 8, 10, 12, 14);
  const ratio = difficulty === "EASY" ? .10 : difficulty === "MEDIUM" ? .14 : difficulty === "HARD" ? .18 : .22;
  const count = Math.floor(size * size * ratio);
  const indices = random.shuffle(Array.from({ length: size * size }, (_, index) => index)).slice(0, count);
  const mines = new Set(indices);
  return {
    board: matrix(size, size, () => cell(null, false)),
    answers: matrix(size, size, (row, col) => mines.has(row * size + col)),
    meta: { mineCount: count, difficulty }
  };
}

function wordSearch(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 8, 10, 12, 14);
  const count = sizeFor(difficulty, 4, 5, 7, 9);
  const candidates = random.shuffle(SPANISH_DICTIONARY.filter((entry) => entry.word.length <= size));
  const letters = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ";
  const answers = matrix<CellValue>(size, size, () => null);
  const directions = random.shuffle([
    { row: 0, col: 1 }, { row: 0, col: -1 },
    { row: 1, col: 0 }, { row: -1, col: 0 },
    { row: 1, col: 1 }, { row: 1, col: -1 },
    { row: -1, col: 1 }, { row: -1, col: -1 }
  ]);
  const placements: Array<{
    word: string; startRow: number; startCol: number; endRow: number; endCol: number; rowStep: number; colStep: number;
  }> = [];

  for (const entry of candidates) {
    if (placements.length >= count) break;
    const preferred = directions[placements.length % directions.length]!;
    const attempts = [preferred, ...random.shuffle(directions.filter((direction) => direction !== preferred))];
    let placed = false;
    for (const direction of attempts) {
      for (let attempt = 0; attempt < 32 && !placed; attempt += 1) {
        const lastOffset = entry.word.length - 1;
        const minRow = direction.row < 0 ? lastOffset : 0;
        const maxRow = direction.row > 0 ? size - 1 - lastOffset : size - 1;
        const minCol = direction.col < 0 ? lastOffset : 0;
        const maxCol = direction.col > 0 ? size - 1 - lastOffset : size - 1;
        if (minRow > maxRow || minCol > maxCol) continue;
        const startRow = random.int(minRow, maxRow);
        const startCol = random.int(minCol, maxCol);
        const fits = [...entry.word].every((letter, offset) => {
          const current = answers[startRow + direction.row * offset]![startCol + direction.col * offset];
          return current === null || current === letter;
        });
        if (!fits) continue;
        [...entry.word].forEach((letter, offset) => {
          answers[startRow + direction.row * offset]![startCol + direction.col * offset] = letter;
        });
        placements.push({
          word: entry.word,
          startRow,
          startCol,
          endRow: startRow + direction.row * lastOffset,
          endCol: startCol + direction.col * lastOffset,
          rowStep: direction.row,
          colStep: direction.col
        });
        placed = true;
      }
      if (placed) break;
    }
  }
  answers.forEach((row) => row.forEach((value, col) => {
    if (value === null) row[col] = random.pick([...letters]);
  }));
  return {
    board: answers.map((row) => row.map((value) => cell(value, true))), answers,
    meta: { words: placements.map((placement) => placement.word), placements, foundWords: [], difficulty }
  };
}

function crossword(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 9, 11, 13, 15);
  const wordCount = sizeFor(difficulty, 5, 7, 9, 11);
  const selected = random.shuffle(SPANISH_DICTIONARY.filter((entry) => entry.word.length <= size - 2)).slice(0, wordCount);
  const answers = matrix<CellValue>(size, size, () => null);
  const placements: Array<{ word: string; clue: string; row: number; col: number; direction: string }> = [];
  selected.forEach((entry, index) => {
    const horizontal = index % 2 === 0;
    const row = horizontal ? (index * 2) % size : Math.max(0, Math.floor((size - entry.word.length) / 2));
    const col = horizontal ? Math.max(0, Math.floor((size - entry.word.length) / 2)) : (index * 2 + 1) % size;
    [...entry.word].forEach((letter, offset) => {
      const y = horizontal ? row : row + offset;
      const x = horizontal ? col + offset : col;
      if (y < size && x < size) answers[y]![x] = letter;
    });
    placements.push({ word: entry.word, clue: entry.clue, row, col, direction: horizontal ? "H" : "V" });
  });
  const starts = new Map(placements.map((placement, index) => [`${placement.row}:${placement.col}`, index + 1]));
  const board = answers.map((row, y) => row.map((answer, x) => answer === null
    ? blockedCell({ block: true })
    : cell(null, false, { clue: starts.get(`${y}:${x}`) ?? 0 })));
  return { board, answers, meta: { clues: placements.map((p, i) => `${i + 1}${p.direction}. ${p.clue}`), difficulty } };
}

function nonogram(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 6, 8, 10, 12);
  const density = difficulty === "EASY" ? .46 : .38;
  const answers = matrix<boolean>(size, size, (row, col) => {
    const mirror = Math.min(col, size - 1 - col);
    return new SeededRandom(`${row}:${mirror}:${random.int(0, 1_000_000)}`).next() < density;
  });
  return {
    board: answers.map((row) => row.map(() => cell(null, false))), answers,
    meta: { rowClues: answers.map(runClues), columnClues: transpose(answers).map(runClues), difficulty }
  };
}

function dotsAndBoxes(difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const boxes = sizeFor(difficulty, 3, 5, 6, 7);
  return {
    board: matrix(boxes, boxes, () => cell(null, false, edgeMeta())),
    answers: matrix<CellValue>(boxes, boxes, () => true),
    meta: { dots: boxes + 1, difficulty }
  };
}

function kakuro(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const playable = sizeFor(difficulty, 3, 4, 5, 6);
  const digits = random.shuffle([1, 2, 3, 4, 5, 6, 7, 8, 9]).slice(0, playable);
  const answers = matrix<CellValue>(playable + 1, playable + 1, (row, col) => row === 0 || col === 0 ? null : digits[(row + col - 2) % playable]!);
  const rowSum = digits.reduce((total, value) => total + value, 0);
  const board = matrix(playable + 1, playable + 1, (row, col) => row === 0 || col === 0
    ? blockedCell({ clueCell: true, downSum: row === 0 && col > 0 ? rowSum : 0, rightSum: col === 0 && row > 0 ? rowSum : 0 })
    : cell(null, false, { runLength: playable }));
  // Se parte de pocas pistas y un verificador por backtracking añade anclas
  // hasta que la matriz admita una sola solución.
  let anchorCount = difficulty === "EASY" ? 4 : difficulty === "MEDIUM" ? 3 : difficulty === "HARD" ? 2 : 1;
  const playableCells = random.shuffle(Array.from({ length: playable * playable }, (_, index) => ({
    row: Math.floor(index / playable) + 1,
    col: index % playable + 1
  })));
  const anchors = new Map<string, number>();
  for (const position of playableCells) {
    const safeVerificationThreshold = Math.max(anchorCount, playable * playable - playable);
    if (anchors.size >= safeVerificationThreshold && countKakuroLatinSolutions(playable, rowSum, anchors, 2) === 1) break;
    anchors.set(`${position.row - 1}:${position.col - 1}`, Number(answers[position.row]![position.col]));
  }
  anchorCount = anchors.size;
  for (const key of anchors.keys()) {
    const [sourceRow, sourceCol] = key.split(":").map(Number);
    const row = sourceRow! + 1;
    const col = sourceCol! + 1;
    board[row]![col] = { ...cell(answers[row]![col]!, true, { runLength: playable, given: true }), isBlocked: true };
  }
  return { board, answers, meta: { instructions: `Cada grupo suma ${rowSum} sin repetir. Los números dorados forman una solución verificada.`, initialClues: anchorCount, verifiedUnique: true, difficulty } };
}

function countKakuroLatinSolutions(
  size: number,
  targetSum: number,
  anchors: Map<string, number>,
  limit: number,
): number {
  const grid = matrix<number>(size, size, () => 0);
  let solutions = 0;
  const visit = (index: number): void => {
    if (solutions >= limit) return;
    if (index === size * size) { solutions += 1; return; }
    const row = Math.floor(index / size);
    const col = index % size;
    const fixed = anchors.get(`${row}:${col}`);
    const candidates = fixed == null ? [1, 2, 3, 4, 5, 6, 7, 8, 9] : [fixed];
    for (const value of candidates) {
      if (grid[row]!.includes(value) || grid.some((line) => line[col] === value)) continue;
      const rowPartial = grid[row]!.reduce((sum, digit) => sum + digit, 0) + value;
      const colPartial = grid.reduce((sum, line) => sum + line[col]!, 0) + value;
      if (rowPartial > targetSum || colPartial > targetSum) continue;
      if (col === size - 1 && rowPartial !== targetSum) continue;
      if (row === size - 1 && colPartial !== targetSum) continue;
      grid[row]![col] = value;
      visit(index + 1);
      grid[row]![col] = 0;
      if (solutions >= limit) return;
    }
  };
  visit(0);
  return solutions;
}

function mathdoku(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 4, 5, 6, 7);
  const symbols = random.shuffle(Array.from({ length: size }, (_, index) => index + 1));
  const source = matrix(size, size, (row, col) => symbols[(row + col) % size]!);
  const answers = source.map((row) => row.map((value) => value as CellValue));
  const board = matrix(size, size, (row, col) => {
    const start = col - col % 2;
    const pair = [source[row]![start]!, source[row]![Math.min(size - 1, start + 1)]!];
    const operation = random.pick(difficulty === "EASY" ? ["+"] : difficulty === "EXPERT" ? ["+", "−", "×", "÷"] : ["+", "−", "×"]);
    const target = operation === "+" ? pair[0]! + pair[1]! : operation === "×" ? pair[0]! * pair[1]!
      : operation === "−" ? Math.abs(pair[0]! - pair[1]!) : Math.max(...pair) / Math.min(...pair);
    return cell(null, false, { cageId: `${row}:${start}`, cageLabel: col === start ? `${Number.isInteger(target) ? target : pair[0]! + pair[1]!}${Number.isInteger(target) ? operation : "+"}` : "", cageStart: col === start, cageEnd: col === Math.min(size - 1, start + 1) });
  });
  return { board, answers, meta: { size, instructions: `Usa 1 a ${size}; cumple las jaulas sin repetir.`, difficulty } };
}

function hitori(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 5, 6, 7, 8);
  const values = matrix(size, size, (row, col) => ((row + col + random.int(0, size - 1)) % size) + 1);
  const black = new Set<string>();
  const target = sizeFor(difficulty, 4, 6, 9, 12);
  for (const index of random.shuffle(Array.from({ length: size * size }, (_, value) => value))) {
    const row = Math.floor(index / size); const col = index % size;
    if (black.size >= target) break;
    if ([`${row - 1}:${col}`, `${row + 1}:${col}`, `${row}:${col - 1}`, `${row}:${col + 1}`].some((key) => black.has(key))) continue;
    black.add(`${row}:${col}`);
    values[row]![col] = values[row]![(col + 1) % size]!;
  }
  return {
    board: values.map((row) => row.map((value) => cell(value, true))),
    answers: matrix<CellValue>(size, size, (row, col) => black.has(`${row}:${col}`)),
    meta: { action: "BLOCK_DUPLICATES", instructions: "Apaga duplicados; las negras no se tocan por lados.", difficulty }
  };
}

function logicTiles(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const rows = sizeFor(difficulty, 6, 8, 10, 12);
  const columns = 6;
  const colors = ["RED", "BLUE", "GREEN", "ORANGE"] as const;
  const answers = matrix<CellValue>(rows, columns, () => null);
  const board = matrix(rows, columns, (row, col) => {
    const color = random.pick(colors);
    const number = random.int(1, 9);
    answers[row]![col] = `${color}:${number}`;
    return cell(number, true, { arcadeTile: true, tileColor: color, spawnOrder: row * columns + col });
  });
  return {
    board,
    answers,
    meta: {
      colors,
      depositZones: ["RED", "BLUE", "GREEN", "ORANGE", "NUMBER"],
      fallIntervalMs: sizeFor(difficulty, 1_600, 1_250, 950, 700),
      instructions: "Arcade Match: arrastra fichas a depósitos y encadena tres del mismo color o número.",
      difficulty,
    },
  };
}

function logicChallenge(random: SeededRandom, operations: readonly string[]): { answer: number; operation: string; rule: string } {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const operation = random.pick(operations);
    const left = random.int(1, 9);
    const right = random.int(1, 9);
    const answer = operation === "SUM" ? left + right
      : operation === "AND" ? (left & right)
      : operation === "OR" ? (left | right)
      : (left ^ right);
    if (answer < 1 || answer > 9) continue;
    const symbol = operation === "SUM" ? "+" : operation;
    return { answer, operation, rule: `${left} ${symbol} ${right} = ?` };
  }
  return { answer: 2, operation: "SUM", rule: "1 + 1 = ?" };
}

function nurikabe(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 6, 8, 10, 12);
  const answers = matrix<boolean>(size, size, (row, col) => row % 2 === 1 ? col !== (row % 4 === 1 ? size - 1 : 0) : col % 3 === 2);
  const board = matrix(size, size, () => cell(null, false));
  let islandId = 0;
  for (let row = 0; row < size; row += 1) {
    for (let col = 0; col < size; col += 1) {
      if (answers[row]![col] || board[row]![col]!.meta.islandId != null) continue;
      const run: Array<[number, number]> = [];
      for (let x = col; x < size && !answers[row]![x]; x += 1) run.push([row, x]);
      islandId += 1;
      run.forEach(([y, x]) => { board[y]![x]!.meta.islandId = islandId; });
      const clue = random.pick(run);
      board[clue[0]]![clue[1]] = blockedCell({ islandId, islandSize: run.length, islandClue: true });
    }
  }
  return { board, answers, meta: { instructions: "Pinta el río conectado; no formes bloques negros 2×2.", difficulty } };
}

function bridges(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  // Difícil/Experto generan redes densas de 13×13 y 15×15.
  const islandGrid = sizeFor(difficulty, 4, 5, 7, 8);
  const size = islandGrid * 2 - 1;
  const answers = matrix<CellValue>(size, size, () => false);
  const board = matrix(size, size, () => cell(null, false, { bridge: true }));
  const islands: Array<[number, number]> = [];
  for (let y = 0; y < islandGrid; y += 1) for (let x = 0; x < islandGrid; x += 1) {
    if ((x + y) % 2 === 0 || random.next() > .35) islands.push([y * 2, x * 2]);
  }
  islands.forEach(([row, col], index) => {
    const neighbours = islands.filter(([y, x]) => (Math.abs(y - row) === 2 && x === col) || (Math.abs(x - col) === 2 && y === row));
    board[row]![col] = blockedCell({ island: true, islandId: index, bridgeCount: Math.max(1, neighbours.length) });
  });
  // Árbol de expansión: cada isla se conecta con la anterior alineada más cercana.
  islands.slice(1).forEach(([row, col], index) => {
    const previous = islands.slice(0, index + 1).filter(([y, x]) => y === row || x === col).at(-1);
    if (!previous) return;
    const midRow = (row + previous[0]) / 2; const midCol = (col + previous[1]) / 2;
    if (Number.isInteger(midRow) && Number.isInteger(midCol)) answers[midRow]![midCol] = true;
  });
  const islandKeys = new Set(islands.map(([row, col]) => `${row}:${col}`));
  for (const [row, col] of islands) {
    const validTargets: string[] = [];
    const directions: Array<readonly [number, number]> = [[-1, 0], [1, 0], [0, -1], [0, 1]];
    for (const [rowStep, colStep] of directions) {
      const targetRow = row + rowStep * 2; const targetCol = col + colStep * 2;
      const midRow = row + rowStep; const midCol = col + colStep;
      if (islandKeys.has(`${targetRow}:${targetCol}`) && answers[midRow]?.[midCol] === true) validTargets.push(`${targetRow}:${targetCol}`);
    }
    board[row]![col]!.meta.validTargets = validTargets;
    board[row]![col]!.meta.bridgeCount = validTargets.length;
  }
  return {
    board,
    answers,
    meta: {
      instructions: "Une islas alineadas; los puentes no se cruzan. La red experta es 15×15.",
      timeLimitSeconds: sizeFor(difficulty, 180, 150, 120, 90),
      denseLayout: true,
      difficulty,
    },
  };
}

function slitherlink(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  return connectDotsNeon(random, difficulty);
  /*
  const size = sizeFor(difficulty, 5, 7, 9, 11);
  // Construimos primero un lazo rectangular válido y después derivamos las pistas.
  // Los márgenes aleatorios cambian la solución sin arriesgar la continuidad del lazo.
  const maxInset = Math.max(0, Math.min(2, Math.floor(size / 4)));
  const top = random.int(0, maxInset);
  const left = random.int(0, maxInset);
  const bottom = random.int(size - 1 - maxInset, size - 1);
  const right = random.int(size - 1 - maxInset, size - 1);
  const answers = matrix<CellValue>(size, size, (row, col) => {
    const edges: string[] = [];
    if (row === top && col >= left && col <= right) edges.push("top");
    if (row === bottom && col >= left && col <= right) edges.push("bottom");
    if (col === left && row >= top && row <= bottom) edges.push("left");
    if (col === right && row >= top && row <= bottom) edges.push("right");
    return edges.join("|");
  });
  const board = matrix(size, size, (row, col) => {
    const clue = String(answers[row]![col]).split("|").filter(Boolean).length;
    const hideChance = difficulty === "EASY" ? .15 : difficulty === "EXPERT" ? .58 : .35;
    return cell(null, false, { ...edgeMeta(), clue: random.next() < hideChance ? -1 : clue });
  });
  return { board, answers, meta: { instructions: "Traza un único lazo; cada pista indica cuántos lados usa.", difficulty } };
  */
}

function connectDotsNeon(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 5, 6, 7, 8);
  const order: Array<[number, number]> = [];
  for (let row = 0; row < size; row += 1) {
    const columns = Array.from({ length: size }, (_, col) => col);
    if (row % 2 === 1) columns.reverse();
    for (const col of columns) order.push([row, col]);
  }
  if (random.next() < .5) order.reverse();
  const answers = matrix<CellValue>(size, size, () => null);
  const board = matrix(size, size, () => cell(null, true, { neonPoint: true }));
  order.forEach(([row, col], index) => {
    const next = order[index + 1];
    answers[row]![col] = next ? `${next[0]}:${next[1]}` : "END";
    board[row]![col]!.meta.pathIndex = index;
    board[row]![col]!.meta.start = index === 0;
  });
  return {
    board,
    answers,
    meta: { pathLength: order.length, instructions: "Conecta todos los puntos con una sola línea continua, sin cruzarla.", difficulty },
  };
}

function hangman(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const minimum = sizeFor(difficulty, 4, 6, 8, 10);
  const candidates = SPANISH_DICTIONARY.filter((entry) => entry.word.length >= minimum && entry.word.length <= 14);
  const entry = random.pick(candidates) ?? { word: "LABERINTO", clue: "Red de caminos con una salida." };
  const word = normalizeWord(entry.word);
  return {
    board: [[...word].map((_, index) => cell(null, false, { letterIndex: index }))],
    answers: [[...word]],
    meta: { wordLength: word.length, clue: entry.clue, maxErrors: 6, instructions: "Adivina letras. Seis errores eliminan al jugador.", difficulty },
  };
}

function arrowsEscape(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = sizeFor(difficulty, 5, 6, 7, 8);
  const board = matrix(size, size, () => cell(null, true));
  const answers = matrix<CellValue>(size, size, () => null);
  for (let row = 0; row < size; row += 1) for (let col = 0; col < size; col += 1) {
    const distances = [
      { direction: "UP", value: row }, { direction: "RIGHT", value: size - 1 - col },
      { direction: "DOWN", value: size - 1 - row }, { direction: "LEFT", value: col },
    ];
    const minimum = Math.min(...distances.map(({ value }) => value));
    const direction = random.pick(distances.filter(({ value }) => value === minimum)).direction;
    board[row]![col] = cell(direction, true, { arrow: direction, escapeDepth: minimum });
    answers[row]![col] = direction;
  }
  return {
    board,
    answers,
    meta: { totalBlocks: size * size, instructions: "Toca una flecha cuando toda su trayectoria esté libre.", difficulty },
  };
}

function rayCells(row: number, col: number, direction: string, size: number): Array<[number, number]> {
  const [dy, dx] = direction === "UP" ? [-1, 0] : direction === "RIGHT" ? [0, 1]
    : direction === "DOWN" ? [1, 0] : [0, -1];
  const result: Array<[number, number]> = [];
  for (let y = row + dy, x = col + dx; y >= 0 && y < size && x >= 0 && x < size; y += dy, x += dx) result.push([y, x]);
  return result;
}

export const SCRABBLE_SCORES: Record<string, number> = {
  A: 1, B: 3, C: 3, D: 2, E: 1, F: 4, G: 2, H: 4, I: 1, J: 8,
  L: 1, M: 3, N: 1, "Ñ": 8, O: 1, P: 3, Q: 5, R: 1, S: 1, T: 1,
  U: 1, V: 4, X: 8, Y: 4, Z: 10
};

function crossLetters(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = 15;
  const tripleWord = new Set(["0:0", "0:7", "0:14", "7:0", "7:14", "14:0", "14:7", "14:14"]);
  const doubleWord = new Set(["1:1", "2:2", "3:3", "4:4", "7:7", "10:10", "11:11", "12:12", "13:13", "1:13", "2:12", "3:11", "4:10", "10:4", "11:3", "12:2", "13:1"]);
  const tripleLetter = new Set(["1:5", "1:9", "5:1", "5:5", "5:9", "5:13", "9:1", "9:5", "9:9", "9:13", "13:5", "13:9"]);
  const doubleLetter = new Set(["0:3", "0:11", "2:6", "2:8", "3:0", "3:7", "3:14", "6:2", "6:6", "6:8", "6:12", "7:3", "7:11", "8:2", "8:6", "8:8", "8:12", "11:0", "11:7", "11:14", "12:6", "12:8", "14:3", "14:11"]);
  const board = matrix(size, size, (row, col) => {
    const key = `${row}:${col}`;
    const bonus = tripleWord.has(key) ? "TW" : doubleWord.has(key) ? "DW" : tripleLetter.has(key) ? "TL" : doubleLetter.has(key) ? "DL" : "NONE";
    return cell(null, false, { bonus, center: row === 7 && col === 7 });
  });
  // Cada partida empieza con una palabra distinta cruzando el centro. Así la
  // primera jugada ya tiene un ancla legal y no depende de que alguien conozca
  // una regla implícita de Scrabble.
  const centralCandidates = SPANISH_DICTIONARY
    .map((entry) => normalizeWord(entry.word))
    .filter((word) => word.length >= 4 && word.length <= 7);
  const centralWord = random.pick(centralCandidates) ?? "ARENA";
  const centralStart = 7 - Math.floor(centralWord.length / 2);
  [...centralWord].forEach((letter, index) => {
    const target = board[7]![centralStart + index]!;
    target.value = letter;
    target.isRevealed = true;
    target.meta = { ...target.meta, given: true, centralAnchor: true };
  });
  return {
    board,
    answers: matrix<CellValue>(size, size, () => null),
    meta: {
      letterScores: SCRABBLE_SCORES,
      centralWord,
      turnSeconds: difficulty === "EXPERT" ? 35 : difficulty === "HARD" ? 45 : 60,
      instructions: "Forma palabras españolas conectadas. DL/TL multiplican letras y DW/TW la palabra.",
      difficulty,
      seedHint: random.int(0, 999_999)
    }
  };
}

function secretCode(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const words = random.shuffle(SPANISH_DICTIONARY.map((entry) => normalizeWord(entry.word)))
    .filter((word, index, all) => word.length >= 3 && word.length <= 10 && all.indexOf(word) === index)
    .slice(0, 25);
  const fallback = ["ARENA", "LOGICA", "NUBE", "SOL", "LUNA", "RIO", "MAR", "FUEGO", "TIERRA", "AIRE", "CASA", "ARBOL", "RELOJ", "PUENTE", "CLAVE", "JUEGO", "EQUIPO", "ROJO", "AZUL", "PISTA", "MENTE", "CAMPO", "TORRE", "REY", "VIAJE"];
  while (words.length < 25) words.push(fallback[words.length]!);
  const identities = random.shuffle([...Array(8).fill("RED"), ...Array(8).fill("BLUE"), ...Array(8).fill("NEUTRAL"), "ASSASSIN"]) as string[];
  return {
    board: matrix(5, 5, (row, col) => cell(words[row * 5 + col]!, true, { wordIndex: row * 5 + col })),
    answers: matrix<CellValue>(5, 5, (row, col) => identities[row * 5 + col]!),
    meta: { instructions: "El capitán da una pista y un número. Los operativos eligen palabras; evita al asesino.", difficulty }
  };
}

const CAPITAL_NAMES = [
  "SALIDA", "Distrito Cian", "NEÓN", "Distrito Pixel", "Impuesto Red", "Estación Byte",
  "Avenida Quantum", "SUERTE", "Bulevar Vector", "Plaza Kernel", "CÁRCEL",
  "Barrio Nova", "Compañía Data", "Paseo Láser", "Jardín Holo", "Estación Cloud",
  "Puerto Crypto", "DESTINO", "Mercado Bot", "Torre Lógica", "PARKING",
  "Isla Matrix", "SUERTE", "Centro Arcade", "Ciudad Prisma", "Estación Socket",
  "Paseo Android", "Avenida Compose", "Compañía Node", "Fortaleza Arena", "IR A CÁRCEL",
  "Distrito Synth", "Distrito Lumen", "DESTINO", "Metrópolis IA", "Estación Orbit",
  "Paseo Zenith", "Impuesto Cloud", "Capital Neón", "Palacio Arena",
] as const;

function capitalArena(random: SeededRandom, difficulty: PuzzleDifficulty): PuzzleBlueprint {
  const size = 11;
  const coordinates: Array<{ row: number; col: number }> = [];
  for (let col = 10; col >= 0; col -= 1) coordinates.push({ row: 10, col });
  for (let row = 9; row >= 0; row -= 1) coordinates.push({ row, col: 0 });
  for (let col = 1; col <= 10; col += 1) coordinates.push({ row: 0, col });
  for (let row = 1; row <= 9; row += 1) coordinates.push({ row, col: 10 });
  const special: Record<number, string> = {
    0: "START", 2: "CHANCE", 4: "TAX", 7: "CHANCE", 10: "JAIL", 12: "UTILITY",
    17: "CHANCE", 20: "PARKING", 22: "CHANCE", 28: "UTILITY", 30: "GO_TO_JAIL",
    33: "CHANCE", 37: "TAX",
  };
  const stations = new Set([5, 15, 25, 35]);
  const spaces = CAPITAL_NAMES.map((name, index) => {
    const type = special[index] ?? (stations.has(index) ? "STATION" : "PROPERTY");
    const price = type === "PROPERTY" ? 100 + Math.floor(index / 5) * 25 + random.int(0, 3) * 10
      : type === "STATION" ? 200 : type === "UTILITY" ? 150 : 0;
    return { index, name, type, price, rent: price > 0 ? Math.max(12, Math.round(price * .12)) : 0 };
  });
  const byCoordinate = new Map(coordinates.map((coordinate, index) => [`${coordinate.row}:${coordinate.col}`, spaces[index]!]));
  const board = matrix(size, size, (row, col) => {
    const space = byCoordinate.get(`${row}:${col}`);
    return space
      ? cell(null, true, { index: space.index, name: space.name, type: space.type, price: space.price, rent: space.rent })
      : blockedCell({ capitalCenter: true });
  });
  return {
    board,
    answers: matrix<CellValue>(size, size, () => null),
    meta: {
      spaces,
      instructions: "Lanza dos dados, compra distritos, cobra rentas y construye mejoras de hackeo.",
      difficulty,
    },
  };
}

function latinPuzzle(random: SeededRandom, size: number): PuzzleBlueprint {
  const digits = random.shuffle(Array.from({ length: size }, (_, index) => index + 1));
  const answers = matrix<CellValue>(size, size, (row, col) => digits[(row + col) % size]!);
  return { board: matrix(size, size, () => cell(null, false)), answers, meta: { size } };
}

function edgeMeta(): GenericCell["meta"] { return { top: false, right: false, bottom: false, left: false }; }
function cell(value: CellValue, isRevealed: boolean, meta: GenericCell["meta"] = {}): GenericCell { return { value, isRevealed, ownerId: null, isBlocked: false, meta }; }
function blockedCell(meta: GenericCell["meta"] = {}): GenericCell { return { value: null, isRevealed: true, ownerId: null, isBlocked: true, meta }; }
function matrix<T>(rows: number, columns: number, create: (row: number, col: number) => T): T[][] { return Array.from({ length: rows }, (_, row) => Array.from({ length: columns }, (_, col) => create(row, col))); }
function runClues(values: boolean[]): number[] { const result: number[] = []; let run = 0; for (const value of values) { if (value) run += 1; else if (run) { result.push(run); run = 0; } } if (run) result.push(run); return result.length ? result : [0]; }
function transpose<T>(value: T[][]): T[][] { return value[0]!.map((_, col) => value.map((row) => row[col]!)); }
function normalizeWord(value: string): string { return value.trim().toUpperCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^A-ZÑ]/g, ""); }
