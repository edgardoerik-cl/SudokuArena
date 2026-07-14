import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { Pool } from "pg";

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
  private readonly pool: Pool | null;

  constructor(private readonly filePath = process.env.LEADERBOARD_FILE ?? resolve("data", "leaderboards.json")) {
    this.pool = process.env.DATABASE_URL
      ? new Pool({
          connectionString: process.env.DATABASE_URL,
          ssl: process.env.PGSSLMODE === "disable" ? false : { rejectUnauthorized: false }
        })
      : null;
    this.ready = this.pool ? this.initializeDatabase() : this.load();
  }

  async topTen(): Promise<{ solo: SoloLeaderboardEntry[]; multiplayer: MultiplayerLeaderboardEntry[] }> {
    await this.ready;
    if (this.pool) {
      const [solo, multiplayer] = await Promise.all([
        this.pool.query<{ nickname: string; best_time_ms: string }>(
          `SELECT nickname, best_solo_time_ms AS best_time_ms
           FROM sudoku_arena_leaderboard WHERE best_solo_time_ms IS NOT NULL
           ORDER BY best_solo_time_ms ASC, updated_at ASC LIMIT 10`
        ),
        this.pool.query<{ nickname: string; wins: number }>(
          `SELECT nickname, multiplayer_wins AS wins
           FROM sudoku_arena_leaderboard WHERE multiplayer_wins > 0
           ORDER BY multiplayer_wins DESC, updated_at ASC LIMIT 10`
        )
      ]);
      return {
        solo: solo.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: Number(entry.best_time_ms) })),
        multiplayer: multiplayer.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, wins: Number(entry.wins) }))
      };
    }
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
    if (this.pool) {
      await this.ready;
      const clean = sanitizeNickname(nickname);
      await this.pool.query(
        `INSERT INTO sudoku_arena_leaderboard (nickname_key, nickname, best_solo_time_ms, multiplayer_wins, updated_at)
         VALUES ($1, $2, $3, 0, NOW())
         ON CONFLICT (nickname_key) DO UPDATE SET
           nickname = EXCLUDED.nickname,
           best_solo_time_ms = LEAST(COALESCE(sudoku_arena_leaderboard.best_solo_time_ms, EXCLUDED.best_solo_time_ms), EXCLUDED.best_solo_time_ms),
           updated_at = NOW()`,
        [clean.toLocaleLowerCase("es"), clean, elapsedMs]
      );
      return;
    }
    await this.mutate((record) => {
      if (record.bestSoloTimeMs === null || elapsedMs < record.bestSoloTimeMs) {
        record.bestSoloTimeMs = elapsedMs;
        record.updatedAt = Date.now();
      }
    }, nickname);
  }

  async recordMultiplayerWin(nickname: string): Promise<void> {
    if (this.pool) {
      await this.ready;
      const clean = sanitizeNickname(nickname);
      await this.pool.query(
        `INSERT INTO sudoku_arena_leaderboard (nickname_key, nickname, best_solo_time_ms, multiplayer_wins, updated_at)
         VALUES ($1, $2, NULL, 1, NOW())
         ON CONFLICT (nickname_key) DO UPDATE SET
           nickname = EXCLUDED.nickname,
           multiplayer_wins = sudoku_arena_leaderboard.multiplayer_wins + 1,
           updated_at = NOW()`,
        [clean.toLocaleLowerCase("es"), clean]
      );
      return;
    }
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

  private async initializeDatabase(): Promise<void> {
    await this.pool!.query(
      `CREATE TABLE IF NOT EXISTS sudoku_arena_leaderboard (
        nickname_key TEXT PRIMARY KEY,
        nickname TEXT NOT NULL,
        best_solo_time_ms BIGINT NULL,
        multiplayer_wins INTEGER NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )`
    );
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
