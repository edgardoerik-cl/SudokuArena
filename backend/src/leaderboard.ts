import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { Pool } from "pg";
import type { GameType } from "./puzzles/types.js";

interface StoredPlayerRecord {
  nickname: string;
  bestSoloTimeMs: number | null;
  multiplayerWins: number;
  updatedAt: number;
}

interface LeaderboardFile {
  version: 1 | 2;
  players: Record<string, StoredPlayerRecord>;
  gameRecords?: Partial<Record<GameType, Record<string, StoredGameRecord>>>;
}

interface StoredGameRecord {
  nickname: string;
  bestTimeMs: number | null;
  bestScore: number;
  wins: number;
  updatedAt: number;
}

export interface GameLeaderboardEntry {
  rank: number;
  nickname: string;
  bestTimeMs?: number;
  bestScore?: number;
  wins: number;
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
  private data: LeaderboardFile = { version: 2, players: {}, gameRecords: {} };
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

  async topGame(gameType: GameType): Promise<{ gameType: GameType; time: GameLeaderboardEntry[]; score: GameLeaderboardEntry[] }> {
    await this.ready;
    if (this.pool) {
      const [time, score] = await Promise.all([
        this.pool.query<{ nickname: string; best_time_ms: string; wins: number }>(
          `SELECT nickname, best_time_ms, wins FROM multi_arena_game_leaderboard
           WHERE game_type=$1 AND best_time_ms IS NOT NULL ORDER BY best_time_ms ASC, updated_at ASC LIMIT 10`, [gameType]
        ),
        this.pool.query<{ nickname: string; best_score: number; wins: number }>(
          `SELECT nickname, best_score, wins FROM multi_arena_game_leaderboard
           WHERE game_type=$1 AND best_score > 0 ORDER BY best_score DESC, wins DESC, updated_at ASC LIMIT 10`, [gameType]
        )
      ]);
      return {
        gameType,
        time: time.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: Number(entry.best_time_ms), wins: Number(entry.wins) })),
        score: score.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestScore: Number(entry.best_score), wins: Number(entry.wins) }))
      };
    }
    const records = Object.values(this.data.gameRecords?.[gameType] ?? {});
    return {
      gameType,
      time: records.filter((entry): entry is StoredGameRecord & { bestTimeMs: number } => entry.bestTimeMs !== null)
        .sort((a, b) => a.bestTimeMs - b.bestTimeMs || b.bestScore - a.bestScore).slice(0, 10)
        .map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: entry.bestTimeMs, wins: entry.wins })),
      score: records.filter((entry) => entry.bestScore > 0)
        .sort((a, b) => b.bestScore - a.bestScore || b.wins - a.wins).slice(0, 10)
        .map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestScore: entry.bestScore, wins: entry.wins }))
    };
  }

  async recordGame(gameType: GameType, nickname: string, elapsedMs: number, score: number, won = false): Promise<void> {
    await this.ready;
    const clean = sanitizeNickname(nickname);
    const validTime = Number.isFinite(elapsedMs) && elapsedMs > 0 ? Math.round(elapsedMs) : null;
    const validScore = Math.max(0, Math.round(score));
    if (this.pool) {
      await this.pool.query(
        `INSERT INTO multi_arena_game_leaderboard (nickname_key, game_type, nickname, best_time_ms, best_score, wins, updated_at)
         VALUES ($1,$2,$3,$4,$5,$6,NOW())
         ON CONFLICT (nickname_key, game_type) DO UPDATE SET nickname=EXCLUDED.nickname,
           best_time_ms=CASE WHEN EXCLUDED.best_time_ms IS NULL THEN multi_arena_game_leaderboard.best_time_ms
             ELSE LEAST(COALESCE(multi_arena_game_leaderboard.best_time_ms, EXCLUDED.best_time_ms), EXCLUDED.best_time_ms) END,
           best_score=GREATEST(multi_arena_game_leaderboard.best_score, EXCLUDED.best_score),
           wins=multi_arena_game_leaderboard.wins + EXCLUDED.wins, updated_at=NOW()`,
        [clean.toLocaleLowerCase("es"), gameType, clean, validTime, validScore, won ? 1 : 0]
      );
      return;
    }
    const gameRecords = this.data.gameRecords ??= {};
    const records = gameRecords[gameType] ??= {};
    const key = clean.toLocaleLowerCase("es");
    const record = records[key] ?? { nickname: clean, bestTimeMs: null, bestScore: 0, wins: 0, updatedAt: Date.now() };
    record.nickname = clean;
    if (validTime !== null && (record.bestTimeMs === null || validTime < record.bestTimeMs)) record.bestTimeMs = validTime;
    record.bestScore = Math.max(record.bestScore, validScore);
    if (won) record.wins += 1;
    record.updatedAt = Date.now();
    records[key] = record;
    this.writeQueue = this.writeQueue.then(() => this.persist());
    await this.writeQueue;
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
      if ((parsed?.version === 1 || parsed?.version === 2) && parsed.players && typeof parsed.players === "object") {
        this.data = { ...parsed, version: 2, gameRecords: parsed.gameRecords ?? {} };
      }
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
    await this.pool!.query(
      `CREATE TABLE IF NOT EXISTS multi_arena_game_leaderboard (
        nickname_key TEXT NOT NULL,
        game_type TEXT NOT NULL,
        nickname TEXT NOT NULL,
        best_time_ms BIGINT NULL,
        best_score INTEGER NOT NULL DEFAULT 0,
        wins INTEGER NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (nickname_key, game_type)
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
