import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { AbyssEngine } from "../src/action/abyssEngine.js";
import { RhythmEngine } from "../src/action/rhythmEngine.js";

describe("Abismo Arena", () => {
  it("crea un RPG raycasting cooperativo con matriz de laberinto", () => {
    const engine = new AbyssEngine("abyss-test", [
      { id: "p1", name: "Nova", colorHex: "#00E5FF" },
      { id: "p2", name: "Vega", colorHex: "#FF2DAA" },
    ]);
    const before = engine.snapshot(1_000);
    assert.equal(before.mode, "COOP_RAYCAST_RPG");
    assert.equal(before.actors.filter((actor) => actor.kind === "PLAYER").length, 2);
    assert.equal(before.actors.filter((actor) => actor.kind === "BOSS").length, 1);
    assert.equal(before.room.maze.length, 17);

    const start = before.actors.find((actor) => actor.id === "p1")!;
    engine.applyInput("p1", { sequence: 1, moveX: 1, moveY: 0, aimX: 0, aimY: -1, shooting: true });
    for (let tick = 0; tick < 10; tick += 1) engine.update(.05);
    const after = engine.snapshot(1_500);
    const moved = after.actors.find((actor) => actor.id === "p1")!;
    assert.ok(moved.x !== start.x || moved.y !== start.y);
    assert.equal(moved.weapon, "BOW");
    assert.equal(moved.attacking, true);
    assert.equal(after.tick, 10);
  });

  it("mueve bots y les permite disparar cooperativamente al jefe", () => {
    const engine = new AbyssEngine("pvp-items", [
      { id: "p1", name: "Nova", colorHex: "#00E5FF" },
      { id: "bot", name: "Bot", colorHex: "#FF2DAA", isBot: true },
    ]);
    const before = engine.snapshot().actors.find((actor) => actor.id === "bot")!;
    const bossBefore = engine.snapshot().actors.find((actor) => actor.kind === "BOSS")!;
    for (let tick = 0; tick < 70; tick += 1) engine.update(.05);
    const after = engine.snapshot();
    const bot = after.actors.find((actor) => actor.id === "bot")!;
    assert.ok(bot.x !== before.x || bot.y !== before.y);
    const bossAfter = after.actors.find((actor) => actor.kind === "BOSS")!;
    assert.ok(bossAfter.hp <= bossBefore.hp);
  });

  it("ignora secuencias de input antiguas", () => {
    const engine = new AbyssEngine("sequence-test", [{ id: "p1", name: "Nova", colorHex: "#00E5FF" }]);
    engine.applyInput("p1", { sequence: 10, moveX: 1, moveY: 0 });
    engine.applyInput("p1", { sequence: 9, moveX: -1, moveY: 0 });
    const start = engine.snapshot().actors.find((actor) => actor.id === "p1")!;
    engine.update(.05);
    const end = engine.snapshot().actors.find((actor) => actor.id === "p1")!;
    assert.ok(end.x !== start.x || end.y !== start.y);
  });
});

describe("Salto Rítmico Arena", () => {
  it("sincroniza plataformas, BPM y cámara vertical autoritativa", () => {
    const engine = new RhythmEngine("rhythm-test", [
      { id: "p1", name: "Nova", colorHex: "#00E5FF" },
      { id: "p2", name: "Vega", colorHex: "#FF2DAA" },
    ]);
    const before = engine.snapshot(1_000);
    engine.applyInput("p1", { sequence: 1, moveX: 1 });
    for (let tick = 0; tick < 40; tick += 1) engine.update(.05);
    const after = engine.snapshot(3_000);
    assert.equal(after.bpm, 128);
    assert.ok(after.cameraY > before.cameraY);
    assert.ok(after.platforms.length > 0);
    assert.ok(after.beat > before.beat);
  });
});
