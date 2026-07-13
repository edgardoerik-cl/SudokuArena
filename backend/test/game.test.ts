import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ArenaGame } from "../src/game.js";
import { SOLUTION } from "../src/constants.js";

describe("ArenaGame", () => {
  it("acepta al primero y rechaza al segundo en la misma casilla", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    game.addPlayer("p2", "Linus");

    const first = game.place("p1", { requestId: "a", row: 0, column: 0, value: 5 }, 1_000);
    const second = game.place("p2", { requestId: "b", row: 0, column: 0, value: 5 }, 1_000);

    assert.equal(first.accepted, true);
    assert.equal(second.accepted, false);
    if (!second.accepted) assert.equal(second.code, "CELL_OCCUPIED");
    assert.equal(game.snapshot().board[0]![0]!.ownerId, "p1");
  });

  it("bloquea tres segundos después de un valor incorrecto", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    const wrong = game.place("p1", { requestId: "a", row: 0, column: 0, value: 4 }, 10_000);
    const duringPenalty = game.place("p1", { requestId: "b", row: 0, column: 0, value: 5 }, 12_000);

    assert.equal(wrong.accepted, false);
    if (!wrong.accepted) assert.equal(wrong.blockedUntil, 13_000);
    assert.equal(duringPenalty.accepted, false);
    if (!duringPenalty.accepted) assert.equal(duringPenalty.code, "BLOCKED");
  });

  it("conquista y limpia una fila completa", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    let finalResult: ReturnType<ArenaGame["place"]> | undefined;

    for (let column = 0; column < 9; column += 1) {
      finalResult = game.place("p1", {
        requestId: `r-${column}`,
        row: 0,
        column,
        value: SOLUTION[0]![column]!
      });
    }

    assert.equal(finalResult?.accepted, true);
    if (!finalResult?.accepted) return;
    assert.deepEqual(finalResult.sections, [{ kind: "row", index: 0 }]);
    assert.equal(game.snapshot().board[0]!.every((cell) => cell.clearing), true);
    assert.ok(finalResult.clearPlan);
    game.executeClear(finalResult.clearPlan!);
    assert.equal(game.snapshot().board[0]!.every((cell) => cell.value === null), true);
  });
});
