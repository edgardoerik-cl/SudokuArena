import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ArenaGame } from "../src/game.js";
import { SOLUTION, createRandomSolution } from "../src/constants.js";

describe("ArenaGame", () => {
  it("inicia Sudoku online con pistas bloqueadas según dificultad", () => {
    const game = new ArenaGame("online-clues");
    game.addPlayer("p1", "Ada");
    game.startMatch({ gameType: "SUDOKU", powersEnabled: true, teamMode: "DUEL", tileType: "NUMBERS", botDifficulty: "MEDIUM", puzzleDifficulty: "HARD" }, "p1");
    const givens = game.snapshot().board.flat().filter((cell) => cell.given);
    assert.equal(givens.length, 26);
    assert.ok(givens.every((cell) => cell.value !== null && cell.ownerId === null));
    const target = game.snapshot().board.flatMap((row, rowIndex) => row.map((cell, column) => ({ cell, row: rowIndex, column }))).find((entry) => entry.cell.given)!;
    const result = game.place("p1", { requestId: "given-cell", row: target.row, column: target.column, value: target.cell.value! });
    assert.equal(result.accepted, false);
    if (!result.accepted) assert.equal(result.code, "CELL_OCCUPIED");
  });
  it("genera soluciones aleatorias válidas para salas independientes", () => {
    const solution = createRandomSolution();
    const expected = "123456789";
    assert.equal(solution.length, 9);
    for (const row of solution) assert.equal([...row].sort().join(""), expected);
    for (let column = 0; column < 9; column += 1) {
      assert.equal(solution.map((row) => row[column]).sort().join(""), expected);
    }
    for (let box = 0; box < 9; box += 1) {
      const values: number[] = [];
      const startRow = Math.floor(box / 3) * 3;
      const startColumn = (box % 3) * 3;
      for (let row = startRow; row < startRow + 3; row += 1) {
        for (let column = startColumn; column < startColumn + 3; column += 1) values.push(solution[row]![column]!);
      }
      assert.equal(values.sort().join(""), expected);
    }
  });

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

  it("multiplica puntos por combos rápidos y los corta al fallar", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Combo");
    for (let column = 0; column < 6; column += 1) {
      game.place("p1", {
        requestId: `combo-${column}`,
        row: 0,
        column,
        value: SOLUTION[0]![column]!
      }, 1_000 + column * 500);
    }
    let player = game.snapshot().players[0]!;
    assert.equal(player.combo, 6);
    assert.equal(player.comboMultiplier, 2);
    assert.equal(player.maxCombo, 6);
    assert.equal(player.score, 90);

    game.place("p1", { requestId: "combo-fail", row: 1, column: 0, value: 1 }, 5_000);
    player = game.snapshot().players[0]!;
    assert.equal(player.combo, 0);
    assert.equal(player.comboMultiplier, 1);
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

  it("acumula energía y consume 100% al lanzar niebla", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    game.addPlayer("p2", "Linus");

    for (let column = 0; column < 4; column += 1) {
      game.place("p1", {
        requestId: `energy-${column}`,
        row: 0,
        column,
        value: SOLUTION[0]![column]!
      });
    }

    assert.equal(game.snapshot().players.find((player) => player.id === "p1")?.energy, 100);
    assert.equal(game.useFogPower("p1", "p1").accepted, false);
    const power = game.useFogPower("p1", "p2");
    assert.equal(power.accepted, true);
    assert.equal(game.snapshot().players.find((player) => player.id === "p1")?.energy, 0);
  });

  it("refleja Niebla contra el atacante durante el Escudo de Espejo", () => {
    const game = new ArenaGame();
    game.addPlayer("attacker", "Atacante");
    game.addPlayer("defender", "Defensor");
    game.setPowerLoadout("defender", ["REFLECT", "REVEAL"]);
    for (let column = 0; column < 4; column += 1) {
      game.place("attacker", { requestId: `a-${column}`, row: 0, column, value: SOLUTION[0]![column]! });
      game.place("defender", { requestId: `d-${column}`, row: 1, column, value: SOLUTION[1]![column]! });
    }
    const shield = game.useReflectPower("defender", 10_000);
    assert.equal(shield.accepted, true);
    const fog = game.useFogPower("attacker", "defender", 12_000);
    assert.equal(fog.accepted, true);
    if (fog.accepted && fog.type === "FOG") {
      assert.equal(fog.reflected, true);
      assert.equal(fog.recipientPlayerId, "attacker");
    }
  });

  it("Ojo de Lince coloca la solución, da puntos y consume exactamente 50 de energía", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    game.place("p1", { requestId: "charge-1", row: 0, column: 0, value: SOLUTION[0]![0]! });
    game.place("p1", { requestId: "charge-2", row: 0, column: 1, value: SOLUTION[0]![1]! });
    const reveal = game.useRevealPower("p1", 0, 2, "reveal", 5_000);
    assert.equal(reveal.accepted, true);
    const state = game.snapshot();
    assert.equal(state.board[0]![2]!.value, SOLUTION[0]![2]);
    assert.equal(state.board[0]![2]!.ownerId, "p1");
    assert.equal(state.players[0]?.score, 30);
    assert.equal(state.players[0]?.energy, 0);
  });

  it("asigna los cuatro colores neón de identidad sin repetir", () => {
    const game = new ArenaGame();
    for (const id of ["p1", "p2", "p3", "p4"]) game.addPlayer(id, id);
    assert.deepEqual(game.snapshot().players.map((player) => player.color), [
      "#00A8FF", "#FF2DAA", "#00C853", "#FF8A00"
    ]);
  });

  it("aplica puntos dobles y penalización de seis segundos en Hora Espejo", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    game.startBoardEvent("MIRROR_HOUR", 10_000);

    const correct = game.place("p1", { requestId: "mirror-ok", row: 0, column: 0, value: 5 }, 10_100);
    const wrong = game.place("p1", { requestId: "mirror-bad", row: 0, column: 1, value: 9 }, 10_200);

    assert.equal(correct.accepted, true);
    if (correct.accepted) assert.equal(correct.cellPoints, 20);
    assert.equal(game.snapshot().players[0]?.score, 20);
    assert.equal(wrong.accepted, false);
    if (!wrong.accepted) assert.equal(wrong.blockedUntil, 16_200);
  });

  it("marca dos casillas doradas y entrega el bono al primer acierto", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Ada");
    game.startBoardEvent("GOLDEN_CELLS", 1_000);
    const snapshot = game.snapshot();
    const goldenCells = snapshot.board.flatMap((row, rowIndex) =>
      row.map((cell, column) => ({ cell, row: rowIndex, column })).filter(({ cell }) => cell.golden)
    );

    assert.equal(goldenCells.length, 2);
    const golden = goldenCells[0]!;
    const result = game.place("p1", {
      requestId: "golden",
      row: golden.row,
      column: golden.column,
      value: SOLUTION[golden.row]![golden.column]!
    });

    assert.equal(result.accepted, true);
    if (result.accepted) assert.equal(result.goldenBonus, 50);
    assert.equal(game.snapshot().players[0]?.score, 60);
  });

  it("comparte el puntaje de equipo y la conquista de celda en 2 vs 2", () => {
    const game = new ArenaGame();
    for (const id of ["p1", "p2", "p3", "p4"]) game.addPlayer(id, id, id === "p1");
    game.startMatch({ gameType: "SUDOKU", powersEnabled: true, teamMode: "TWO_V_TWO", tileType: "COLORS", botDifficulty: "MEDIUM" }, "p1");
    const correct = game.createBotProposal("p1", 1)!;
    game.place("p1", { ...correct, requestId: "team" });
    const state = game.snapshot();

    assert.equal(state.players.find((player) => player.id === "p1")?.teamScore, 10);
    assert.equal(state.players.find((player) => player.id === "p3")?.teamScore, 10);
    assert.equal(state.players.find((player) => player.id === "p2")?.teamScore, 0);
    assert.equal(state.board[correct.row]![correct.column]!.ownerTeamId, "TEAM_A");
  });

  it("desactiva energía y niebla cuando el host apaga poderes", () => {
    const game = new ArenaGame();
    game.addPlayer("p1", "Host");
    game.addPlayer("p2", "Rival");
    game.startMatch({ gameType: "SUDOKU", powersEnabled: false, teamMode: "FFA", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "p1");
    game.place("p1", { requestId: "no-power", row: 0, column: 0, value: 5 });

    assert.equal(game.snapshot().players.find((player) => player.id === "p1")?.energy, 0);
    const power = game.useFogPower("p1", "p2");
    assert.equal(power.accepted, false);
    if (!power.accepted) assert.equal(power.code, "POWER_DISABLED");
  });

  it("otorga al Jefe carga y puntos dobles en 3 vs 1", () => {
    const game = new ArenaGame();
    for (const id of ["boss", "r1", "r2", "r3"]) game.addPlayer(id, id, id === "boss");
    game.startMatch({ gameType: "SUDOKU", powersEnabled: true, teamMode: "THREE_V_ONE", tileType: "NUMBERS", botDifficulty: "MEDIUM" }, "boss");
    game.place("boss", { ...game.createBotProposal("boss", 1)!, requestId: "boss-hit" });
    const boss = game.snapshot().players.find((player) => player.id === "boss");

    assert.equal(boss?.role, "BOSS");
    assert.equal(boss?.score, 20);
    assert.equal(boss?.energy, 50);
  });

  it("genera jugadas de Bot correctas y erróneas mediante el mismo validador", () => {
    const game = new ArenaGame();
    game.addPlayer("bot", "Bot_Pro", true);
    const correct = game.createBotProposal("bot", 1);
    assert.ok(correct);
    assert.equal(game.place("bot", correct!).accepted, true);

    const wrong = game.createBotProposal("bot", 0);
    assert.ok(wrong);
    const result = game.place("bot", wrong!);
    assert.equal(result.accepted, false);
    if (!result.accepted) assert.equal(result.code, "INCORRECT_VALUE");
    assert.equal(game.snapshot().players[0]?.isBot, true);
  });

  it("permite que un Bot sea el Jefe autoritativo en 3 vs 1", () => {
    const game = new ArenaGame();
    for (const id of ["h1", "h2", "h3"]) game.addPlayer(id, id);
    game.addPlayer("bot-boss", "Bot_Jefe", true);
    game.startMatch(
      { gameType: "SUDOKU", powersEnabled: true, teamMode: "THREE_V_ONE", tileType: "COLORS", botDifficulty: "HARD" },
      "bot-boss"
    );
    const state = game.snapshot();
    assert.equal(state.players.find((player) => player.id === "bot-boss")?.role, "BOSS");
    assert.equal(state.players.filter((player) => player.role === "RAIDER").length, 3);
  });
});
