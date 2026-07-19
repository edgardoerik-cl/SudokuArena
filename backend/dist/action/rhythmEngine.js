const BPM = 128;
const BEAT_SECONDS = 60 / BPM;
/** Cámara, plataformas, vidas y tempo son autoritativos. */
export class RhythmEngine {
    players = new Map();
    platforms = [];
    cameraY = 0;
    elapsed = 0;
    tick = 0;
    nextPlatformY = 1;
    seed;
    constructor(seed, players) {
        this.seed = hash(seed);
        players.slice(0, 4).forEach((player, index) => this.players.set(player.id, {
            ...player, x: .2 + index * .2, y: .6, vy: 0, lives: 3, eliminated: false, moveX: 0, sequence: -1, isBot: player.isBot === true,
        }));
        for (let i = 0; i < 18; i += 1)
            this.spawnPlatform();
    }
    applyInput(id, input) {
        const player = this.players.get(id);
        const sequence = Math.floor(Number(input.sequence));
        if (!player || player.isBot || !Number.isFinite(sequence) || sequence <= player.sequence)
            return;
        player.sequence = sequence;
        player.moveX = clamp(Number(input.moveX), -1, 1);
    }
    update(rawDt) {
        const dt = clamp(rawDt, 0, .08);
        this.elapsed += dt;
        this.tick += 1;
        this.cameraY += dt * .42;
        while (this.nextPlatformY < this.cameraY + 12)
            this.spawnPlatform();
        for (const player of this.players.values()) {
            if (player.eliminated)
                continue;
            if (player.isBot)
                player.moveX = Math.sin(this.elapsed * 1.7 + player.x * 4);
            player.x = clamp(player.x + player.moveX * dt * .52, .03, .97);
            const previousY = player.y;
            player.vy -= 1.85 * dt;
            player.y += player.vy * dt;
            if (player.vy <= 0) {
                const landing = this.platforms.find((platform) => previousY >= platform.y && player.y <= platform.y &&
                    player.x >= platform.x && player.x <= platform.x + platform.width);
                if (landing) {
                    player.y = landing.y;
                    player.vy = .92;
                }
            }
            if (player.y < this.cameraY - 1.2) {
                player.lives -= 1;
                if (player.lives <= 0)
                    player.eliminated = true;
                else {
                    player.y = this.cameraY + .4;
                    player.x = .5;
                    player.vy = .92;
                }
            }
        }
        this.platforms = this.platforms.filter((platform) => platform.y > this.cameraY - 2);
    }
    snapshot(now = Date.now()) {
        return {
            serverTime: now, tick: this.tick, bpm: BPM, beat: Math.floor(this.elapsed / BEAT_SECONDS),
            cameraY: this.cameraY, completed: [...this.players.values()].filter((player) => !player.eliminated).length <= 1,
            platforms: this.platforms, players: [...this.players.values()].map(({ moveX: _m, sequence: _s, isBot: _b, ...player }) => player),
        };
    }
    spawnPlatform() {
        const beat = this.platforms.length;
        this.platforms.push({
            id: beat, x: .05 + this.random() * .66, y: this.nextPlatformY,
            width: .20 + this.random() * .18, obstacle: beat % 4 === 0,
        });
        this.nextPlatformY += .42 + this.random() * .18;
    }
    random() { this.seed = (this.seed * 1664525 + 1013904223) >>> 0; return this.seed / 0x1_0000_0000; }
}
function clamp(value, min, max) { return Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0; }
function hash(value) { let result = 2166136261; for (const char of value)
    result = Math.imul(result ^ char.charCodeAt(0), 16777619); return result >>> 0; }
//# sourceMappingURL=rhythmEngine.js.map