import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ArenaGame } from "../src/game.js";
import { GenericPuzzleEngine } from "../src/puzzles/engine.js";
import { GAME_TYPES } from "../src/puzzles/types.js";

describe("motor genérico de puzzles", () => {
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
