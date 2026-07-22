import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import { io, type Socket } from "socket.io-client";

const port = 32_000 + Math.floor(Math.random() * 1_000);
const url = `http://127.0.0.1:${port}`;
const leaderboardFile = join(tmpdir(), `sudoku-arena-room-test-${port}.json`);
let server: ChildProcess;
const clients: Socket[] = [];

before(async () => {
  server = spawn(process.execPath, ["--import", "tsx", "src/server.ts"], {
    cwd: new URL("../", import.meta.url),
    env: {
      ...process.env,
      PORT: String(port),
      MATCH_DURATION_MS: "2500",
      SUDDEN_DEATH_DURATION_MS: "500",
      RPS_ENABLED: "false",
      LEADERBOARD_FILE: leaderboardFile
    },
    stdio: "ignore"
  });
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const response = await fetch(`${url}/health`);
      if (response.ok) return;
    } catch {
      // El proceso todavía está iniciando.
    }
    await delay(100);
  }
  throw new Error("El servidor de integración no inició");
});

after(async () => {
  for (const client of clients) client.disconnect();
  if (server && !server.killed) {
    server.kill("SIGTERM");
    await Promise.race([once(server, "exit"), delay(2_000)]);
  }
  await rm(leaderboardFile, { force: true });
});

describe("matchmaking por salas", () => {
  it("publica la versión vigente y el enlace oficial de actualización", async () => {
    const response = await fetch(`${url}/api/app-version`);
    assert.equal(response.status, 200);
    const update = await response.json() as {
      versionCode: number;
      versionName: string;
      downloadUrl: string;
    };
    assert.equal(update.versionCode, 59);
    assert.equal(update.versionName, "8.12.0");
    assert.match(update.downloadUrl, /^https:\/\/drive\.google\.com\/drive\/folders\//);
  });

  it("publica y conserva el mejor récord solitario por HTTP", async () => {
    const firstChallenge = await fetch(`${url}/api/solo/challenge`, { method: "POST" }).then((value) => value.json()) as { token: string };
    const first = await fetch(`${url}/api/leaderboards/solo`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ nickname: "Speedy", elapsedMs: 125_000, challengeToken: firstChallenge.token })
    });
    assert.equal(first.status, 200);
    const replay = await fetch(`${url}/api/leaderboards/solo`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ nickname: "Speedy", elapsedMs: 124_000, challengeToken: firstChallenge.token })
    });
    assert.equal(replay.status, 403);

    const secondChallenge = await fetch(`${url}/api/solo/challenge`, { method: "POST" }).then((value) => value.json()) as { token: string };
    await fetch(`${url}/api/leaderboards/solo`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ nickname: "Speedy", elapsedMs: 110_000, challengeToken: secondChallenge.token })
    });
    const response = await fetch(`${url}/api/leaderboards`);
    const top = await response.json() as { solo: Array<{ rank: number; nickname: string; bestTimeMs: number }> };
    assert.deepEqual(top.solo[0], { rank: 1, nickname: "Speedy", bestTimeMs: 110_000 });
    const perGame = await fetch(`${url}/api/leaderboards/game?gameType=SUDOKU`).then((value) => value.json()) as { time: Array<{ nickname: string }> };
    assert.equal(perGame.time[0]?.nickname, "Speedy");
  });

  it("crea códigos de cuatro dígitos y aísla eventos entre salas", async () => {
    const alice = await connect("Alice");
    const aliceJoined = nextEvent<any>(alice, "game:joined");
    alice.emit("room:create");
    const roomOne = await aliceJoined;

    assert.match(roomOne.roomCode, /^\d{4}$/);
    assert.equal(roomOne.state.players.length, 1);

    const bob = await connect("Bob");
    const bobJoined = nextEvent<any>(bob, "game:joined");
    bob.emit("room:join", { roomCode: roomOne.roomCode });
    const bobState = await bobJoined;
    assert.equal(bobState.roomCode, roomOne.roomCode);
    assert.equal(bobState.state.players.length, 2);

    const configuredAtBob = nextMatchingEvent<any>(bob, "room:state", (state) => state.config.powersEnabled === false);
    alice.emit("room:configure", { powersEnabled: false, teamMode: "FFA", tileType: "COLORS" });
    const configured = await configuredAtBob;
    assert.equal(configured.config.powersEnabled, false);
    assert.equal(configured.config.tileType, "COLORS");

    const startedAtBob = nextEvent<any>(bob, "game:started");
    const finishedAtBob = nextEvent<any>(bob, "game:finished", 4_000);
    alice.emit("room:start");
    const started = await startedAtBob;
    assert.ok(started.endsAt > started.startedAt);

    const carol = await connect("Carol");
    const carolJoined = nextEvent<any>(carol, "game:joined");
    carol.emit("room:create");
    const roomTwo = await carolJoined;
    assert.notEqual(roomTwo.roomCode, roomOne.roomCode);
    const botJoined = nextMatchingEvent<any>(carol, "game:state", (state) =>
      state.players.length === 2 && state.players.some((player: any) => player.isBot)
    );
    carol.emit("fill_with_ai");
    const hybridState = await botJoined;
    assert.equal(hybridState.players.filter((player: any) => player.isBot).length, 1);
    const configuredAtCarol = nextMatchingEvent<any>(
      carol,
      "room:state",
      (state) => state.config.botDifficulty === "HARD"
    );
    carol.emit("room:configure", {
      powersEnabled: true,
      teamMode: "FFA",
      tileType: "COLORS",
      botDifficulty: "HARD"
    });
    await configuredAtCarol;
    const botActed = nextMatchingEvent<any>(carol, "game:state", (state) =>
      state.players.some((player: any) => player.isBot && (player.score > 0 || player.blockedUntil > state.serverTime))
    , 2_200);
    carol.emit("room:start");
    await botActed;

    let leakedToCarol = false;
    carol.once("reaction_received", () => { leakedToCarol = true; });
    const reactionAtBob = nextEvent<any>(bob, "reaction_received");
    alice.emit("send_reaction", { emojiId: "LAUGH" });
    const reaction = await reactionAtBob;
    assert.equal(reaction.emojiId, "LAUGH");
    await delay(150);
    assert.equal(leakedToCarol, false);
    const finished = await finishedAtBob;
    assert.equal(finished.results.length, 2);
    assert.equal(finished.results[0].rank, 1);

    const rematchAtBob = nextMatchingEvent<any>(bob, "room:state", (state) => state.phase === "PLAYING");
    alice.emit("room:rematch");
    bob.emit("room:rematch");
    const rematch = await rematchAtBob;
    assert.equal(rematch.rematchVotes, 0);
    assert.equal(rematch.suddenDeath, false);
  });

  it("reanuda una partida con la misma identidad tras un corte breve", async () => {
    const identity = `client-${port}`;
    const first = await connect("Reconnect", identity);
    const created = nextEvent<any>(first, "game:joined");
    first.emit("room:create");
    const initial = await created;
    first.emit("fill_with_ai");
    await nextMatchingEvent<any>(first, "game:state", (state) => state.players.length === 2);
    first.emit("room:start");
    await nextEvent(first, "game:started");
    first.disconnect();

    const resumed = await connect("Reconnect", identity);
    const rejoined = nextEvent<any>(resumed, "game:joined");
    resumed.emit("room:join", { roomCode: initial.roomCode });
    const state = await rejoined;
    assert.equal(state.playerId, initial.playerId);
    assert.equal(state.roomState.phase, "PLAYING");
    assert.equal(state.state.players.filter((player: any) => !player.isBot).length, 1);
  });

  it("sincroniza una arena genérica mediante make_move", async () => {
    const host = await connect("Multiarena", `multi-${port}`);
    const joinedPromise = nextEvent<any>(host, "game:joined");
    host.emit("room:create");
    await joinedPromise;
    host.emit("fill_with_ai");
    await nextMatchingEvent<any>(host, "game:state", (state) => state.players.length === 2);
    const configured = nextMatchingEvent<any>(host, "room:state", (state) => state.config.gameType === "WORD_SEARCH");
    host.emit("room:configure", {
      gameType: "WORD_SEARCH",
      powersEnabled: true,
      teamMode: "FFA",
      tileType: "NUMBERS",
      botDifficulty: "MEDIUM"
    });
    await configured;
    const genericStarted = nextMatchingEvent<any>(host, "generic:state", (state) => state.gameType === "WORD_SEARCH");
    host.emit("room:start");
    const initial = await genericStarted;
    assert.equal(initial.rows, 10);
    assert.equal(initial.columns, 10);

    const placement = initial.meta.placements[0];
    const accepted = nextEvent<any>(host, "generic:move-accepted");
    host.emit("make_move", {
      requestId: "word-path",
      row: placement.startRow,
      col: placement.startCol,
      val: { word: placement.word, endRow: placement.endRow, endCol: placement.endCol }
    });
    const result = await accepted;
    assert.equal(result.accepted, true);
    assert.equal(result.points, placement.word.length * 10);
  });

  it("pausa por consenso y reanuda con cuenta regresiva", async () => {
    const host = await connect("PausaHost", `pause-host-${port}`);
    const joined = nextEvent<any>(host, "game:joined");
    host.emit("room:create");
    const room = await joined;
    const guest = await connect("PausaGuest", `pause-guest-${port}`);
    const guestJoined = nextEvent<any>(guest, "game:joined");
    guest.emit("room:join", { roomCode: room.roomCode });
    await guestJoined;
    host.emit("room:start");
    await nextEvent(host, "game:started");

    const requested = nextEvent<any>(guest, "pause:requested");
    host.emit("pause:request");
    assert.equal((await requested).requesterId, room.playerId);
    const paused = nextMatchingEvent<any>(host, "room:state", (state) => state.phase === "PAUSED");
    guest.emit("pause:respond", { accepted: true });
    const pausedState = await paused;
    assert.equal(pausedState.pauseVotes, 2);

    const countdown = nextEvent<any>(guest, "pause:resuming");
    host.emit("pause:resume");
    assert.ok((await countdown).endsAt > Date.now());
    const resumed = await nextMatchingEvent<any>(guest, "room:state", (state) => state.phase === "PLAYING", 5_000);
    assert.ok(resumed.endsAt > Date.now());
  });
});

async function connect(name: string, clientId?: string): Promise<Socket> {
  const socket = io(url, { transports: ["websocket"], auth: { name, clientId }, reconnection: false });
  clients.push(socket);
  await nextEvent(socket, "connect");
  return socket;
}

function nextEvent<T = unknown>(socket: Socket, event: string, timeoutMs = 3_000): Promise<T> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off(event, handler);
      reject(new Error(`Timeout esperando ${event}`));
    }, timeoutMs);
    const handler = (payload: T) => {
      clearTimeout(timeout);
      resolve(payload);
    };
    socket.once(event, handler);
  });
}

function nextMatchingEvent<T>(
  socket: Socket,
  event: string,
  predicate: (payload: T) => boolean,
  timeoutMs = 3_000
): Promise<T> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off(event, handler);
      reject(new Error(`Timeout esperando ${event}`));
    }, timeoutMs);
    const handler = (payload: T) => {
      if (!predicate(payload)) return;
      clearTimeout(timeout);
      socket.off(event, handler);
      resolve(payload);
    };
    socket.on(event, handler);
  });
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
