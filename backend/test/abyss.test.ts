import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { AbyssEngine } from "../src/action/abyssEngine.js";

describe("Abismo Arena", () => {
  it("crea una arena PvP sin enemigos y procesa movimiento/ataque autoritativo", () => {
    const engine = new AbyssEngine("abyss-test", [
      { id: "p1", name: "Nova", colorHex: "#00E5FF" },
      { id: "p2", name: "Vega", colorHex: "#FF2DAA" },
    ]);
    const before = engine.snapshot(1_000);
    assert.equal(before.mode, "PVP_FFA");
    assert.equal(before.actors.length, 2);
    assert.ok(before.actors.every((actor) => actor.kind === "PLAYER"));
    assert.ok(before.room.obstacles.length >= 8);

    const start = before.actors.find((actor) => actor.id === "p1")!;
    engine.applyInput("p1", { sequence: 1, moveX: 1, moveY: 0, aimX: 0, aimY: -1, shooting: true });
    for (let tick = 0; tick < 10; tick += 1) engine.update(.05);
    const after = engine.snapshot(1_500);
    const moved = after.actors.find((actor) => actor.id === "p1")!;
    assert.ok(moved.x > start.x);
    assert.equal(moved.weapon, "SWORD");
    assert.equal(moved.attacking, true);
    assert.equal(after.tick, 10);
  });

  it("genera armas aleatorias en el laberinto y mueve bots como contendientes", () => {
    const engine = new AbyssEngine("pvp-items", [
      { id: "p1", name: "Nova", colorHex: "#00E5FF" },
      { id: "bot", name: "Bot", colorHex: "#FF2DAA", isBot: true },
    ]);
    const before = engine.snapshot().actors.find((actor) => actor.id === "bot")!;
    for (let tick = 0; tick < 70; tick += 1) engine.update(.05);
    const after = engine.snapshot();
    const bot = after.actors.find((actor) => actor.id === "bot")!;
    assert.ok(bot.x !== before.x || bot.y !== before.y);
    assert.ok(after.items.length > 0);
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
