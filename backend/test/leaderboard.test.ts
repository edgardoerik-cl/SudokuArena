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
});
