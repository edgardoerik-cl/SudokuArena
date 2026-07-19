import { randomUUID } from "node:crypto";
const DURATION = 300_000;
const SIZE = 17;
/** Mundo 2D autoritativo; Android únicamente proyecta rayos sobre `maze`. */
export class AbyssEngine {
    players = new Map();
    maze;
    exit = { x: SIZE - 2.5, y: SIZE - 2.5 };
    projectiles = [];
    boss;
    elapsed = 0;
    tick = 0;
    completed = false;
    seed;
    constructor(seed, players) {
        this.seed = hash(seed);
        this.maze = this.generateMaze();
        players.slice(0, 4).forEach((source, index) => this.players.set(source.id, {
            ...source, kind: "PLAYER", x: 1.5 + index * .24, y: 1.5, vx: 0, vy: 0,
            hp: 6, maxHp: 6, weapon: "BOW", kills: 0, deaths: 0, respawnAt: 0,
            facingX: 1, facingY: 0, attacking: false, input: neutral(), sequence: -1,
            rotation: 0, cooldown: 0, isBot: source.isBot === true,
        }));
        this.boss = {
            id: "ABYSS_WARDEN", kind: "BOSS", x: SIZE - 3.5, y: SIZE - 3.5, vx: 0, vy: 0,
            hp: 160, maxHp: 160, colorHex: "#FF3D71", name: "Guardián Arcano", weapon: "HAMMER",
            kills: 0, deaths: 0, respawnAt: 0, facingX: -1, facingY: 0, attacking: false,
        };
    }
    applyInput(id, input) {
        const player = this.players.get(id);
        const sequence = Math.floor(Number(input.sequence));
        if (!player || player.isBot || !Number.isFinite(sequence) || sequence <= player.sequence)
            return;
        player.sequence = sequence;
        player.input = {
            sequence, moveX: clamp(Number(input.moveX), -1, 1), moveY: clamp(Number(input.moveY), -1, 1),
            aimX: clamp(Number(input.aimX), -1, 1), aimY: clamp(Number(input.aimY), -1, 1), shooting: input.shooting === true,
        };
    }
    update(rawDt) {
        if (this.completed)
            return;
        const dt = clamp(rawDt, 0, .08);
        this.elapsed += dt * 1_000;
        this.tick += 1;
        for (const player of this.players.values()) {
            if (player.isBot)
                this.botIntent(player);
            player.rotation += player.input.aimX * dt * 2.5;
            const forward = -player.input.moveY;
            const strafe = player.input.moveX;
            const dx = Math.cos(player.rotation) * forward - Math.sin(player.rotation) * strafe;
            const dy = Math.sin(player.rotation) * forward + Math.cos(player.rotation) * strafe;
            player.vx = dx * 2.3;
            player.vy = dy * 2.3;
            this.move(player, player.vx * dt, player.vy * dt);
            player.facingX = Math.cos(player.rotation);
            player.facingY = Math.sin(player.rotation);
            player.cooldown -= dt;
            player.attacking = player.input.shooting;
            if (player.input.shooting && player.cooldown <= 0)
                this.cast(player);
            if (Math.hypot(player.x - this.exit.x, player.y - this.exit.y) < .55 && this.boss.hp <= 0)
                this.completed = true;
        }
        this.updateProjectiles(dt);
        if (this.elapsed >= DURATION)
            this.completed = true;
    }
    snapshot(now = Date.now()) {
        const actors = [...this.players.values()].map(({ input: _i, sequence: _s, rotation: _r, cooldown: _c, isBot: _b, ...actor }) => actor);
        return {
            serverTime: now, tick: this.tick, level: 1, maxLevel: 1, bossLevel: true,
            mode: "COOP_RAYCAST_RPG", remainingMs: Math.max(0, DURATION - this.elapsed),
            winnerId: this.completed && this.boss.hp <= 0 ? "PLAYERS" : null, completed: this.completed,
            actors: [...actors, this.boss], projectiles: this.projectiles, items: [],
            room: {
                seed: this.seed, maze: this.maze, exit: this.exit,
                obstacles: this.maze.flatMap((row, y) => row.map((wall, x) => wall ? { x, y, width: 1, height: 1 } : null)).filter((v) => v !== null),
            },
        };
    }
    cast(player) {
        player.cooldown = .32;
        this.projectiles.push({
            id: randomUUID(), ownerId: player.id, x: player.x, y: player.y,
            vx: player.facingX * 7, vy: player.facingY * 7, damage: 2, ttl: 2.2,
        });
    }
    updateProjectiles(dt) {
        for (const shot of this.projectiles) {
            shot.x += shot.vx * dt;
            shot.y += shot.vy * dt;
            shot.ttl -= dt;
            if (this.wall(shot.x, shot.y))
                shot.ttl = 0;
            if (shot.ownerId !== this.boss.id && Math.hypot(shot.x - this.boss.x, shot.y - this.boss.y) < .55) {
                this.boss.hp = Math.max(0, this.boss.hp - shot.damage);
                shot.ttl = 0;
                const owner = this.players.get(shot.ownerId);
                if (owner)
                    owner.kills += 2;
            }
        }
        this.projectiles = this.projectiles.filter((shot) => shot.ttl > 0);
    }
    move(actor, dx, dy) {
        if (!this.wall(actor.x + dx, actor.y))
            actor.x += dx;
        if (!this.wall(actor.x, actor.y + dy))
            actor.y += dy;
    }
    wall(x, y) {
        return this.maze[Math.floor(y)]?.[Math.floor(x)] !== 0;
    }
    botIntent(bot) {
        const dx = this.boss.x - bot.x;
        const dy = this.boss.y - bot.y;
        const target = Math.atan2(dy, dx);
        let delta = target - bot.rotation;
        while (delta > Math.PI)
            delta -= Math.PI * 2;
        while (delta < -Math.PI)
            delta += Math.PI * 2;
        bot.input = { sequence: ++bot.sequence, moveX: 0, moveY: Math.abs(delta) < .4 ? -1 : 0, aimX: clamp(delta * 2, -1, 1), aimY: 0, shooting: Math.abs(delta) < .14 };
    }
    generateMaze() {
        const maze = Array.from({ length: SIZE }, (_, y) => Array.from({ length: SIZE }, (_, x) => x === 0 || y === 0 || x === SIZE - 1 || y === SIZE - 1 ? 1 : 0));
        for (let y = 2; y < SIZE - 2; y += 2)
            for (let x = 2; x < SIZE - 2; x += 2) {
                maze[y][x] = 1;
                if (this.random() < .5)
                    maze[y][x + (this.random() < .5 ? 1 : -1)] = 1;
                else
                    maze[y + (this.random() < .5 ? 1 : -1)][x] = 1;
            }
        // corredor seguro diagonal para garantizar salida.
        for (let index = 1; index < SIZE - 1; index += 1) {
            maze[1][index] = 0;
            maze[index][SIZE - 2] = 0;
        }
        for (let x = SIZE - 4; x < SIZE - 1; x += 1)
            maze[SIZE - 4][x] = 0;
        return maze;
    }
    random() { this.seed = (this.seed * 1664525 + 1013904223) >>> 0; return this.seed / 0x1_0000_0000; }
}
function neutral() { return { sequence: 0, moveX: 0, moveY: 0, aimX: 0, aimY: 0, shooting: false }; }
function clamp(value, min, max) { return Number.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0; }
function hash(value) { let result = 2166136261; for (const char of value)
    result = Math.imul(result ^ char.charCodeAt(0), 16777619); return result >>> 0; }
//# sourceMappingURL=abyssEngine.js.map