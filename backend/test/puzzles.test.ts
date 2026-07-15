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
      assert.equal(cell.value, null);
      assert.match(String(cell.meta.rule), /\?$/);
      assert.ok(!String(cell.meta.rule).endsWith(String(rummikub.answers[y]![x])));
    }));
  });

  for (const gameType of GAME_TYPES.filter((type) => type !== "SUDOKU")) {
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
