import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ArenaGame } from "../src/game.js";
import { createPuzzleBlueprint } from "../src/puzzles/blueprints.js";
import { GenericPuzzleEngine } from "../src/puzzles/engine.js";
import { GAME_TYPES } from "../src/puzzles/types.js";

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

  it("publica pistas conceptuales y nunca filtra la respuesta de Rummikub", () => {
    const crossword = createPuzzleBlueprint("CROSSWORD", { seed: "real-clues", difficulty: "MEDIUM" });
    const clues = crossword.meta.clues as string[];
    assert.ok(clues.every((clue) => !/Palabra de \d+ letras/i.test(clue)));
    assert.ok(clues.every((clue) => clue.split(". ")[1]?.length > 12));

    const rummikub = createPuzzleBlueprint("RUMMIKUB", { seed: "hidden-results", difficulty: "EXPERT" });
    rummikub.board.forEach((row, y) => row.forEach((cell, x) => {
      if (cell.isBlocked) return;
      assert.equal(cell.value, null);
      assert.ok(["RUN", "GROUP"].includes(String(cell.meta.meldType)));
      assert.equal(cell.meta.rule, undefined);
      assert.match(String(rummikub.answers[y]![x]), /^(RED|BLUE|GREEN|ORANGE):(1[0-3]|[1-9])$/);
    }));
  });

  for (const gameType of GAME_TYPES.filter((type) => !["SUDOKU", "CROSS_LETTERS", "SECRET_CODE", "CAPITAL_ARENA"].includes(type))) {
    it(`genera y permite a un Bot resolver ${gameType}`, () => {
      const players = new ArenaGame(`players-${gameType}`);
      players.addPlayer("bot", "Bot_Matriz", true);
      players.startMatch({ gameType, powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "HARD" }, "bot");
      const engine = new GenericPuzzleEngine(gameType, `test-${gameType}`);
      const initial = engine.snapshot(players);
      assert.ok(initial.rows > 0);
      assert.ok(initial.columns > 0);
      assert.equal(initial.board.length, initial.rows);

      for (let turn = 0; turn < 1_500 && !engine.snapshot(players).completed; turn += 1) {
        const move = engine.createBotMove(1);
        assert.ok(move, `${gameType} debe producir una jugada mientras no termine`);
        engine.makeMove("bot", move!, players, 1_000 + turn * 100);
      }
      assert.equal(engine.snapshot(players).completed, true, `${gameType} debe poder completarse`);
    });
  }

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

  it("Letras Cruzadas entrega atril privado y valida una palabra española por turnos", () => {
    const players = new ArenaGame("letters-players");
    players.addPlayer("bot", "Bot_Letras", true);
    players.startMatch({ gameType: "CROSS_LETTERS", powersEnabled: true, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "HARD", puzzleDifficulty: "MEDIUM" }, "bot");
    const engine = new GenericPuzzleEngine("CROSS_LETTERS", "letters-test");
    const initial = engine.snapshot(players, 1_000);
    assert.equal(initial.rows, 15);
    assert.equal(engine.rackFor("bot").length, 7);
    assert.equal(initial.meta.activePlayerId, "bot");
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

  it("inyecta pistas iniciales bloqueadas en Kakuro y Criptogramas", () => {
    const kakuro = createPuzzleBlueprint("KAKURO", { seed: "anchors", difficulty: "MEDIUM" });
    const kakuroGivens = kakuro.board.flat().filter((cell) => cell.meta.given === true);
    assert.equal(kakuroGivens.length, 3);
    assert.ok(kakuroGivens.every((cell) => cell.isBlocked && cell.value !== null));

    const crypt = createPuzzleBlueprint("CRYPTARITHM", { seed: "deduction", difficulty: "HARD" });
    const cryptGivens = crypt.board.flat().filter((cell) => cell.meta.given === true);
    assert.equal(cryptGivens.length, 2);
    assert.ok(cryptGivens.every((cell) => cell.isBlocked && typeof cell.value === "number"));
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
});
