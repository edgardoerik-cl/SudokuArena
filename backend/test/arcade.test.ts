import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { DemolitionArenaEngine, PacmanArenaEngine, TetrisArenaEngine } from "../src/action/arcadeEngines.js";

const players = [
  { id: "p1", name: "Nova", color: "#00E5FF", slot: 0, score: 0, blockedUntil: 0, energy: 0, teamId: "A", role: "PLAYER", teamScore: 0, isBot: false, shieldUntil: 0, combo: 0, maxCombo: 0, comboMultiplier: 1, powerLoadout: [], avatarId: "ORBIT" },
  { id: "p2", name: "Vega", color: "#FF2DAA", slot: 1, score: 0, blockedUntil: 0, energy: 0, teamId: "B", role: "PLAYER", teamScore: 0, isBot: false, shieldUntil: 0, combo: 0, maxCombo: 0, comboMultiplier: 1, powerLoadout: [], avatarId: "NOVA" },
] as any;

describe("Tetris Arena", () => {
  it("usa tableros 10x20 y una bolsa de siete piezas", () => {
    const engine = new TetrisArenaEngine();
    engine.syncPlayers(players);
    engine.input("p1", "ROTATE");
    engine.input("p1", "HARD_DROP");
    const state = engine.snapshot();
    assert.equal(state.players.length, 2);
    assert.equal(state.players[0].board.length, 20);
    assert.equal(state.players[0].board[0].length, 10);
    assert.ok(state.players[0].next);
    assert.equal(state.players[0].impact, 1);
    assert.equal(state.players[0].abilityEnergy, 0);
    assert.equal(engine.input("p1", "HOLD"), false, "Hold debe ganarse completando una línea");
    assert.equal(engine.input("p1", "CLEAN_BOMB"), false, "La bomba requiere cuatro líneas de energía");
  });

  it("confirma la secuencia de entrada y separa tablero fijo para predicción local", () => {
    const engine = new TetrisArenaEngine();
    engine.syncPlayers(players);
    engine.input("p1", "LEFT", 41);
    const player = engine.snapshot().players.find((candidate: { id: string }) => candidate.id === "p1");
    assert.equal(player.lastInputSeq, 41);
    assert.equal(player.settledBoard.length, 20);
    assert.equal(player.piece.col, 2);
  });
});

describe("Pac-Man Arena", () => {
  it("publica tilemap y fantasmas con máquina de estados", () => {
    const engine = new PacmanArenaEngine();
    engine.syncPlayers(players);
    assert.equal(engine.snapshot().status, "WAITING");
    engine.tick(900);
    assert.equal(engine.snapshot().tick, 0);
    engine.input("p1", "LEFT");
    for (let tick = 0; tick < 12; tick += 1) engine.tick(1_000 + tick * 100);
    const state = engine.snapshot();
    assert.equal(state.tilemap.length, 15);
    assert.equal(state.ghosts.length, 4);
    assert.equal(state.status, "PLAYING");
    assert.ok(state.ghosts.every((ghost: { mode: string }) => ["CHASE", "SCATTER", "FRIGHTENED", "EATEN"].includes(ghost.mode)));
    assert.ok(["LEFT", "UP", "DOWN", "RIGHT", "STOP"].includes(state.players[0].direction));
  });
});

describe("Demolición Arcade", () => {
  it("mantiene física autoritativa, colisiones y niveles por jugador", () => {
    const engine = new DemolitionArenaEngine();
    engine.syncPlayers(players);
    assert.equal(engine.input("p1", .82), true);
    for (let tick = 0; tick < 120; tick += 1) engine.tick(1 / 60);
    const state = engine.snapshot();
    assert.equal(state.players.length, 2);
    assert.equal(state.players[0].paddleX, .82);
    assert.ok(state.players[0].bricks.length > 0);
    assert.ok(state.players[0].ballX >= 0 && state.players[0].ballX <= 1);
    assert.ok(state.players[0].ballY >= 0 && state.players[0].ballY <= 1);
    assert.ok(state.players[0].balls.length >= 1);
    assert.ok(Array.isArray(state.players[0].drops));
  });
});
