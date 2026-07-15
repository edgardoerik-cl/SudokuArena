import assert from "node:assert/strict";
import { rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, describe, it } from "node:test";
import { LeaderboardStore } from "../src/leaderboard.js";

const file = join(tmpdir(), `sudoku-arena-leaderboard-${process.pid}.json`);

after(async () => {
  await rm(file, { force: true });
});

describe("LeaderboardStore", () => {
  it("conserva el menor tiempo y ordena victorias globales", async () => {
    const store = new LeaderboardStore(file);
    await store.recordSolo("Ada", 120_000);
    await store.recordSolo("Ada", 105_000);
    await store.recordSolo("Linus", 110_000);
    await store.recordMultiplayerWin("Linus");
    await store.recordMultiplayerWin("Linus");
    await store.recordMultiplayerWin("Ada");

    const top = await store.topTen();
    assert.deepEqual(top.solo.map((entry) => [entry.nickname, entry.bestTimeMs]), [
      ["Ada", 105_000],
      ["Linus", 110_000]
    ]);
    assert.deepEqual(top.multiplayer.map((entry) => [entry.nickname, entry.wins]), [
      ["Linus", 2],
      ["Ada", 1]
    ]);
  });

  it("mantiene tiempo y puntaje independientes para cada juego", async () => {
    const store = new LeaderboardStore(file);
    await store.recordGame("MINESWEEPER", "Ada", 80_000, 120, true);
    await store.recordGame("MINESWEEPER", "Ada", 95_000, 180, false);
    await store.recordGame("KAKURO", "Ada", 70_000, 90, true);
    await store.recordGame("MINESWEEPER", "Linus", 75_000, 100, false);

    const mines = await store.topGame("MINESWEEPER");
    assert.deepEqual(mines.time.map((entry) => [entry.nickname, entry.bestTimeMs]), [
      ["Linus", 75_000],
      ["Ada", 80_000]
    ]);
    assert.deepEqual(mines.score.map((entry) => [entry.nickname, entry.bestScore]), [
      ["Ada", 180],
      ["Linus", 100]
    ]);

    const kakuro = await store.topGame("KAKURO");
    assert.equal(kakuro.time.length, 1);
    assert.equal(kakuro.time[0]?.bestTimeMs, 70_000);
  });
});
