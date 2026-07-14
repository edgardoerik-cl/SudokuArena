import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { once } from "node:events";
import { after, before, describe, it } from "node:test";
import { io, type Socket } from "socket.io-client";

const port = 32_000 + Math.floor(Math.random() * 1_000);
const url = `http://127.0.0.1:${port}`;
let server: ChildProcess;
const clients: Socket[] = [];

before(async () => {
  server = spawn(process.execPath, ["--import", "tsx", "src/server.ts"], {
    cwd: new URL("../", import.meta.url),
    env: { ...process.env, PORT: String(port) },
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
});

describe("matchmaking por salas", () => {
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

    const carol = await connect("Carol");
    const carolJoined = nextEvent<any>(carol, "game:joined");
    carol.emit("room:create");
    const roomTwo = await carolJoined;
    assert.notEqual(roomTwo.roomCode, roomOne.roomCode);

    let leakedToCarol = false;
    carol.once("reaction_received", () => { leakedToCarol = true; });
    const reactionAtBob = nextEvent<any>(bob, "reaction_received");
    alice.emit("send_reaction", { emojiId: "LAUGH" });
    const reaction = await reactionAtBob;
    assert.equal(reaction.emojiId, "LAUGH");
    await delay(150);
    assert.equal(leakedToCarol, false);
  });
});

async function connect(name: string): Promise<Socket> {
  const socket = io(url, { transports: ["websocket"], auth: { name }, reconnection: false });
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

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
