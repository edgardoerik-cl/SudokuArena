import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { AbyssEngine } from "../src/action/abyssEngine.js";

describe("Abismo Arena", () => {
  it("mantiene 20 niveles, genera enemigos y procesa input autoritativo", () => {
    const engine = new AbyssEngine("abyss-test", [
      { id: "p1", name: "Nova", colorHex: "#00E5FF" },
      { id: "p2", name: "Vega", colorHex: "#FF2DAA" },
    ]);
    const before = engine.snapshot(1_000);
    assert.equal(before.level, 1);
    assert.equal(before.maxLevel, 20);
    assert.ok(before.actors.some((actor) => actor.kind === "ENEMY"));

    const start = before.actors.find((actor) => actor.id === "p1")!;
    engine.applyInput("p1", { sequence: 1, moveX: 1, moveY: 0, aimX: 0, aimY: -1, shooting: true });
    for (let tick = 0; tick < 10; tick += 1) engine.update(.05);
    const after = engine.snapshot(1_500);
    const moved = after.actors.find((actor) => actor.id === "p1")!;
    assert.ok(moved.x > start.x);
    assert.ok(after.projectiles.length > 0);
    assert.equal(after.tick, 10);
  });

  it("ignora secuencias de input antiguas", () => {
    const engine = new AbyssEngine("sequence-test", [{ id: "p1", name: "Nova", colorHex: "#00E5FF" }]);
    engine.applyInput("p1", { sequence: 10, moveX: 1, moveY: 0 });
    engine.applyInput("p1", { sequence: 9, moveX: -1, moveY: 0 });
    const start = engine.snapshot().actors.find((actor) => actor.id === "p1")!.x;
    engine.update(.05);
    const end = engine.snapshot().actors.find((actor) => actor.id === "p1")!.x;
    assert.ok(end > start);
  });
});
