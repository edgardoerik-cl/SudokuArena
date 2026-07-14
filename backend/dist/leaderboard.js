import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { Pool } from "pg";
/**
 * Persistencia JSON pequeña para el prototipo. Las escrituras se serializan y
 * reemplazan el archivo mediante rename para evitar estados parciales.
 */
export class LeaderboardStore {
    filePath;
    data = { version: 1, players: {} };
    ready;
    writeQueue = Promise.resolve();
    pool;
    constructor(filePath = process.env.LEADERBOARD_FILE ?? resolve("data", "leaderboards.json")) {
        this.filePath = filePath;
        this.pool = process.env.DATABASE_URL
            ? new Pool({
                connectionString: process.env.DATABASE_URL,
                ssl: process.env.PGSSLMODE === "disable" ? false : { rejectUnauthorized: false }
            })
            : null;
        this.ready = this.pool ? this.initializeDatabase() : this.load();
    }
    async topTen() {
        await this.ready;
        if (this.pool) {
            const [solo, multiplayer] = await Promise.all([
                this.pool.query(`SELECT nickname, best_solo_time_ms AS best_time_ms
           FROM sudoku_arena_leaderboard WHERE best_solo_time_ms IS NOT NULL
           ORDER BY best_solo_time_ms ASC, updated_at ASC LIMIT 10`),
                this.pool.query(`SELECT nickname, multiplayer_wins AS wins
           FROM sudoku_arena_leaderboard WHERE multiplayer_wins > 0
           ORDER BY multiplayer_wins DESC, updated_at ASC LIMIT 10`)
            ]);
            return {
                solo: solo.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: Number(entry.best_time_ms) })),
                multiplayer: multiplayer.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, wins: Number(entry.wins) }))
            };
        }
        const records = Object.values(this.data.players);
        return {
            solo: records
                .filter((entry) => entry.bestSoloTimeMs !== null)
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
    async recordSolo(nickname, elapsedMs) {
        if (this.pool) {
            await this.ready;
            const clean = sanitizeNickname(nickname);
            await this.pool.query(`INSERT INTO sudoku_arena_leaderboard (nickname_key, nickname, best_solo_time_ms, multiplayer_wins, updated_at)
         VALUES ($1, $2, $3, 0, NOW())
         ON CONFLICT (nickname_key) DO UPDATE SET
           nickname = EXCLUDED.nickname,
           best_solo_time_ms = LEAST(COALESCE(sudoku_arena_leaderboard.best_solo_time_ms, EXCLUDED.best_solo_time_ms), EXCLUDED.best_solo_time_ms),
           updated_at = NOW()`, [clean.toLocaleLowerCase("es"), clean, elapsedMs]);
            return;
        }
        await this.mutate((record) => {
            if (record.bestSoloTimeMs === null || elapsedMs < record.bestSoloTimeMs) {
                record.bestSoloTimeMs = elapsedMs;
                record.updatedAt = Date.now();
            }
        }, nickname);
    }
    async recordMultiplayerWin(nickname) {
        if (this.pool) {
            await this.ready;
            const clean = sanitizeNickname(nickname);
            await this.pool.query(`INSERT INTO sudoku_arena_leaderboard (nickname_key, nickname, best_solo_time_ms, multiplayer_wins, updated_at)
         VALUES ($1, $2, NULL, 1, NOW())
         ON CONFLICT (nickname_key) DO UPDATE SET
           nickname = EXCLUDED.nickname,
           multiplayer_wins = sudoku_arena_leaderboard.multiplayer_wins + 1,
           updated_at = NOW()`, [clean.toLocaleLowerCase("es"), clean]);
            return;
        }
        await this.mutate((record) => {
            record.multiplayerWins += 1;
            record.updatedAt = Date.now();
        }, nickname);
    }
    async mutate(change, nickname) {
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
    async load() {
        try {
            const parsed = JSON.parse(await readFile(this.filePath, "utf8"));
            if (parsed?.version === 1 && parsed.players && typeof parsed.players === "object")
                this.data = parsed;
        }
        catch (error) {
            const code = error.code;
            if (code !== "ENOENT")
                console.error("No se pudo leer el Cuadro de Honor", error);
        }
    }
    async initializeDatabase() {
        await this.pool.query(`CREATE TABLE IF NOT EXISTS sudoku_arena_leaderboard (
        nickname_key TEXT PRIMARY KEY,
        nickname TEXT NOT NULL,
        best_solo_time_ms BIGINT NULL,
        multiplayer_wins INTEGER NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )`);
    }
    async persist() {
        await mkdir(dirname(this.filePath), { recursive: true });
        const temporary = `${this.filePath}.${process.pid}.tmp`;
        await writeFile(temporary, JSON.stringify(this.data, null, 2), "utf8");
        await rename(temporary, this.filePath);
    }
}
export function sanitizeNickname(value) {
    const clean = typeof value === "string" ? value.trim().replace(/\s+/g, " ").slice(0, 20) : "";
    if (!clean)
        throw new Error("Nickname inválido");
    return clean;
}
//# sourceMappingURL=leaderboard.js.map