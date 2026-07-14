import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
/**
 * Persistencia JSON pequeña para el prototipo. Las escrituras se serializan y
 * reemplazan el archivo mediante rename para evitar estados parciales.
 */
export class LeaderboardStore {
    filePath;
    data = { version: 1, players: {} };
    ready;
    writeQueue = Promise.resolve();
    constructor(filePath = process.env.LEADERBOARD_FILE ?? resolve("data", "leaderboards.json")) {
        this.filePath = filePath;
        this.ready = this.load();
    }
    async topTen() {
        await this.ready;
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
        await this.mutate((record) => {
            if (record.bestSoloTimeMs === null || elapsedMs < record.bestSoloTimeMs) {
                record.bestSoloTimeMs = elapsedMs;
                record.updatedAt = Date.now();
            }
        }, nickname);
    }
    async recordMultiplayerWin(nickname) {
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