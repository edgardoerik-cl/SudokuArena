import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ArenaGame } from "../src/game.js";
import { createPuzzleBlueprint } from "../src/puzzles/blueprints.js";
import { GenericPuzzleEngine } from "../src/puzzles/engine.js";
import { GAME_TYPES } from "../src/puzzles/types.js";
import { attackRange, calculateDamage, movementRange, skillFor, type Piece } from "../src/puzzles/chessTactics.js";

describe("motor genérico de puzzles", () => {
  it("reproduce una semilla y cambia el tablero con otra", () => {
    const first = createPuzzleBlueprint("MINESWEEPER", { seed: "arena-42", difficulty: "HARD" });
    const replay = createPuzzleBlueprint("MINESWEEPER", { seed: "arena-42", difficulty: "HARD" });
    const different = createPuzzleBlueprint("MINESWEEPER", { seed: "arena-43", difficulty: "HARD" });
    assert.deepEqual(first.answers, replay.answers);
    assert.notDeepEqual(first.answers, different.answers);
  });

  it("la dificultad incrementa tamaño y densidad en Buscaminas", () => {
    const easy = createPuzzleBlueprint("MINESWEEPER", { seed: "difficulty", difficulty: "EASY" });
    const expert = createPuzzleBlueprint("MINESWEEPER", { seed: "difficulty", difficulty: "EXPERT" });
    assert.ok(expert.board.length > easy.board.length);
    assert.ok(Number(expert.meta.mineCount) > Number(easy.meta.mineCount));
  });

  it("oculta palabras en ocho direcciones y conserva recorridos verificables", () => {
    const puzzle = createPuzzleBlueprint("WORD_SEARCH", { seed: "multidirectional-words", difficulty: "EXPERT" });
    const placements = puzzle.meta.placements as Array<{ word: string; startRow: number; startCol: number; rowStep: number; colStep: number }>;
    assert.equal(placements.length, 9);
    assert.ok(placements.some((placement) => placement.rowStep !== 0), "debe contener palabras verticales o diagonales");
    assert.ok(placements.some((placement) => placement.rowStep !== 0 && placement.colStep !== 0), "debe contener diagonales");
    assert.ok(placements.some((placement) => placement.rowStep < 0 || placement.colStep < 0), "debe contener recorridos invertidos");
    placements.forEach((placement) => {
      const boardWord = [...placement.word].map((_, offset) =>
        puzzle.board[placement.startRow + placement.rowStep * offset]![placement.startCol + placement.colStep * offset]!.value
      ).join("");
      assert.equal(boardWord, placement.word);
    });
  });

  it("publica pistas conceptuales y configura Chess Tactics RPG", () => {
    const crossword = createPuzzleBlueprint("CROSSWORD", { seed: "real-clues", difficulty: "MEDIUM" });
    const clues = crossword.meta.clues as string[];
    assert.ok(clues.every((clue) => !/Palabra de \d+ letras/i.test(clue)));
    assert.ok(clues.every((clue) => clue.split(". ")[1]?.length > 12));

    const tactics = createPuzzleBlueprint("CHESS_TACTICS", { seed: "combat", difficulty: "EXPERT" });
    const pieces = tactics.board.flat().filter((cell) => cell.value !== null);
    assert.ok(pieces.length >= 10);
    assert.ok(pieces.every((cell) => ["BLUE", "RED"].includes(String(cell.meta.team))));
    assert.ok(pieces.every((cell) => Number(cell.meta.hp) > 0 && Number(cell.meta.ap) > 0));
  });

  for (const gameType of GAME_TYPES.filter((type) => ![
    "SUDOKU", "CROSS_LETTERS", "SECRET_CODE", "CAPITAL_ARENA",
    "TETRIS_ARENA", "PACMAN_ARENA", "CHECKERS", "CHESS_TACTICS",
  ].includes(type))) {
    it(`genera y permite a un Bot resolver ${gameType}`, () => {
      const players = new ArenaGame(`players-${gameType}`);
      players.addPlayer("bot", "Bot_Matriz", true);
      players.startMatch({ gameType, powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "HARD" }, "bot");
      const engine = new GenericPuzzleEngine(gameType, `test-${gameType}`);
      const initial = engine.snapshot(players);
      assert.ok(initial.rows > 0);
      assert.ok(initial.columns > 0);
      assert.equal(initial.board.length, initial.rows);

      const startedAt = Date.now();
      const maxTurns = gameType === "NEXUS_ZERO" ? 8_000 : 1_500;
      for (let turn = 0; turn < maxTurns && !engine.snapshot(players).completed; turn += 1) {
        const move = engine.createBotMove(1, "bot");
        assert.ok(move, `${gameType} debe producir una jugada mientras no termine`);
        const actionNow = startedAt + turn * (gameType === "TOWER_DEFENSE" ? 1_000 : 200);
        engine.makeMove("bot", move!, players, actionNow);
        if (gameType === "TOWER_DEFENSE") {
          for (let subTick = 1; subTick <= 5; subTick += 1) {
            engine.tickTowerDefense(players, startedAt + turn * 1_000 + subTick * 200);
          }
        }
      }
      assert.equal(engine.snapshot(players).completed, true, `${gameType} debe poder completarse`);
    });
  }

  it("Tower Defense publica tropas móviles, disparos y vida durante una oleada", () => {
    const players = new ArenaGame("tower-live");
    players.addPlayer("p1", "Arquitecto");
    players.startMatch({ gameType: "TOWER_DEFENSE", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("TOWER_DEFENSE", "tower-live-engine");
    engine.snapshot(players);
    assert.equal(engine.makeMove("p1", {
      requestId: "build", row: 0, col: 0, val: { action: "BUILD", towerType: "RAPID" },
    }, players).accepted, true);
    assert.equal(engine.makeMove("p1", {
      requestId: "wave", row: 0, col: 0, val: { action: "START_WAVE" },
    }, players).accepted, true);
    const started = engine.snapshot(players);
    assert.equal(started.meta.waveActive, true);
    assert.ok((started.meta.enemies as Array<unknown>).length >= 7);
    const now = Date.now();
    for (let tick = 1; tick <= 12; tick += 1) engine.tickTowerDefense(players, now + tick * 100);
    const moving = engine.snapshot(players, now + 1_300);
    assert.ok((moving.meta.enemies as Array<{ progress: number }>).some((enemy) => enemy.progress > 0));
    assert.ok((moving.meta.projectiles as Array<unknown>).length > 0);
  });

  it("Capital Arena controla dados, economía y turnos en el servidor", () => {
    const players = new ArenaGame("capital-players");
    players.addPlayer("p1", "Capitalista 1");
    players.addPlayer("p2", "Capitalista 2");
    players.startMatch({ gameType: "CAPITAL_ARENA", powersEnabled: true, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("CAPITAL_ARENA", "capital-test");

    const initial = engine.snapshot(players, 1_000);
    assert.equal(initial.meta.currentPlayerTurn, "p1");
    assert.equal((initial.meta.balances as Record<string, number>).p1, 1_500);
    assert.equal(engine.makeMove("p2", { requestId: "capital-wrong-turn", row: 10, col: 10, val: { action: "ROLL" } }, players, 1_001).accepted, false);
    const fakeMove = engine.makeMove(
      "p1",
      { requestId: "capital-teleport", row: 10, col: 10, val: { action: "ROLL", from: "0", to: "12" } },
      players,
      1_001,
    );
    assert.equal(fakeMove.accepted, false);
    assert.equal(engine.snapshot(players, 1_001).meta.currentPlayerTurn, "p1");
    assert.equal(engine.snapshot(players, 1_001).meta.stage, "ROLL");

    const originalRandom = Math.random;
    Math.random = () => 0; // 1+1 cae en Suerte y roba la primera tarjeta.
    try {
      assert.equal(engine.makeMove("p1", { requestId: "capital-roll", row: 10, col: 10, val: { action: "ROLL" } }, players, 1_002).accepted, true);
    } finally {
      Math.random = originalRandom;
    }
    const rolled = engine.snapshot(players, 1_003);
    assert.equal((rolled.meta.dice as number[]).length, 2);
    assert.equal((rolled.meta.surpriseCard as { title: string }).title, "Hackathon Maestro");
    assert.equal((rolled.meta.balances as Record<string, number>).p1, 1_700);
    if (rolled.meta.stage === "BUY_OR_END") {
      assert.equal(engine.makeMove("p1", { requestId: "capital-buy", row: 10, col: 10, val: { action: "BUY" } }, players, 1_004).accepted, true);
    }
    assert.equal(engine.makeMove("p1", { requestId: "capital-end", row: 10, col: 10, val: { action: "END_TURN" } }, players, 1_005).accepted, true);
    assert.equal(engine.snapshot(players, 1_006).meta.currentPlayerTurn, "p2");
  });

  it("Capital Arena admite cuatro jugadores y rechaza un quinto slot", () => {
    const players = new ArenaGame("capital-four");
    assert.ok(players.addPlayer("p1", "Uno"));
    assert.ok(players.addPlayer("p2", "Dos"));
    assert.ok(players.addPlayer("p3", "Tres"));
    assert.ok(players.addPlayer("p4", "Cuatro"));
    assert.equal(players.addPlayer("p5", "Cinco"), null);
    players.startMatch({ gameType: "CAPITAL_ARENA", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("CAPITAL_ARENA", "capital-four-test");
    const snapshot = engine.snapshot(players);
    assert.equal(Object.keys(snapshot.meta.balances as object).length, 4);
    assert.equal(snapshot.players.length, 4);
  });

  it("Letras Cruzadas Blitz entrega atriles y acepta juego sin turnos", () => {
    const players = new ArenaGame("letters-players");
    players.addPlayer("bot", "Bot_Letras", true);
    players.startMatch({ gameType: "CROSS_LETTERS", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "HARD", puzzleDifficulty: "MEDIUM" }, "bot");
    const engine = new GenericPuzzleEngine("CROSS_LETTERS", "letters-test");
    const initial = engine.snapshot(players, 1_000);
    assert.equal(initial.rows, 15);
    assert.equal(engine.rackFor("bot").length, 7);
    assert.equal(initial.meta.activePlayerId, null);
    assert.equal(initial.meta.blitz, true);
    const move = engine.createBotMove(1, "bot");
    assert.ok(move);
    const result = engine.makeMove("bot", move!, players, 2_000);
    assert.equal(result.accepted, true);
    assert.ok(result.points > 0);
    assert.ok(engine.snapshot(players).board.flat().some((cell) => typeof cell.value === "string"));
  });

  it("Código Secreto oculta la clave a operativos y resuelve turnos capitán/operativo", () => {
    const players = new ArenaGame("secret-players");
    players.addPlayer("redCaptain", "Capitana");
    players.addPlayer("blueCaptain", "Capitán azul");
    players.addPlayer("redAgent", "Agente rojo");
    players.addPlayer("blueAgent", "Agente azul");
    players.startMatch({ gameType: "SECRET_CODE", powersEnabled: true, teamMode: "TWO_V_TWO", tileType: "NUMBERS", botDifficulty: "HARD", puzzleDifficulty: "MEDIUM" }, "redCaptain");
    const engine = new GenericPuzzleEngine("SECRET_CODE", "secret-test", { seed: "secret" });
    engine.snapshot(players, 1_000);
    const ids = ["redCaptain", "blueCaptain", "redAgent", "blueAgent"];
    const currentTeam = engine.secretStateFor(ids[0]!)?.currentTeam;
    const captainId = ids.find((id) => engine.secretStateFor(id)?.team === currentTeam && engine.secretStateFor(id)?.role === "CAPTAIN")!;
    const agentId = ids.find((id) => engine.secretStateFor(id)?.team === currentTeam && engine.secretStateFor(id)?.role === "OPERATIVE")!;
    assert.equal((engine.secretStateFor(captainId)?.key as string[]).length, 25);
    assert.equal(engine.secretStateFor(agentId)?.key, null);
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, captainId);
    assert.equal(engine.makeMove(captainId, { requestId: "clue", row: 0, col: 0, val: { action: "CLUE", clue: "IDEA", count: 2 } }, players, 1_100).accepted, true);
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, agentId);
    const redIndex = (engine.secretStateFor(captainId)?.key as string[]).findIndex((entry) => entry === currentTeam);
    const guess = engine.makeMove(agentId, { requestId: "guess", row: Math.floor(redIndex / 5), col: redIndex % 5, val: { action: "GUESS" } }, players, 1_200);
    assert.equal(guess.accepted, true);
    assert.equal(engine.snapshot(players).board[Math.floor(redIndex / 5)]![redIndex % 5]!.meta.revealedColor, currentTeam);
  });

  it("inyecta pistas iniciales en Kakuro y configura Ahorcado", () => {
    const kakuro = createPuzzleBlueprint("KAKURO", { seed: "anchors", difficulty: "MEDIUM" });
    const kakuroGivens = kakuro.board.flat().filter((cell) => cell.meta.given === true);
    assert.ok(kakuroGivens.length >= 3);
    assert.equal(kakuro.meta.verifiedUnique, true);
    assert.ok(kakuroGivens.every((cell) => cell.isBlocked && cell.value !== null));

    const hangman = createPuzzleBlueprint("HANGMAN", { seed: "deduction", difficulty: "HARD" });
    assert.ok(Number(hangman.meta.wordLength) >= 8);
    assert.equal(hangman.meta.maxErrors, 6);
    assert.ok(hangman.board[0]!.every((cell) => cell.value === null));
  });

  it("Nexo Cero genera cargas opuestas en una matriz sin superposición", () => {
    const blueprint = createPuzzleBlueprint("NEXUS_ZERO", { seed: "deep-shuffle", difficulty: "EXPERT" });
    const values = blueprint.board.flat().map((cell) => cell.value).filter((value): value is number => typeof value === "number");
    const counts = new Map<number, number>();
    values.forEach((value) => counts.set(value, (counts.get(value) ?? 0) + 1));
    counts.forEach((count, value) => assert.equal(counts.get(-value), count));
    assert.equal(blueprint.meta.guaranteedSolvable, true);
    assert.equal(blueprint.meta.engine, "NEXUS_SWIPE");
  });

  it("Nexo Cero procesa swipes globales sin solapar fichas incompatibles", () => {
    const players = new ArenaGame("nexus-int");
    players.addPlayer("p1", "Cero");
    players.startMatch({ gameType: "NEXUS_ZERO", powersEnabled: false, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("NEXUS_ZERO", "nexus-int", { seed: "integer-zero" });
    const before = engine.snapshot(players).board.flat().filter((cell) => cell.value != null).length;
    const result = ["LEFT", "DOWN", "RIGHT", "UP"].map((direction, index) => engine.makeMove("p1", {
      requestId: `zero-swipe-${index}`, row: 0, col: 0, val: direction,
    }, players)).find((candidate) => candidate.accepted);
    assert.ok(result);
    const after = engine.snapshot(players).board.flat().filter((cell) => cell.value != null).length;
    assert.ok(after <= before);
  });

  it("Damas conserva el turno al intentar un movimiento ilegal", () => {
    const players = new ArenaGame("checkers-invalid");
    players.addPlayer("p1", "Azul"); players.addPlayer("p2", "Rojo");
    players.startMatch({ gameType: "CHECKERS", powersEnabled: false, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("CHECKERS", "checkers-invalid");
    engine.setFirstPlayer("p1");
    const rejected = engine.makeMove("p1", {
      requestId: "illegal-checker",
      row: 2,
      col: 1,
      val: { targetRow: 7, targetCol: 6 },
    }, players, 1_000);
    assert.equal(rejected.accepted, false);
    assert.equal(engine.snapshot(players, 1_001).meta.currentPlayerTurn, "p1");
  });

  it("El Gato detecta una de las ocho líneas de victoria", () => {
    const players = new ArenaGame("gato");
    players.addPlayer("p1", "X"); players.addPlayer("p2", "O");
    players.startMatch({ gameType: "TIC_TAC_TOE", powersEnabled: false, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("TIC_TAC_TOE", "gato-test");
    engine.setFirstPlayer("p1");
    const play = (id: string, row: number, col: number, requestId: string) =>
      engine.makeMove(id, { requestId, row, col, val: "MARK" }, players);
    assert.equal(play("p1", 0, 0, "x1").accepted, true);
    assert.equal(play("p2", 1, 0, "o1").accepted, true);
    assert.equal(play("p1", 0, 1, "x2").accepted, true);
    assert.equal(play("p2", 1, 1, "o2").accepted, true);
    assert.equal(play("p1", 0, 2, "x3").completed, true);
  });

  it("Ahorcado enmascara la palabra y conserva el turno al acertar", () => {
    const players = new ArenaGame("hangman-security");
    players.addPlayer("p1", "Uno"); players.addPlayer("p2", "Dos");
    players.startMatch({ gameType: "HANGMAN", powersEnabled: false, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("HANGMAN", "hangman-security", { seed: "turns" });
    engine.setFirstPlayer("p1");
    const initial = engine.snapshot(players);
    assert.ok(Array.isArray(initial.meta.hiddenWord));
    assert.ok((initial.meta.hiddenWord as string[]).every((letter) => letter === "_"));
    assert.deepEqual(initial.meta.wrongGuesses, []);
    assert.ok((initial.meta.maskedWord as string[]).every((letter) => letter === "_"));
    const correct = engine.createBotMove(1, "p1")!;
    assert.equal(engine.makeMove("p1", correct, players).accepted, true);
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p1");
    const invalid = engine.makeMove("p1", { requestId: "invalid-format", row: 0, col: 0, val: "AB" }, players);
    assert.equal(invalid.code, "INVALID_MOVE");
    assert.equal(invalid.penaltyMs, 0);
    const repeated = engine.makeMove("p1", { ...correct, requestId: "repeated-letter" }, players);
    assert.equal(repeated.code, "INVALID_MOVE");
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p1");
    for (const letter of "ZXQWVUTSRPONMLKJIHGFEDCBA") {
      const result = engine.makeMove("p1", { requestId: `miss-${letter}`, row: 0, col: 0, val: letter }, players);
      if (result.accepted && result.points === 0) break;
    }
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p2");
  });

  it("Ahorcado aplica Revelación, Descarte y Último Aliento una sola vez", () => {
    const players = new ArenaGame("hangman-powers");
    players.addPlayer("p1", "Comodín");
    players.startMatch({ gameType: "HANGMAN", powersEnabled: false, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("HANGMAN", "hangman-powers", { seed: "lifeline" });
    engine.setFirstPlayer("p1");
    assert.equal(engine.makeMove("p1", { requestId: "reveal", row: 0, col: 0, val: { action: "REVEAL" } }, players).accepted, true);
    assert.equal(engine.makeMove("p1", { requestId: "reveal-again", row: 0, col: 0, val: { action: "REVEAL" } }, players).accepted, false);
    assert.equal(engine.makeMove("p1", { requestId: "discard", row: 0, col: 0, val: { action: "DISCARD" } }, players).accepted, true);
    const state = engine.snapshot(players);
    assert.ok((state.meta.revealUsed as string[]).includes("p1"));
    assert.equal((state.meta.discardedByPlayer as Record<string, string[]>).p1.length, 3);
  });

  it("Flechas modela rutas serpenteantes con salida vectorial y solución", () => {
    const blueprint = createPuzzleBlueprint("ARROWS_ESCAPE", { seed: "complex-shapes", difficulty: "EXPERT" });
    const shapes = blueprint.meta.shapes as Array<{
      id: string; points: Array<{ x: number; y: number }>; direction: string;
      exitVector: { x: number; y: number }; thickness: number; removalOrder: number;
    }>;
    assert.equal(blueprint.meta.pathModel, "SERPENTINE_V2");
    assert.ok(shapes.every((shape) => shape.points.length >= 2));
    assert.ok(shapes.some((shape) => shape.points.length >= 4));
    assert.ok(shapes.every((shape) => ["UP", "RIGHT", "DOWN", "LEFT"].includes(shape.direction)));
    assert.ok(shapes.every((shape) => Math.abs(shape.exitVector.x) + Math.abs(shape.exitVector.y) === 1));
    assert.ok(shapes.every((shape) => shape.thickness > 0));
    assert.ok(blueprint.board.flat().every((cell) => typeof cell.meta.shapeId === "string"));
  });

  it("Reactor Chain acepta el toque directo de un grupo conectado", () => {
    const players = new ArenaGame("reactor-touch");
    players.addPlayer("p1", "Cadena");
    players.startMatch({ gameType: "REACTOR_CHAIN", powersEnabled: false, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "EASY" }, "p1");
    const engine = new GenericPuzzleEngine("REACTOR_CHAIN", "reactor-touch", { seed: "guaranteed-chain", difficulty: "EASY" });
    const before = Number(engine.snapshot(players).meta.removed ?? 0);
    const result = engine.makeMove("p1", { requestId: "chain-tap", row: 0, col: 0, val: "CHAIN" }, players);
    assert.equal(result.accepted, true);
    assert.ok(Number(engine.snapshot(players).meta.removed) >= before + 3);
  });

  it("Chess Tactics configura las seis clases, cooldowns y rangos clásicos", () => {
    const knight: Piece = {
      id: "n", team: "BLUE", owner: "BLUE", type: "KNIGHT",
      hp: 100, maxHp: 100, ap: 4, maxAp: 4, defense: 12,
      statusEffects: [], currentCooldown: 0, isShielded: false,
      hasEvasion: true, canActThisTurn: false, hasMoved: false, ambushTarget: null,
    };
    assert.equal(skillFor(knight), "SEISMIC_LEAP");
    assert.equal(movementRange(knight, { row: 4, col: 4 }).length, 8);
    assert.equal(attackRange(knight, { row: 4, col: 4 }).length, 8);
    const pawn = { ...knight, type: "PAWN" as const, hasMoved: false };
    assert.equal(movementRange(pawn, { row: 1, col: 3 }).length, 2);
    pawn.hasMoved = true;
    assert.equal(movementRange(pawn, { row: 3, col: 3 }).length, 1);
    assert.ok(calculateDamage(40, 20) < calculateDamage(40, 0));
    const blueprint = createPuzzleBlueprint("CHESS_TACTICS", { seed: "six-classes" });
    assert.deepEqual(
      new Set(blueprint.board.flat().map((cell) => cell.meta.type).filter(Boolean)),
      new Set(["PAWN", "KNIGHT", "BISHOP", "ROOK", "QUEEN", "KING"]),
    );
  });

  it("Buscaminas aplica cinco segundos al pisar una mina", () => {
    const players = new ArenaGame("mine-players");
    players.addPlayer("bot", "Bot_Mina", true);
    players.startMatch({ gameType: "MINESWEEPER", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "EASY" }, "bot");
    const engine = new GenericPuzzleEngine("MINESWEEPER", "mine-test");
    const mineMove = engine.createBotMove(0)!;
    const result = engine.makeMove("bot", mineMove, players, 10_000);
    assert.equal(result.accepted, false);
    assert.equal(result.penaltyMs, 5_000);
    assert.equal(players.snapshot().players[0]?.blockedUntil, 15_000);
  });

  it("rechaza jugadas durante una penalización activa", () => {
    const players = new ArenaGame("blocked-players");
    players.addPlayer("human", "Ada");
    players.startMatch({ gameType: "KAKURO", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "human");
    players.applyGenericPenalty("human", 20_000);
    const engine = new GenericPuzzleEngine("KAKURO", "blocked-test");
    const move = engine.createBotMove(1)!;
    const result = engine.makeMove("human", move, players, 15_000);
    assert.equal(result.accepted, false);
    assert.equal(result.code, "PLAYER_BLOCKED");
    assert.equal(engine.snapshot(players).revision, 0);
  });

  it("Timbiriche comparte cada arista con la casilla vecina", () => {
    const players = new ArenaGame("dots-players");
    players.addPlayer("human", "Grace");
    players.startMatch({ gameType: "DOTS_AND_BOXES", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "human");
    const engine = new GenericPuzzleEngine("DOTS_AND_BOXES", "dots-test");
    const result = engine.makeMove("human", { requestId: "edge-1", row: 0, col: 0, val: "right" }, players, 1_000);
    const state = engine.snapshot(players);
    assert.equal(result.accepted, true);
    assert.equal(state.board[0]?.[0]?.meta.right, true);
    assert.equal(state.board[0]?.[1]?.meta.left, true);
  });

  it("Timbiriche rechaza al jugador fuera de turno y avanza tras una línea", () => {
    const players = new ArenaGame("dots-turns");
    players.addPlayer("p1", "Uno"); players.addPlayer("p2", "Dos");
    players.startMatch({ gameType: "DOTS_AND_BOXES", powersEnabled: true, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("DOTS_AND_BOXES", "dots-turns");
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p1");
    assert.equal(engine.makeMove("p2", { requestId: "early", row: 0, col: 0, val: "right" }, players).accepted, false);
    assert.equal(engine.makeMove("p1", { requestId: "first", row: 0, col: 0, val: "right" }, players).accepted, true);
    assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p2");
  });

  it("Timbiriche conserva el turno al cerrar una caja y registra el color de cada arista", () => {
    const players = new ArenaGame("dots-extra");
    players.addPlayer("p1", "Uno"); players.addPlayer("p2", "Dos");
    players.startMatch({ gameType: "DOTS_AND_BOXES", powersEnabled: true, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("DOTS_AND_BOXES", "dots-extra");
    assert.equal(engine.makeMove("p1", { requestId: "top", row: 0, col: 0, val: "top" }, players).accepted, true);
    assert.equal(engine.makeMove("p2", { requestId: "right", row: 0, col: 0, val: "right" }, players).accepted, true);
    assert.equal(engine.makeMove("p1", { requestId: "bottom", row: 0, col: 0, val: "bottom" }, players).accepted, true);
    assert.equal(engine.makeMove("p2", { requestId: "left", row: 0, col: 0, val: "left" }, players).accepted, true);
    const state = engine.snapshot(players);
    assert.equal(state.meta.currentPlayerTurn, "p2");
    assert.equal(state.board[0]![0]!.ownerId, "p2");
    assert.equal(state.board[0]![0]!.meta.leftOwnerId, "p2");
  });

  for (const turnGame of ["MINESWEEPER", "CROSSWORD"] as const) {
    it(`${turnGame} publica el jugador activo, rechaza fuera de turno y rota`, () => {
      const players = new ArenaGame(`turn-${turnGame}`);
      players.addPlayer("p1", "Uno"); players.addPlayer("p2", "Dos");
      players.startMatch({ gameType: turnGame, powersEnabled: true, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
      const engine = new GenericPuzzleEngine(turnGame, `turn-${turnGame}`);
      assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p1");
      const proposal = engine.createBotMove(1, "p1")!;
      assert.equal(engine.makeMove("p2", { ...proposal, requestId: `early-${turnGame}` }, players).accepted, false);
      assert.equal(engine.makeMove("p1", { ...proposal, requestId: `valid-${turnGame}` }, players).accepted, true);
      assert.equal(engine.snapshot(players).meta.currentPlayerTurn, "p2");
    });
  }

  it("Ojo de Lince nunca conduce a una mina ni a una penalización", () => {
    const players = new ArenaGame("reveal-players");
    players.addPlayer("human", "Linus");
    players.startMatch({ gameType: "MINESWEEPER", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "human");
    const engine = new GenericPuzzleEngine("MINESWEEPER", "reveal-test");
    const mine = engine.createBotMove(0)!;
    const reveal = engine.revealMove(mine.row, mine.col);
    assert.ok(reveal);
    const result = engine.makeMove("human", reveal!, players, 1_000, { rewardEnergy: false });
    assert.equal(result.accepted, true);
    assert.equal(result.penaltyMs, 0);
  });

  it("Memoria Neon no filtra respuestas y conquista parejas de forma autoritativa", () => {
    const players = new ArenaGame("memory-players");
    players.addPlayer("p1", "Memoria");
    players.startMatch({ gameType: "MEMORY_NEON", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("MEMORY_NEON", "memory-contract", { seed: "memory" });
    const initial = engine.snapshot(players);
    assert.ok(initial.board.flat().every((cell) => cell.value === null));
    const first = engine.createBotMove(1, "p1")!;
    assert.equal(engine.makeMove("p1", first, players).accepted, true);
    const second = engine.createBotMove(1, "p1")!;
    assert.equal(engine.makeMove("p1", second, players).accepted, true);
    const state = engine.snapshot(players);
    assert.equal(state.board.flat().filter((cell) => cell.ownerId === "p1").length, 2);
    assert.equal(state.meta.pairsFound, 1);
  });

  it("2048 Arena serializa deslizamientos y no penaliza un gesto sin movimiento", () => {
    const players = new ArenaGame("merge-players");
    players.addPlayer("p1", "Merge");
    players.startMatch({ gameType: "MERGE_2048", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    const engine = new GenericPuzzleEngine("MERGE_2048", "merge-contract", { seed: "merge", difficulty: "EASY" });
    let accepted = 0;
    for (let turn = 0; turn < 30 && !engine.snapshot(players).completed; turn += 1) {
      const move = engine.createBotMove(1, "p1")!;
      const result = engine.makeMove("p1", move, players, 1_000 + turn);
      if (result.accepted) accepted += 1;
      else assert.equal(result.penaltyMs, 0);
    }
    assert.ok(accepted > 0);
    assert.ok(Number(engine.snapshot(players).meta.highestTile ?? 2) >= 4);
  });
});
