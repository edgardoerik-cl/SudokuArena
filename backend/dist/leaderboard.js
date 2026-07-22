import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { Pool } from "pg";
/**
 * Persistencia JSON pequeña para el prototipo. Las escrituras se serializan y
 * reemplazan el archivo mediante rename para evitar estados parciales.
 */
export class LeaderboardStore {
    filePath;
    data = { version: 3, players: {}, gameRecords: {}, gameAttempts: [] };
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
    async topGame(gameType) {
        await this.ready;
        if (this.pool) {
            const [time, score] = await Promise.all([
                this.pool.query(`SELECT nickname, elapsed_ms, won FROM multi_arena_game_attempts
           WHERE game_type=$1 AND elapsed_ms IS NOT NULL
           ORDER BY elapsed_ms ASC, score DESC, created_at ASC LIMIT 10`, [gameType]),
                this.pool.query(`SELECT nickname, score, elapsed_ms, won FROM multi_arena_game_attempts
           WHERE game_type=$1 ORDER BY score DESC, elapsed_ms ASC NULLS LAST, created_at ASC LIMIT 10`, [gameType])
            ]);
            return {
                gameType,
                time: time.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: Number(entry.elapsed_ms), wins: entry.won ? 1 : 0 })),
                score: score.rows.map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestScore: Number(entry.score), wins: entry.won ? 1 : 0 }))
            };
        }
        const attempts = (this.data.gameAttempts ?? []).filter((entry) => entry.gameType === gameType);
        return {
            gameType,
            time: attempts.filter((entry) => entry.elapsedMs !== null)
                .sort((a, b) => a.elapsedMs - b.elapsedMs || b.score - a.score || a.createdAt - b.createdAt).slice(0, 10)
                .map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestTimeMs: entry.elapsedMs, wins: entry.won ? 1 : 0 })),
            score: attempts.slice()
                .sort((a, b) => b.score - a.score || (a.elapsedMs ?? Number.MAX_SAFE_INTEGER) - (b.elapsedMs ?? Number.MAX_SAFE_INTEGER) || a.createdAt - b.createdAt).slice(0, 10)
                .map((entry, index) => ({ rank: index + 1, nickname: entry.nickname, bestScore: entry.score, wins: entry.won ? 1 : 0 }))
        };
    }
    async recordGame(gameType, nickname, elapsedMs, score, won = false) {
        await this.ready;
        const clean = sanitizeNickname(nickname);
        const validTime = Number.isFinite(elapsedMs) && elapsedMs > 0 ? Math.round(elapsedMs) : null;
        const validScore = Math.max(0, Math.round(score));
        if (this.pool) {
            const client = await this.pool.connect();
            try {
                await client.query("BEGIN");
                await client.query(`INSERT INTO multi_arena_game_attempts (game_type, nickname, elapsed_ms, score, won, created_at)
           VALUES ($1,$2,$3,$4,$5,NOW())`, [gameType, clean, validTime, validScore, won]);
                await client.query(`INSERT INTO multi_arena_game_leaderboard (nickname_key, game_type, nickname, best_time_ms, best_score, wins, updated_at)
           VALUES ($1,$2,$3,$4,$5,$6,NOW())
           ON CONFLICT (nickname_key, game_type) DO UPDATE SET nickname=EXCLUDED.nickname,
             best_time_ms=CASE WHEN EXCLUDED.best_time_ms IS NULL THEN multi_arena_game_leaderboard.best_time_ms
               ELSE LEAST(COALESCE(multi_arena_game_leaderboard.best_time_ms, EXCLUDED.best_time_ms), EXCLUDED.best_time_ms) END,
             best_score=GREATEST(multi_arena_game_leaderboard.best_score, EXCLUDED.best_score),
             wins=multi_arena_game_leaderboard.wins + EXCLUDED.wins, updated_at=NOW()`, [clean.toLocaleLowerCase("es"), gameType, clean, validTime, validScore, won ? 1 : 0]);
                await client.query("COMMIT");
            }
            catch (error) {
                await client.query("ROLLBACK");
                throw error;
            }
            finally {
                client.release();
            }
            return;
        }
        (this.data.gameAttempts ??= []).push({
            gameType, nickname: clean, elapsedMs: validTime, score: validScore, won, createdAt: Date.now()
        });
        const gameRecords = this.data.gameRecords ??= {};
        const records = gameRecords[gameType] ??= {};
        const key = clean.toLocaleLowerCase("es");
        const record = records[key] ?? { nickname: clean, bestTimeMs: null, bestScore: 0, wins: 0, updatedAt: Date.now() };
        record.nickname = clean;
        if (validTime !== null && (record.bestTimeMs === null || validTime < record.bestTimeMs))
            record.bestTimeMs = validTime;
        record.bestScore = Math.max(record.bestScore, validScore);
        if (won)
            record.wins += 1;
        record.updatedAt = Date.now();
        records[key] = record;
        this.writeQueue = this.writeQueue.then(() => this.persist());
        await this.writeQueue;
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
            if ((parsed?.version === 1 || parsed?.version === 2 || parsed?.version === 3) && parsed.players && typeof parsed.players === "object") {
                const migratedAttempts = parsed.gameAttempts ?? Object.entries(parsed.gameRecords ?? {}).flatMap(([gameType, records]) => Object.values(records ?? {}).map((record) => ({
                    gameType: gameType,
                    nickname: record.nickname,
                    elapsedMs: record.bestTimeMs,
                    score: record.bestScore,
                    won: record.wins > 0,
                    createdAt: record.updatedAt,
                })));
                this.data = { ...parsed, version: 3, gameRecords: parsed.gameRecords ?? {}, gameAttempts: migratedAttempts };
            }
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
        await this.pool.query(`CREATE TABLE IF NOT EXISTS multi_arena_game_leaderboard (
        nickname_key TEXT NOT NULL,
        game_type TEXT NOT NULL,
        nickname TEXT NOT NULL,
        best_time_ms BIGINT NULL,
        best_score INTEGER NOT NULL DEFAULT 0,
        wins INTEGER NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (nickname_key, game_type)
      )`);
        await this.pool.query(`CREATE TABLE IF NOT EXISTS multi_arena_game_attempts (
        id BIGSERIAL PRIMARY KEY,
        source_key TEXT UNIQUE NULL,
        game_type TEXT NOT NULL,
        nickname TEXT NOT NULL,
        elapsed_ms BIGINT NULL,
        score INTEGER NOT NULL DEFAULT 0,
        won BOOLEAN NOT NULL DEFAULT FALSE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )`);
        await this.pool.query(`CREATE INDEX IF NOT EXISTS idx_multi_arena_attempts_time
       ON multi_arena_game_attempts (game_type, elapsed_ms ASC) WHERE elapsed_ms IS NOT NULL`);
        await this.pool.query(`CREATE INDEX IF NOT EXISTS idx_multi_arena_attempts_score
       ON multi_arena_game_attempts (game_type, score DESC)`);
        // Conserva el mejor dato historico agregado una sola vez al migrar.
        await this.pool.query(`INSERT INTO multi_arena_game_attempts (source_key, game_type, nickname, elapsed_ms, score, won, created_at)
       SELECT 'legacy:' || nickname_key || ':' || game_type, game_type, nickname, best_time_ms, best_score, wins > 0, updated_at
       FROM multi_arena_game_leaderboard
       ON CONFLICT (source_key) DO NOTHING`);
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