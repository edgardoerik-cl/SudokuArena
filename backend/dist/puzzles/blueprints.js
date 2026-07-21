import { SeededRandom } from "./random.js";
import { SPANISH_DICTIONARY } from "./spanishDictionary.js";
export function createPuzzleBlueprint(gameType, options = {}) {
    const difficulty = options.difficulty ?? "MEDIUM";
    const random = new SeededRandom(options.seed ?? `${gameType}-${Date.now()}-${Math.random()}`);
    switch (gameType) {
        case "MINESWEEPER": return minesweeper(random, difficulty);
        case "WORD_SEARCH": return wordSearch(random, difficulty);
        case "CROSSWORD": return crossword(random, difficulty);
        case "TIC_TAC_TOE": return ticTacToe(difficulty);
        case "DOTS_AND_BOXES": return dotsAndBoxes(difficulty);
        case "KAKURO": return kakuro(random, difficulty);
        case "MATHDOKU": return mathdoku(random, difficulty);
        case "HITORI": return hitori(random, difficulty);
        case "CHESS_TACTICS": return chessTactics(difficulty);
        case "NURIKABE": return nurikabe(random, difficulty);
        case "BRIDGES": return bridges(random, difficulty);
        case "TETRIS_ARENA": return { board: matrix(20, 10, () => cell(null, false)), answers: matrix(20, 10, () => null), meta: { actionMode: true, engine: "TETRIS_7_BAG", difficulty } };
        case "HANGMAN": return hangman(random, difficulty);
        case "ARROWS_ESCAPE": return arrowsEscape(random, difficulty);
        case "PACMAN_ARENA": return { board: [[cell(null, true)]], answers: [[null]], meta: { actionMode: true, engine: "PACMAN_TILEMAP", difficulty } };
        case "CROSS_LETTERS": return crossLetters(random, difficulty);
        case "SECRET_CODE": return secretCode(random, difficulty);
        case "CAPITAL_ARENA": return capitalArena(random, difficulty);
        case "NEXUS_ZERO": return nexusZero(random, difficulty);
        case "CHECKERS": return checkers(difficulty);
        case "DEMOLITION_ARCADE": return { board: [[cell(null, true)]], answers: [[null]], meta: { actionMode: true, engine: "BREAKOUT_PHYSICS", difficulty } };
        case "MEMORY_NEON": return memoryNeon(random, difficulty);
        case "MERGE_2048": return merge2048(random, difficulty);
        case "TOWER_DEFENSE": return towerDefense(random, difficulty);
        case "REACTOR_CHAIN": return reactorChain(random, difficulty);
        case "SUDOKU": return latinPuzzle(random, 9);
    }
}
/** Carrera de memoria compartida: las respuestas nunca salen en el snapshot. */
function memoryNeon(random, difficulty) {
    const [rows, columns] = difficulty === "EASY" ? [4, 4]
        : difficulty === "EXPERT" ? [6, 6] : [4, 6];
    const pairCount = rows * columns / 2;
    const symbols = ["◆", "●", "▲", "★", "☀", "☾", "⚡", "✦", "⬢", "♣", "♥", "♠", "♫", "☂", "✿", "☯", "☕", "∞"];
    const shuffled = random.shuffle(symbols.slice(0, pairCount).flatMap((symbol) => [symbol, symbol]));
    return {
        board: matrix(rows, columns, (_row, _col) => cell(null, false, { card: true })),
        answers: matrix(rows, columns, (row, col) => shuffled[row * columns + col]),
        meta: {
            instructions: "Encuentra parejas. La primera carta queda visible; si aciertas, ambas quedan conquistadas.",
            pairCount,
            difficulty,
        },
    };
}
/** 2048 competitivo compartido. El servidor serializa cada deslizamiento. */
function merge2048(random, difficulty) {
    const board = matrix(4, 4, () => cell(null, false));
    const open = random.shuffle(Array.from({ length: 16 }, (_, index) => index)).slice(0, 2);
    open.forEach((index) => {
        board[Math.floor(index / 4)][index % 4].value = random.next() < .9 ? 2 : 4;
        board[Math.floor(index / 4)][index % 4].isRevealed = true;
    });
    const target = difficulty === "EASY" ? 128 : difficulty === "MEDIUM" ? 256
        : difficulty === "HARD" ? 512 : 1024;
    return {
        board,
        answers: matrix(4, 4, () => null),
        meta: {
            actionMode: true,
            engine: "MERGE_2048",
            target,
            instructions: `Combina fichas iguales y alcanza ${target}. Desliza o usa las flechas.`,
            difficulty,
        },
    };
}
function ticTacToe(difficulty) {
    return {
        board: matrix(3, 3, () => cell(null, false)),
        answers: matrix(3, 3, () => null),
        meta: { turnBased: true, marks: ["X", "O"], difficulty },
    };
}
function checkers(difficulty) {
    const board = matrix(8, 8, (row, col) => {
        const playable = (row + col) % 2 === 1;
        if (!playable)
            return blockedCell({ playable: false });
        const team = row <= 2 ? "BLUE" : row >= 5 ? "RED" : null;
        return cell(team ? `${team}_MAN` : null, team !== null, {
            playable: true,
            team,
            king: false,
        });
    });
    return {
        board,
        answers: matrix(8, 8, () => null),
        meta: {
            turnBased: true,
            mandatoryCapture: true,
            flyingKings: true,
            instructions: "Las capturas son obligatorias. Encadena saltos y corona una reina al alcanzar el extremo.",
            difficulty,
        },
    };
}
function chessTactics(difficulty) {
    const board = matrix(8, 8, () => cell(null, false));
    let id = 0;
    const add = (row, col, team, type) => {
        const stats = {
            PAWN: { hp: 70, maxHp: 70, ap: 3, maxAp: 3, defense: 8 },
            KNIGHT: { hp: 100, maxHp: 100, ap: 4, maxAp: 4, defense: 12 },
            BISHOP: { hp: 85, maxHp: 85, ap: 4, maxAp: 4, defense: 9 },
            ROOK: { hp: 135, maxHp: 135, ap: 3, maxAp: 3, defense: 18 },
            QUEEN: { hp: 110, maxHp: 110, ap: 5, maxAp: 5, defense: 11 },
            KING: { hp: 160, maxHp: 160, ap: 2, maxAp: 2, defense: 20 },
        }[type];
        board[row][col] = cell(type, true, {
            pieceId: `${team}-${type}-${++id}`,
            team,
            owner: team,
            type,
            ...stats,
            statusEffects: [],
            currentCooldown: 0,
            isShielded: false,
            hasEvasion: type === "KNIGHT",
            canActThisTurn: false,
            hasMoved: false,
            ambushTarget: null,
        });
    };
    for (const col of [0, 1, 2, 3, 4, 5, 6, 7]) {
        add(1, col, "BLUE", "PAWN");
        add(6, col, "RED", "PAWN");
    }
    const backRank = ["ROOK", "KNIGHT", "BISHOP", "QUEEN", "KING", "BISHOP", "KNIGHT", "ROOK"];
    backRank.forEach((type, col) => {
        add(0, col, "BLUE", type);
        add(7, col, "RED", type);
    });
    return {
        board,
        answers: matrix(8, 8, () => null),
        meta: {
            turnBased: true,
            blueMoves: "movimiento",
            redMoves: "ataque",
            instructions: "Elige movimiento clásico O habilidad. Peón: Falange; Caballo: Salto Sísmico; Alfil: Rayo; Torre: Muro; Reina: Intimidación; Rey: Revivir.",
            difficulty,
        },
    };
}
function nexusZero(random, difficulty) {
    const size = sizeFor(difficulty, 5, 6, 7, 8);
    const board = matrix(size, size, () => cell(null, false));
    const answers = matrix(size, size, () => null);
    const pairsPerRow = Math.max(1, Math.floor(size * .36));
    const pairCount = pairsPerRow * size;
    // Los huecos y orientaciones cambian, pero cada fila conserva pares opuestos
    // en orden de compactación. El tablero siempre tiene una solución por swipes.
    for (let row = 0; row < size; row += 1) {
        const columns = random.shuffle(Array.from({ length: size }, (_, index) => index))
            .slice(0, pairsPerRow * 2).sort((a, b) => a - b);
        for (let pair = 0; pair < pairsPerRow; pair += 1) {
            const value = random.int(1, 9);
            const ordered = random.next() < .5 ? [value, -value] : [-value, value];
            board[row][columns[pair * 2]] = cell(ordered[0], true, { charge: true });
            board[row][columns[pair * 2 + 1]] = cell(ordered[1], true, { charge: true });
        }
    }
    return {
        board,
        answers,
        meta: {
            actionMode: true,
            engine: "NEXUS_SWIPE",
            instructions: "Desliza todas las cargas. Solo +N y -N se fusionan; cada Nexo Cero elimina ambas fichas.",
            pairCount,
            nexusRound: 1,
            nexusTargetRounds: sizeFor(difficulty, 3, 4, 5, 6),
            guaranteedSolvable: true,
            difficulty,
        },
    };
}
function sizeFor(difficulty, easy, medium, hard, expert) {
    return difficulty === "EASY" ? easy : difficulty === "HARD" ? hard : difficulty === "EXPERT" ? expert : medium;
}
function minesweeper(random, difficulty) {
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
function wordSearch(random, difficulty) {
    const size = sizeFor(difficulty, 8, 10, 12, 14);
    const count = sizeFor(difficulty, 4, 5, 7, 9);
    const candidates = random.shuffle(SPANISH_DICTIONARY.filter((entry) => entry.word.length <= size));
    const letters = "ABCDEFGHIJKLMNÑOPQRSTUVWXYZ";
    const answers = matrix(size, size, () => null);
    const directions = random.shuffle([
        { row: 0, col: 1 }, { row: 0, col: -1 },
        { row: 1, col: 0 }, { row: -1, col: 0 },
        { row: 1, col: 1 }, { row: 1, col: -1 },
        { row: -1, col: 1 }, { row: -1, col: -1 }
    ]);
    const placements = [];
    for (const entry of candidates) {
        if (placements.length >= count)
            break;
        const preferred = directions[placements.length % directions.length];
        const attempts = [preferred, ...random.shuffle(directions.filter((direction) => direction !== preferred))];
        let placed = false;
        for (const direction of attempts) {
            for (let attempt = 0; attempt < 32 && !placed; attempt += 1) {
                const lastOffset = entry.word.length - 1;
                const minRow = direction.row < 0 ? lastOffset : 0;
                const maxRow = direction.row > 0 ? size - 1 - lastOffset : size - 1;
                const minCol = direction.col < 0 ? lastOffset : 0;
                const maxCol = direction.col > 0 ? size - 1 - lastOffset : size - 1;
                if (minRow > maxRow || minCol > maxCol)
                    continue;
                const startRow = random.int(minRow, maxRow);
                const startCol = random.int(minCol, maxCol);
                const fits = [...entry.word].every((letter, offset) => {
                    const current = answers[startRow + direction.row * offset][startCol + direction.col * offset];
                    return current === null || current === letter;
                });
                if (!fits)
                    continue;
                [...entry.word].forEach((letter, offset) => {
                    answers[startRow + direction.row * offset][startCol + direction.col * offset] = letter;
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
            if (placed)
                break;
        }
    }
    answers.forEach((row) => row.forEach((value, col) => {
        if (value === null)
            row[col] = random.pick([...letters]);
    }));
    return {
        board: answers.map((row) => row.map((value) => cell(value, true))), answers,
        meta: { words: placements.map((placement) => placement.word), placements, foundWords: [], difficulty }
    };
}
function crossword(random, difficulty) {
    const size = sizeFor(difficulty, 9, 11, 13, 15);
    const wordCount = sizeFor(difficulty, 5, 7, 9, 11);
    const selected = random.shuffle(SPANISH_DICTIONARY.filter((entry) => entry.word.length <= size - 2)).slice(0, wordCount);
    const answers = matrix(size, size, () => null);
    const placements = [];
    selected.forEach((entry, index) => {
        const horizontal = index % 2 === 0;
        const row = horizontal ? (index * 2) % size : Math.max(0, Math.floor((size - entry.word.length) / 2));
        const col = horizontal ? Math.max(0, Math.floor((size - entry.word.length) / 2)) : (index * 2 + 1) % size;
        [...entry.word].forEach((letter, offset) => {
            const y = horizontal ? row : row + offset;
            const x = horizontal ? col + offset : col;
            if (y < size && x < size)
                answers[y][x] = letter;
        });
        placements.push({ word: entry.word, clue: entry.clue, row, col, direction: horizontal ? "H" : "V" });
    });
    const starts = new Map(placements.map((placement, index) => [`${placement.row}:${placement.col}`, index + 1]));
    const board = answers.map((row, y) => row.map((answer, x) => answer === null
        ? blockedCell({ block: true })
        : cell(null, false, { clue: starts.get(`${y}:${x}`) ?? 0 })));
    return { board, answers, meta: { clues: placements.map((p, i) => `${i + 1}${p.direction}. ${p.clue}`), difficulty } };
}
function dotsAndBoxes(difficulty) {
    const boxes = sizeFor(difficulty, 3, 5, 6, 7);
    return {
        board: matrix(boxes, boxes, () => cell(null, false, edgeMeta())),
        answers: matrix(boxes, boxes, () => true),
        meta: { dots: boxes + 1, difficulty }
    };
}
function kakuro(random, difficulty) {
    const playable = sizeFor(difficulty, 3, 4, 5, 6);
    const digits = random.shuffle([1, 2, 3, 4, 5, 6, 7, 8, 9]).slice(0, playable);
    const answers = matrix(playable + 1, playable + 1, (row, col) => row === 0 || col === 0 ? null : digits[(row + col - 2) % playable]);
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
    const anchors = new Map();
    for (const position of playableCells) {
        const safeVerificationThreshold = Math.max(anchorCount, playable * playable - playable);
        if (anchors.size >= safeVerificationThreshold && countKakuroLatinSolutions(playable, rowSum, anchors, 2) === 1)
            break;
        anchors.set(`${position.row - 1}:${position.col - 1}`, Number(answers[position.row][position.col]));
    }
    anchorCount = anchors.size;
    for (const key of anchors.keys()) {
        const [sourceRow, sourceCol] = key.split(":").map(Number);
        const row = sourceRow + 1;
        const col = sourceCol + 1;
        board[row][col] = { ...cell(answers[row][col], true, { runLength: playable, given: true }), isBlocked: true };
    }
    return { board, answers, meta: { instructions: `Cada grupo suma ${rowSum} sin repetir. Los números dorados forman una solución verificada.`, initialClues: anchorCount, verifiedUnique: true, difficulty } };
}
function countKakuroLatinSolutions(size, targetSum, anchors, limit) {
    const grid = matrix(size, size, () => 0);
    let solutions = 0;
    const visit = (index) => {
        if (solutions >= limit)
            return;
        if (index === size * size) {
            solutions += 1;
            return;
        }
        const row = Math.floor(index / size);
        const col = index % size;
        const fixed = anchors.get(`${row}:${col}`);
        const candidates = fixed == null ? [1, 2, 3, 4, 5, 6, 7, 8, 9] : [fixed];
        for (const value of candidates) {
            if (grid[row].includes(value) || grid.some((line) => line[col] === value))
                continue;
            const rowPartial = grid[row].reduce((sum, digit) => sum + digit, 0) + value;
            const colPartial = grid.reduce((sum, line) => sum + line[col], 0) + value;
            if (rowPartial > targetSum || colPartial > targetSum)
                continue;
            if (col === size - 1 && rowPartial !== targetSum)
                continue;
            if (row === size - 1 && colPartial !== targetSum)
                continue;
            grid[row][col] = value;
            visit(index + 1);
            grid[row][col] = 0;
            if (solutions >= limit)
                return;
        }
    };
    visit(0);
    return solutions;
}
function mathdoku(random, difficulty) {
    const size = sizeFor(difficulty, 4, 5, 6, 7);
    const symbols = random.shuffle(Array.from({ length: size }, (_, index) => index + 1));
    const source = matrix(size, size, (row, col) => symbols[(row + col) % size]);
    const answers = source.map((row) => row.map((value) => value));
    const board = matrix(size, size, (row, col) => {
        const start = col - col % 2;
        const pair = [source[row][start], source[row][Math.min(size - 1, start + 1)]];
        const operation = random.pick(difficulty === "EASY" ? ["+"] : difficulty === "EXPERT" ? ["+", "−", "×", "÷"] : ["+", "−", "×"]);
        const target = operation === "+" ? pair[0] + pair[1] : operation === "×" ? pair[0] * pair[1]
            : operation === "−" ? Math.abs(pair[0] - pair[1]) : Math.max(...pair) / Math.min(...pair);
        return cell(null, false, { cageId: `${row}:${start}`, cageLabel: col === start ? `${Number.isInteger(target) ? target : pair[0] + pair[1]}${Number.isInteger(target) ? operation : "+"}` : "", cageStart: col === start, cageEnd: col === Math.min(size - 1, start + 1) });
    });
    return { board, answers, meta: { size, instructions: `Usa 1 a ${size}; cumple las jaulas sin repetir.`, difficulty } };
}
function hitori(random, difficulty) {
    const size = sizeFor(difficulty, 5, 6, 7, 8);
    const values = matrix(size, size, (row, col) => ((row + col + random.int(0, size - 1)) % size) + 1);
    const black = new Set();
    const target = sizeFor(difficulty, 4, 6, 9, 12);
    for (const index of random.shuffle(Array.from({ length: size * size }, (_, value) => value))) {
        const row = Math.floor(index / size);
        const col = index % size;
        if (black.size >= target)
            break;
        if ([`${row - 1}:${col}`, `${row + 1}:${col}`, `${row}:${col - 1}`, `${row}:${col + 1}`].some((key) => black.has(key)))
            continue;
        black.add(`${row}:${col}`);
        values[row][col] = values[row][(col + 1) % size];
    }
    return {
        board: values.map((row) => row.map((value) => cell(value, true))),
        answers: matrix(size, size, (row, col) => black.has(`${row}:${col}`)),
        meta: { action: "BLOCK_DUPLICATES", instructions: "Apaga duplicados; las negras no se tocan por lados.", difficulty }
    };
}
function nurikabe(random, difficulty) {
    const size = sizeFor(difficulty, 6, 8, 10, 12);
    const answers = matrix(size, size, (row, col) => row % 2 === 1 ? col !== (row % 4 === 1 ? size - 1 : 0) : col % 3 === 2);
    const board = matrix(size, size, () => cell(null, false));
    let islandId = 0;
    for (let row = 0; row < size; row += 1) {
        for (let col = 0; col < size; col += 1) {
            if (answers[row][col] || board[row][col].meta.islandId != null)
                continue;
            const run = [];
            for (let x = col; x < size && !answers[row][x]; x += 1)
                run.push([row, x]);
            islandId += 1;
            run.forEach(([y, x]) => { board[y][x].meta.islandId = islandId; });
            const clue = random.pick(run);
            board[clue[0]][clue[1]] = blockedCell({ islandId, islandSize: run.length, islandClue: true });
        }
    }
    return { board, answers, meta: { instructions: "Pinta el río conectado; no formes bloques negros 2×2.", difficulty } };
}
function bridges(random, difficulty) {
    // Difícil/Experto generan redes densas de 13×13 y 15×15.
    const islandGrid = sizeFor(difficulty, 4, 5, 7, 8);
    const size = islandGrid * 2 - 1;
    const answers = matrix(size, size, () => false);
    const board = matrix(size, size, () => cell(null, false, { bridge: true }));
    const islands = [];
    for (let y = 0; y < islandGrid; y += 1)
        for (let x = 0; x < islandGrid; x += 1) {
            if ((x + y) % 2 === 0 || random.next() > .35)
                islands.push([y * 2, x * 2]);
        }
    islands.forEach(([row, col], index) => {
        const neighbours = islands.filter(([y, x]) => (Math.abs(y - row) === 2 && x === col) || (Math.abs(x - col) === 2 && y === row));
        board[row][col] = blockedCell({ island: true, islandId: index, bridgeCount: Math.max(1, neighbours.length) });
    });
    // Árbol de expansión: cada isla se conecta con la anterior alineada más cercana.
    islands.slice(1).forEach(([row, col], index) => {
        const previous = islands.slice(0, index + 1).filter(([y, x]) => y === row || x === col).at(-1);
        if (!previous)
            return;
        const midRow = (row + previous[0]) / 2;
        const midCol = (col + previous[1]) / 2;
        if (Number.isInteger(midRow) && Number.isInteger(midCol))
            answers[midRow][midCol] = true;
    });
    const islandKeys = new Set(islands.map(([row, col]) => `${row}:${col}`));
    for (const [row, col] of islands) {
        const validTargets = [];
        const directions = [[-1, 0], [1, 0], [0, -1], [0, 1]];
        for (const [rowStep, colStep] of directions) {
            const targetRow = row + rowStep * 2;
            const targetCol = col + colStep * 2;
            const midRow = row + rowStep;
            const midCol = col + colStep;
            if (islandKeys.has(`${targetRow}:${targetCol}`) && answers[midRow]?.[midCol] === true)
                validTargets.push(`${targetRow}:${targetCol}`);
        }
        board[row][col].meta.validTargets = validTargets;
        board[row][col].meta.bridgeCount = validTargets.length;
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
function hangman(random, difficulty) {
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
function arrowsEscape(random, difficulty) {
    const count = sizeFor(difficulty, 12, 16, 20, 24);
    const board = matrix(1, count, () => cell(null, true));
    const answers = matrix(1, count, () => null);
    const shapes = [];
    const directions = [
        { name: "UP", x: 0, y: -1 }, { name: "RIGHT", x: 1, y: 0 },
        { name: "DOWN", x: 0, y: 1 }, { name: "LEFT", x: -1, y: 0 },
    ];
    const pointToSegment = (point, start, end) => {
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        const square = dx * dx + dy * dy;
        if (square === 0)
            return Math.hypot(point.x - start.x, point.y - start.y);
        const t = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / square));
        return Math.hypot(point.x - start.x - t * dx, point.y - start.y - t * dy);
    };
    const segments = (points) => points.slice(1).map((point, index) => [points[index], point]);
    const pathsTouch = (a, b, clearance = .025) => segments(a).some(([a0, a1]) => segments(b).some(([b0, b1]) => {
        for (let step = 0; step <= 8; step += 1) {
            const t = step / 8;
            if (pointToSegment({ x: a0.x + (a1.x - a0.x) * t, y: a0.y + (a1.y - a0.y) * t }, b0, b1) < clearance)
                return true;
            if (pointToSegment({ x: b0.x + (b1.x - b0.x) * t, y: b0.y + (b1.y - b0.y) * t }, a0, a1) < clearance)
                return true;
        }
        return false;
    }));
    const rayToBorder = (points, vector) => {
        const head = points[points.length - 1];
        const candidates = [
            vector.x > 0 ? (1.08 - head.x) / vector.x : Number.POSITIVE_INFINITY,
            vector.x < 0 ? (-.08 - head.x) / vector.x : Number.POSITIVE_INFINITY,
            vector.y > 0 ? (1.08 - head.y) / vector.y : Number.POSITIVE_INFINITY,
            vector.y < 0 ? (-.08 - head.y) / vector.y : Number.POSITIVE_INFINITY,
        ].filter((value) => value > 0);
        const distance = Math.min(...candidates);
        return [head, { x: head.x + vector.x * distance, y: head.y + vector.y * distance }];
    };
    for (let index = 0; index < count; index += 1) {
        let route = null;
        for (let attempt = 0; attempt < 350 && route === null; attempt += 1) {
            const exit = random.pick(directions);
            const grid = 20;
            const head = { x: random.int(3, grid - 3) / grid, y: random.int(3, grid - 3) / grid };
            const points = [head];
            let cursor = head;
            let backwards = { x: -exit.x, y: -exit.y };
            const bends = random.int(2, sizeFor(difficulty, 3, 4, 5, 6));
            for (let segment = 0; segment < bends; segment += 1) {
                const length = random.int(2, 5) / grid;
                cursor = { x: cursor.x + backwards.x * length, y: cursor.y + backwards.y * length };
                points.unshift(cursor);
                const turn = random.next() < .5 ? -1 : 1;
                backwards = { x: -backwards.y * turn, y: backwards.x * turn };
            }
            if (points.some((point) => point.x < .055 || point.x > .945 || point.y < .055 || point.y > .945))
                continue;
            if (shapes.some((existing) => pathsTouch(points, existing.points)))
                continue;
            if (shapes.some((existing) => pathsTouch(rayToBorder(points, exit), existing.points, .04)))
                continue;
            route = {
                id: `route-${index}`, points, direction: exit.name, exitVector: { x: exit.x, y: exit.y }, thickness: .014,
                blockType: index > 0 && index % 11 === 0 ? "BOMB" : index > 0 && index % 7 === 0 ? "BIDIRECTIONAL" : "NORMAL",
                memberKeys: [`0:${index}`], removalOrder: index,
            };
        }
        if (route === null) {
            // Los fallbacks viven en un corredor perimetral reservado. Su cabeza ya
            // mira hacia afuera, por lo que nunca crea un estado sin movimientos.
            const side = index % 4;
            const slot = Math.floor(index / 4);
            const axis = .12 + (slot % 6) * .145;
            const fallback = side === 0
                ? { points: [{ x: axis + .035, y: .075 }, { x: axis, y: .075 }, { x: axis, y: .025 }], direction: "UP", exitVector: { x: 0, y: -1 } }
                : side === 1
                    ? { points: [{ x: .925, y: axis + .035 }, { x: .925, y: axis }, { x: .975, y: axis }], direction: "RIGHT", exitVector: { x: 1, y: 0 } }
                    : side === 2
                        ? { points: [{ x: axis - .035, y: .925 }, { x: axis, y: .925 }, { x: axis, y: .975 }], direction: "DOWN", exitVector: { x: 0, y: 1 } }
                        : { points: [{ x: .075, y: axis - .035 }, { x: .075, y: axis }, { x: .025, y: axis }], direction: "LEFT", exitVector: { x: -1, y: 0 } };
            route = {
                id: `route-${index}`, ...fallback, thickness: .012,
                blockType: "NORMAL", memberKeys: [`0:${index}`], removalOrder: index,
            };
        }
        shapes.push(route);
        board[0][index] = cell(route.direction, true, {
            arrow: route.direction,
            shapeId: route.id,
            shapeAnchor: true,
            pathType: "SERPENTINE",
            blockType: route.blockType,
        });
        answers[0][index] = route.direction;
    }
    return {
        board,
        answers,
        meta: {
            freeSpace: true,
            pathModel: "SERPENTINE_V2",
            worldWidth: 1,
            worldHeight: 1,
            totalBlocks: count,
            totalShapes: shapes.length,
            maxFailedTaps: sizeFor(difficulty, 8, 7, 6, 5),
            rotatePowerUses: 2,
            missilePowerUses: 1,
            shapes,
            instructions: "Toca una ruta o su punta. La flecha solo escapa si el rayo de su cabeza llega al borde sin cruzar otra ruta.",
            difficulty,
        },
    };
}
export const SCRABBLE_SCORES = {
    A: 1, B: 3, C: 3, D: 2, E: 1, F: 4, G: 2, H: 4, I: 1, J: 8,
    L: 1, M: 3, N: 1, "Ñ": 8, O: 1, P: 3, Q: 5, R: 1, S: 1, T: 1,
    U: 1, V: 4, X: 8, Y: 4, Z: 10
};
function crossLetters(random, difficulty) {
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
        .filter((word) => word.length >= 4 && word.length <= 6);
    const centralWord = random.pick(centralCandidates) ?? "ARENA";
    const centralStart = 7 - Math.floor(centralWord.length / 2);
    [...centralWord].forEach((letter, index) => {
        const target = board[7][centralStart + index];
        target.value = letter;
        target.isRevealed = true;
        target.meta = { ...target.meta, given: true, centralAnchor: true };
    });
    return {
        board,
        answers: matrix(size, size, () => null),
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
function secretCode(random, difficulty) {
    const words = random.shuffle(SPANISH_DICTIONARY.map((entry) => normalizeWord(entry.word)))
        .filter((word, index, all) => word.length >= 3 && word.length <= 10 && all.indexOf(word) === index)
        .slice(0, 25);
    const fallback = ["ARENA", "LOGICA", "NUBE", "SOL", "LUNA", "RIO", "MAR", "FUEGO", "TIERRA", "AIRE", "CASA", "ARBOL", "RELOJ", "PUENTE", "CLAVE", "JUEGO", "EQUIPO", "ROJO", "AZUL", "PISTA", "MENTE", "CAMPO", "TORRE", "REY", "VIAJE"];
    while (words.length < 25)
        words.push(fallback[words.length]);
    const identities = random.shuffle([...Array(8).fill("RED"), ...Array(8).fill("BLUE"), ...Array(8).fill("NEUTRAL"), "ASSASSIN"]);
    return {
        board: matrix(5, 5, (row, col) => cell(words[row * 5 + col], true, { wordIndex: row * 5 + col })),
        answers: matrix(5, 5, (row, col) => identities[row * 5 + col]),
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
];
function capitalArena(random, difficulty) {
    const size = 11;
    const coordinates = [];
    for (let col = 10; col >= 0; col -= 1)
        coordinates.push({ row: 10, col });
    for (let row = 9; row >= 0; row -= 1)
        coordinates.push({ row, col: 0 });
    for (let col = 1; col <= 10; col += 1)
        coordinates.push({ row: 0, col });
    for (let row = 1; row <= 9; row += 1)
        coordinates.push({ row, col: 10 });
    const special = {
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
    const byCoordinate = new Map(coordinates.map((coordinate, index) => [`${coordinate.row}:${coordinate.col}`, spaces[index]]));
    const board = matrix(size, size, (row, col) => {
        const space = byCoordinate.get(`${row}:${col}`);
        return space
            ? cell(null, true, { index: space.index, name: space.name, type: space.type, price: space.price, rent: space.rent })
            : blockedCell({ capitalCenter: true });
    });
    return {
        board,
        answers: matrix(size, size, () => null),
        meta: {
            spaces,
            instructions: "Lanza dos dados, compra distritos, cobra rentas y construye mejoras de hackeo.",
            difficulty,
        },
    };
}
function towerDefense(random, difficulty) {
    const rows = 9;
    const columns = 14;
    const path = [];
    for (let col = 0; col < columns; col += 1)
        path.push({ row: 1, col });
    for (let row = 2; row <= 4; row += 1)
        path.push({ row, col: columns - 1 });
    for (let col = columns - 2; col >= 1; col -= 1)
        path.push({ row: 4, col });
    for (let row = 5; row <= 7; row += 1)
        path.push({ row, col: 1 });
    for (let col = 2; col < columns; col += 1)
        path.push({ row: 7, col });
    const pathIndex = new Map(path.map((point, index) => [`${point.row}:${point.col}`, index]));
    const board = matrix(rows, columns, (row, col) => {
        const index = pathIndex.get(`${row}:${col}`);
        return index == null
            ? cell(null, false, { buildable: true, terrain: random.int(0, 3) })
            : blockedCell({ path: true, pathIndex: index, spawn: index === 0, base: index === path.length - 1 });
    });
    return {
        board,
        answers: matrix(rows, columns, () => null),
        meta: {
            actionMode: true,
            engine: "COOP_TOWER_DEFENSE",
            path,
            maxWaves: 20,
            startingCredits: difficulty === "EASY" ? 500 : difficulty === "EXPERT" ? 320 : 400,
            instructions: "Construye torres junto al camino, elige prioridades y lanza 20 oleadas cooperativas.",
            difficulty,
        },
    };
}
function reactorChain(random, difficulty) {
    const size = sizeFor(difficulty, 7, 8, 9, 10);
    const colors = difficulty === "EASY" ? 4 : difficulty === "EXPERT" ? 6 : 5;
    const board = matrix(size, size, () => cell(random.int(1, colors), true, { reactorOrb: true }));
    // Toda ronda comienza con al menos una cadena jugable; así nunca se presenta
    // un tablero muerto ni la IA queda sin una acción válida.
    const guaranteedColor = random.int(1, colors);
    board[0][0].value = guaranteedColor;
    board[0][1].value = guaranteedColor;
    board[1][0].value = guaranteedColor;
    return {
        board,
        answers: matrix(size, size, () => null),
        meta: {
            actionMode: true,
            engine: "REACTOR_CHAIN",
            colors,
            targetRemoved: size * size * 2,
            removed: 0,
            combo: 1,
            instructions: "Toca grupos de 3 o más núcleos iguales. Las cadenas grandes multiplican la puntuación y recargan el reactor.",
            difficulty,
        },
    };
}
function latinPuzzle(random, size) {
    const digits = random.shuffle(Array.from({ length: size }, (_, index) => index + 1));
    const answers = matrix(size, size, (row, col) => digits[(row + col) % size]);
    return { board: matrix(size, size, () => cell(null, false)), answers, meta: { size } };
}
function edgeMeta() { return { top: false, right: false, bottom: false, left: false }; }
function cell(value, isRevealed, meta = {}) { return { value, isRevealed, ownerId: null, isBlocked: false, meta }; }
function blockedCell(meta = {}) { return { value: null, isRevealed: true, ownerId: null, isBlocked: true, meta }; }
function matrix(rows, columns, create) { return Array.from({ length: rows }, (_, row) => Array.from({ length: columns }, (_, col) => create(row, col))); }
function normalizeWord(value) { return value.trim().toUpperCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^A-ZÑ]/g, ""); }
//# sourceMappingURL=blueprints.js.map