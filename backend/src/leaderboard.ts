import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

interface StoredPlayerRecord {
  nickname: string;
  bestSoloTimeMs: number | null;
  multiplayerWins: number;
  updatedAt: number;
}

interface LeaderboardFile {
  version: 1;
  players: Record<string, StoredPlayerRecord>;
}

export interface SoloLeaderboardEntry {
  rank: number;
  nickname: string;
  bestTimeMs: number;
}

export interface MultiplayerLeaderboardEntry {
  rank: number;
  nickname: string;
  wins: number;
}

/**
 * Persistencia JSON pequeña para el prototipo. Las escrituras se serializan y
 * reemplazan el archivo mediante rename para evitar estados parciales.
 */
export class LeaderboardStore {
  private data: LeaderboardFile = { version: 1, players: {} };
  private readonly ready: Promise<void>;
  private writeQueue: Promise<void> = Promise.resolve();

  constructor(private readonly filePath = process.env.LEADERBOARD_FILE ?? resolve("data", "leaderboards.json")) {
    this.ready = this.load();
  }

  async topTen(): Promise<{ solo: SoloLeaderboardEntry[]; multiplayer: MultiplayerLeaderboardEntry[] }> {
    await this.ready;
    const records = Object.values(this.data.players);
    return {
      solo: records
        .filter((entry): entry is StoredPlayerRecord & { bestSoloTimeMs: number } => entry.bestSoloTimeMs !== null)
        .sort((left, right) => left.bestSoloTimeMs - right.bestSoloTimeMs || left.updatedAt - right.updatedAt)
        .slice(0, 10)
        .map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: entry.bestSoloTimeMs })),
      multiplayer: records
        .filter((entry) => entry.multiplayerWins > 0)
        .sort((left, right) => right.multiplayerWins - left.multiplayerWins || left.updatedAt - right.updatedAt)
        .slice(0, 10)
        .map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, wins: entry.multiplayerWins }))
    };
  }

  async recordSolo(nickname: string, elapsedMs: number): Promise<void> {
    await this.mutate((record) => {
      if (record.bestSoloTimeMs === null || elapsedMs < record.bestSoloTimeMs) {
        record.bestSoloTimeMs = elapsedMs;
        record.updatedAt = Date.now();
      }
    }, nickname);
  }

  async recordMultiplayerWin(nickname: string): Promise<void> {
    await this.mutate((record) => {
      record.multiplayerWins += 1;
      record.updatedAt = Date.now();
    }, nickname);
  }

  private async mutate(change: (record: StoredPlayerRecord) => void, nickname: string): Promise<void> {
    await this.ready;
    const clean = sanitizeNickname(nickname);
    const key = clean.toLocaleLowerCase("es");
    const record = this.data.players[key] ?? {
      nickname: clean,
      bestSoloTimeMs: null,
      multiplayerWins: 0,
      updatedAt: Date.now()
    };
    record.nickname = clean;
    change(record);
    this.data.players[key] = record;
    this.writeQueue = this.writeQueue.then(() => this.persist());
    await this.writeQueue;
  }

  private async load(): Promise<void> {
    try {
      const parsed = JSON.parse(await readFile(this.filePath, "utf8")) as LeaderboardFile;
      if (parsed?.version === 1 && parsed.players && typeof parsed.players === "object") this.data = parsed;
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code !== "ENOENT") console.error("No se pudo leer el Cuadro de Honor", error);
    }
  }

  private async persist(): Promise<void> {
    await mkdir(dirname(this.filePath), { recursive: true });
    const temporary = `${this.filePath}.${process.pid}.tmp`;
    await writeFile(temporary, JSON.stringify(this.data, null, 2), "utf8");
    await rename(temporary, this.filePath);
  }
}

export function sanitizeNickname(value: unknown): string {
  const clean = typeof value === "string" ? value.trim().replace(/\s+/g, " ").slice(0, 20) : "";
  if (!clean) throw new Error("Nickname inválido");
  return clean;
}
